# Proposal A — LATENCY FIRST

Design tournament, FAWE Folia port. Angle: **minimize time-to-visibility and player-perceived
responsiveness for edits, within the §1b hard rules.** Where a lever conflicts with the safety
invariants it is dropped, not bent — §1b and §4b(1) correctness win over latency, always.

## 0. Thesis: what W0.2 actually says the fight is about

W0.2 measured visibility at ~51–55 ms across all three commit strategies, and proved the number
is **almost entirely one region tick of scheduler delay** (`max_schedule_delay_us` 50.8–52.2 ms ≈
1 tick) sitting on top of **sub-millisecond commit work** (`max_commit_task_us` 364–705 µs). The
commit is not the cost. The *hop onto the owning region's next tick* is the cost.

That reframes latency-first into three concrete programs, and nothing else matters:

1. **Eliminate the hop when we are already the owner.** The only way below the tick floor is to
   commit inline on a thread that already owns the target chunk. This is the generalization of
   FAWE's existing `Fawe.isMainThread()` fast path (`SingleThreadQueueExtent.submitUnchecked:258`,
   `NMSAdapter.setSectionAtomic:133`) to `ownsChunk(world, cx, cz)`. It is the single biggest win
   and it is fully §1b-legal (ownership valid at execution time, no wait, no bypass).
2. **Amortize the hop we cannot eliminate.** For off-thread edits the tick delay is a hard floor
   (cross-thread scheduling cannot preempt a region mid-tick without violating ownership). So pay
   it **once per region per drain**, not once per chunk — a persistent per-region *commit pump*
   that drains every ready commit for that region in the tick it wakes. This is exactly why
   region-sweep won W0.2 (4 tasks vs 16, 364 µs vs 551 µs) at equal visibility.
3. **Hide the floor we cannot amortize.** Order visibility by distance-to-nearest-viewer and
   decouple block-visibility from finalizers/light/history. The player perceives *time-to-first-
   block-where-they-are-looking*, not time-to-last-block or time-to-operation-complete. A 500k
   edit and a 50-block edit should both light up the player's screen in ~1 tick locally.

Everything below serves those three. I mark every claim that outruns W0.2's evidence as
**[NEEDS-RUNTIME]**.

---

## 1. Java SPI shapes

### 1.1 `FaweThreadContext` (core seam, `worldedit-core`, `[W0-FREEZE]`)

Replaces the binary `Fawe.isMainThread()` identity model (`Fawe.java:210`, one captured `thread`).
On the Bukkit/Paper backend every predicate collapses to the old single-main-thread identity, so
Paper behavior is unchanged. On Folia the predicates are ownership questions answered against the
live region owner at call time.

```java
package com.fastasyncworldedit.core.util.task;

public interface FaweThreadContext {

    // Am I on a Minecraft tick thread at all (region, entity, or global)?
    boolean isTickThread();

    // Do I, right now, own the region covering (world, chunkX, chunkZ)?
    // Latency-critical: this is the gate for the inline commit fast path.
    boolean ownsChunk(World world, int chunkX, int chunkZ);

    boolean ownsEntity(Entity entity);

    // Am I on the global-region thread (world lifecycle, worldless work)?
    boolean isGlobalContext();

    // Am I a FAWE worker (fork-join / blocking pool)? Never a tick thread.
    boolean isFaweWorker();

    static FaweThreadContext get() { return Fawe.instance().threadContext(); }
}
```

Bukkit backend: `isTickThread() == ownsChunk(..) == isGlobalContext() == old isMainThread()`;
`isFaweWorker()` = not-main. Folia backend:

```java
final class FoliaThreadContext implements FaweThreadContext {
    @Override public boolean ownsChunk(World w, int cx, int cz) {
        return Bukkit.isOwnedByCurrentRegion(w, cx, cz);   // Folia RegionizedServer check
    }
    @Override public boolean ownsEntity(Entity e) { return Bukkit.isOwnedByCurrentRegion(e); }
    @Override public boolean isTickThread() { return TickThread.isTickThread(); }
    @Override public boolean isGlobalContext() { return Bukkit.isGlobalTickThread(); }
    @Override public boolean isFaweWorker() { return FaweForkJoinWorkerThread.isFaweWorker(); }
}
```

`ownsChunk` is a pure read of Folia's `TickRegions` owner map from the calling thread — no lock,
no hop. **[NEEDS-RUNTIME]** confirm `isOwnedByCurrentRegion(World,int,int)` is the cheap
lock-free path (it is documented as such; W0.2 A5 confirms region routing is correct, but the
per-call cost of the *predicate itself* on the hot commit path was not isolated).

### 1.2 `FoliaRegionDispatcher` (single choke point, adapter-26.1)

Every region hop goes through this one class. Latency-relevant additions over the naive shape:
an **inline fast path** and an **already-draining coalescing path**.

```java
public interface FoliaRegionDispatcher {

    /**
     * Run `commit` on the region owning (world, cx, cz).
     * If the caller already owns that region, run INLINE (no scheduler hop, sub-tick) —
     * the latency fast path. Otherwise enqueue on that region's commit pump (§3).
     * Never blocks the caller. Never caller-runs owner-bound work from a non-owner.
     */
    void dispatchCommit(World world, int cx, int cz, RegionCommit commit);

    /** Owner-thread continuation for a callback that arrived on a NON-owner thread (§4). */
    void reDispatch(World world, int cx, int cz, Runnable ownerOnly);

    /** Entity-scheduler hop (packet fan-out fallback, entity finalizers). */
    void dispatchEntity(Entity entity, Runnable ownerOnly);

    /** Global-region hop (world lifecycle only). */
    void dispatchGlobal(Runnable globalOnly);

    /** Bounded backpressure handle for (world, region) — §5. */
    RegionPermit acquire(World world, int cx, int cz) throws InterruptedException;
}

@FunctionalInterface
public interface RegionCommit {
    /** Executes on the owning region thread. Returns finalizer work to chain (§4). */
    CommitResult run() throws CommitException;
}
```

`dispatchCommit` decision, in order:

```java
void dispatchCommit(World w, int cx, int cz, RegionCommit c) {
    if (ctx.ownsChunk(w, cx, cz)) {          // FAST PATH — inline, no tick delay
        runInline(c);                         // still bounded by slice budget (§3.3)
        return;
    }
    pumpFor(w, cx, cz).offer(c);              // COALESCED PATH — rides next drain
}
```

There is **no** `RegionScheduler.execute` per commit in the common case. `execute` is used only to
*wake/create* a region's pump (§3.2), so the up-to-one-tick latency is paid at most once per
region per idle→busy transition, not per chunk.

### 1.3 `FoliaBackpressure` (per-region bounded in-flight, adapter-26.1)

```java
public interface FoliaBackpressure {
    RegionPermit acquire(long regionKey) throws InterruptedException; // bounded, no caller-runs
    void release(long regionKey);
    int inFlight(long regionKey);          // §8 signal
    int queueDepth(long regionKey);        // §8 signal, feeds C6 region load gate
}

public interface RegionPermit extends AutoCloseable {
    @Override void close();                 // release on commit completion
}
```

Bound is **per logical region key**, not global. A permit is a slot in that region's bounded
in-flight commit set. Workers `acquire` before handing a prepared chunk to the pump and hold it
until the commit's finalizers settle. Semaphore-based; fair FIFO with a priority override for
viewer-near chunks (§5). **Never** `CallerRunsPolicy` for owner-bound work — that is the current
`blockingExecutor` policy (`FaweCache.java:646`) and it is illegal here (§1b: no caller-runs for
owner-bound work; and a FAWE worker running a commit inline would not own the region anyway).

### 1.4 Context-carrying sync (C3, replaces the global drain)

`QueueHandler.run()`'s single global main-thread drain loop (`QueueHandler.java:108`, asserts
`isMainThread`) has no Folia analogue — there is no global main. Every location-free `sync(...)`
/ `syncWhenFree(...)` / `TaskManager.taskNowMain(...)` entry point is **preserved** (§4c, APIC
table) but its target is *derived*:

```java
public interface ContextSync {
    <T> T sync(World world, int cx, int cz, Supplier<T> ownerWork);   // derives R(world,cx,cz)
    <T> T syncEntity(Entity e, Supplier<T> ownerWork);                // derives E(entity)
    <T> T syncGlobal(Supplier<T> globalWork);                         // global region
    // Legacy location-free sync(Supplier): if caller is on a tick thread, run inline on the
    // CURRENT owner; else derive global. Deterministic per §4c. Never blocks a tick thread.
}
```

Internal callers migrate to the location-carrying variants by compile-error-driven migration
(SS2 pattern): the location-free overloads become `@Deprecated` on the Folia path and each of the
~140 `TaskManager.taskManager().{sync,task}` occurrences is requalified with a rationale record
(C3/C5). The latency payoff: a `sync` issued *from* a region thread that owns the target runs
inline (sub-tick), same fast-path logic as commits.

---

## 2. GET snapshot lifecycle

Unchanged from architecture §1 in mechanism; latency-tuned in *when* and *how eagerly* it runs.

1. **Capture (region thread).** For each needed chunk, a `prepareGet` task on the owning region
   copies palette/section data, biomes, tile-NBT, entity-NBT, and — critically for lighting —
   light nibbles (W0.3 L2: `getSkyLight`/`getEmittedLight` hide a `queueSectionData` write and
   MUST be region-captured, never run on a worker). Output is a detached immutable `IChunkGet`
   snapshot (C2, `live_refs_on_worker=false`, proven in W0.2 `PIPELINE_SAFETY_OK`).
2. **Cache.** Snapshot cached per `(world, cx, cz)` in the existing `ChunkCache`
   (`chunk/ChunkCache.java`), invalidated on commit of that chunk. Cache is now sharded by region
   key (not one coarse `synchronized` map — `QueueHandler.java:405`) to kill cross-region
   contention (hotspot #8, recon).
3. **Consume (worker).** Workers read only the detached snapshot. No live read escapes the worker.

**Latency levers on GET:**
- **Eager region-batched prefetch.** The `AsyncPreloader` (`AsyncPreloader.java`, TPS>18 gate) is
  replaced by a region-keyed preloader (C6): for the edit's chunk set, group by owning region and
  fire one `prepareGet` sweep per region *ahead of* the SET pass, so GET latency overlaps prepare
  compute instead of serializing in front of it. The old global TPS gate becomes a per-region
  queue-depth gate (C6, §8 signals).
- **Owner-inline GET.** If the actor's interaction fired on a region thread that owns the target
  (brush/wand — the interactive case), the GET runs inline in that same event, feeding an inline
  SET commit (§3.1) with zero scheduler hops on either side.
- **[NEEDS-RUNTIME]** W0.2 Q4: `getChunkAtAsync` completion landed owner-region *in one weak
  observation*. Snapshot capture that relies on async chunk load must still re-dispatch its
  continuation to the owner before the region-only reads (cheap; removes the assumption).

Cost owned honestly: eager per-region prefetch raises peak outstanding snapshots (heap, §8). The
region-keyed cache costs a little more memory than one global map. Both are latency-for-footprint
trades, quantified in §8.

---

## 3. SET commit flow

### 3.1 The two-tier execution model (central latency bet)

Edits are routed by **size and trigger context**, because the fast path only exists on tick
threads and only pays off when prepare is cheap enough not to stall a region tick.

**Tier 1 — inline-on-owner (interactive / small).** Triggered when the edit originates on a
region thread that owns (or comes to own, per chunk) the target *and* the prepared work for a
chunk fits the slice budget. Player brush/wand interactions in Folia fire on the player's region
thread; for a co-located target that is the owning region. Prepare (small, detached-data compute)
runs inline, section swap runs inline, packet built+sent inline — **sub-tick visibility, zero
scheduler delay.** This is the case W0.2 never measured (it always scheduled cross-thread) and it
is where latency-first decisively beats a naive port: interactive editing feels instant.

Guard rails (so this never stalls a region):
- A hard **per-inline-slice budget** (default ~2 ms of prepare+commit; configurable). If a chunk's
  prepared work would blow the budget, it is *not* run inline — it is offloaded to the worker pool
  and the coalesced path (Tier 2), preserving TPS. Correctness/TPS beats the latency shortcut.
- Inline path still respects backpressure accounting (it consumes a permit) so a burst of tiny
  edits cannot monopolize a region tick.

**Tier 2 — detached prepare + coalesced commit (bulk).** The proven W0.2 model. Workers build
complete detached `LevelChunkSection` replacements + finalizer lists off-thread; each prepared
chunk is offered to its region's commit pump (§3.2). This is every `//set`, `//paste`, large
brush, and any edit not originating on the owning tick thread.

### 3.2 The per-region commit pump (amortizes the tick floor)

Instead of `RegionScheduler.execute` per commit (each risking a fresh ~1-tick wait), each active
region has one lightweight **commit pump**: a drain task that, when it runs, empties that region's
ready-commit queue in a single tick (bounded by the slice cap, §3.3).

```
worker: prepare chunk  ──► pumpFor(region).offer(commit)  ──► if pump idle: wake it
                                                                 via RegionScheduler.execute
region tick N:  pump.drain():
    for commit in queue up to sliceCap (viewer-near first, §3.3):
        result = commit.run()            # section swap, sub-ms (W0.2: 364 µs sweep)
        buildAndSendPacket(result)       # blocks visible THIS tick
        chain finalizers (§4)
    if queue not empty: re-arm for tick N+1
```

Why this is the region-sweep winner from W0.2: the ~1-tick latency is paid **once**, by the first
chunk that wakes an idle pump. Every other chunk that a worker finishes before that drain fires
rides the *same* tick — visibility ≈ 1 tick for the whole region's share of the edit, regardless
of chunk count, exactly as W0.2 showed (4 tasks, equal 51 ms visibility). The pump replaces
W0.2's "4 scheduled sweep tasks" with a standing drain so we don't re-pay wake latency mid-edit.

**[NEEDS-RUNTIME]** the wake mechanism: `RegionScheduler.execute` schedules onto the region's
*next* tick; W0.2's ~51 ms is consistent with "just missed this tick." Whether Folia offers any
same-tick wake for an already-scheduled region (it should not, cross-thread) — assume not; the
floor is real. Average real-world wake latency under uniform arrival is ~½ tick (~25 ms), better
than W0.2's worst-case-looking 51 ms, but this must be measured, not claimed.

### 3.3 Strategy choice vs W0.2, and behavior at large N

**Choice: region-sweep drain with a mandatory per-drain chunk cap (sliced sweeps), viewer-near
ordering.** Directly justified by W0.2 §3:
- region-sweep: 4 tasks, 364 µs max task, 51.3 ms visibility (rank 1).
- batched-neighbor: 8 tasks, 705 µs, 51.3 ms (rank 2).
- per-chunk: 16 tasks, 551 µs, 55.0 ms (rank 3) — the only one whose visibility measurably
  *regressed*, because 16 scheduled tasks spread drain across more scheduler slots.

At **large N** (W0.2 §3 extrapolation, explicitly not measured):
- per-chunk scales tasks linearly (100k chunks → 100k tasks + matching queue highwater) → real
  visibility and tick-time divergence. Rejected.
- unsliced region-sweep collapses a region's whole share into one task that can exceed a tick
  (W0.2: "a sweep committing hundreds of chunks in one task could exceed a tick and must be
  sliced") → a single over-long commit blows the region's tick budget, hurting both TPS and, for
  latency-first, the *next* region's fairness.
- **sliced region-sweep** (chosen): each drain commits at most `sliceCap` chunks; the pump re-arms
  for the next tick if more remain. `sliceCap` is tuned for latency: large enough that the
  player's *local* chunks (which viewer-near ordering front-loads) all land in the first drain,
  small enough to hold the per-region tick budget. The W0.2 numbers give the direction (sub-ms per
  chunk, 364 µs for a 4-chunk sweep) but **not** the cap — that is a W0.8/W0.10a measurement.
  **[NEEDS-RUNTIME]** derive `sliceCap` from a per-tick commit-time budget (e.g. target ≤ ~3 ms of
  the ~50 ms tick), never a fixed chunk count, since per-chunk cost varies with section density.

**Region grouping is computed at execution time**, per W0.2's warning: Folia regions split/merge
dynamically, so the 4-cluster↔4-region 1:1 mapping W0.2 saw is not a design invariant. The pump is
keyed by *current* owner (`isOwnedByCurrentRegion`), and a chunk whose owner changed mid-edit
(ownership migration) is re-routed to the new owner's pump at offer time.

**Viewer-near ordering (the perceived-latency lever):** within a drain, commits are ordered by
the minimum distance from the chunk to any tracked viewer of that chunk. Result: the blocks a
player is looking at commit and send in the *first* drain tick; far chunks trail. Time-to-first-
visible-near-block ≈ 1 tick even for a 500k-block edit. This costs a sort per drain (cheap;
bounded by `sliceCap`) and needs the tracked-viewer set, read region-locally (W0.5 A1).
**[NEEDS-RUNTIME]** W0.2's packet leg had `tracked_viewer=false`; viewer-near ordering assumes a
correct tracked-viewer read on the region thread — verify with a real tracked viewer.

### 3.4 Packet timing (decouple visibility from finalization)

FAWE's `LIGHTING.DELAY_PACKET_SENDING` batches chunk resends until *after* the relight pass
(`PaperweightGetBlocks.java:758`). That is a throughput optimization that **adds visibility
latency** — the player waits for relight before seeing blocks. Latency-first inverts it:

- **Send the block packet at commit** (in the same region drain task, W0.5 §2d: build
  `ClientboundLevelChunkWithLightPacket` + `chunkMap.getPlayers(pos)` + `connection.send`, all
  region-owned reads). Blocks visible in the commit tick.
- **Relight runs after** (Tier-2 NMSRelighter, W0.3 L3), and a follow-up light packet is sent when
  the relight finalizer completes. Two sends per chunk in the worst case, but time-to-visible-
  blocks is minimized.

Cost owned: up to double chunk-packet bandwidth on lit edits, and a brief window where blocks are
visible with stale light before the relight packet lands. This is a deliberate latency-for-
bandwidth-and-transient-light-accuracy trade; it is configurable (fall back to
DELAY_PACKET_SENDING = the throughput rivals' default). **[NEEDS-RUNTIME]** W0.2 `client_ack=
pending_bot_chat`, `tracked_viewer=false`: server-side cross-owner `connection.send` did not throw
(A2 tentatively holds), but client-visible delivery to a tracked viewer is unconfirmed. If A2
fails, fall back to the per-viewer entity-scheduler fan-out (W0.5 2d) — a hop per off-region
viewer, which adds their region's tick to *their* visibility but not to the committing region's.

---

## 4. Finalizer ordering + non-owner-callback re-dispatch (mandatory)

Per-chunk commit produces an ordered finalizer chain, run **on the owning region thread** as the
continuation of the same drain task (or inline task), in this order (from W0.5 Part 3 + W0.3):

1. Section swap (the visible mutation) — `setSectionAtomic` on the owner (no `ChunkSendLock`
   needed; region-thread serialization subsumes it, W0.5 §2c).
2. `setLightCorrect` / `mustNotSave` field writes; `blockChanged` / `sendBlockUpdated`.
3. Tile entities: `getBlockEntity` / `loadWithComponents` (region-owned).
4. Entities: `addFreshEntity` / `removeEntity` (chunk/entity region — `ownsEntity` gate).
5. Beacon teardown / `removeBeacon` — **must move onto the owner** (today runs on the worker inside
   `internalCall`, W0.5 Part 3 flag).
6. Block packet send (§3.4) — region-owned reads, `connection.send` (cross-region viewer =
   [NEEDS-RUNTIME] A2 / entity-scheduler fallback).
7. Commit future completion + backpressure permit release + chunk-future resolution (C4).

**The mandatory rule (W0.2 §4, GO is conditional on it):** relight/moonrise completion callbacks
land on **non-owner** threads — W0.2 proved `Paper_Common_Worker_#0`, `owner=false` for both the
per-chunk and completion callbacks. **Any callback continuation that touches live state — ticket
removal, packet send, chunk-future completion, light-packet send, history finalization — MUST
re-dispatch to the owning region via `FoliaRegionDispatcher.reDispatch(world, cx, cz, ...)` before
touching anything live.** Treat every future/callback thread as hostile until re-dispatched (SS2
chunk-future discipline). This is written into the frozen C1/C4 wording, not left as convention.

Concretely, the relight completion path:

```java
relighter.whenComplete((chunk, err) ->                     // on Paper_Common_Worker, owner=false
    dispatcher.reDispatch(world, cx, cz, () -> {            // HOP to owner first
        materializeLight(chunk);                            // fillLightNibble on owner (W0.3 L1)
        sendLightPacket(chunk);                             // region-owned reads
        completeChunkFuture(chunk);                         // C4
    }));
```

Latency note: this re-dispatch adds *one* owner hop to the *light* finalization only — it does not
touch block visibility, which already happened at step 6 of the block commit. So the mandatory
safety hop costs light-update latency, never block-visibility latency. That is the right place to
pay it.

---

## 5. Backpressure (bounded, per-region, no caller-runs)

- **Bound:** a per-region-key semaphore of `maxInFlight` commit permits (default small, e.g. 4–8;
  tunable, a §8 signal). A worker `acquire`s before offering a prepared chunk to the pump and
  releases at finalizer completion (step 7). `inFlight`/`queueDepth` are exported as §8 signals and
  feed the C6 region-load gate that replaces the global TPS gate.
- **No caller-runs for owner-bound work.** The current `blockingExecutor` `CallerRunsPolicy`
  (`FaweCache.java:646`) is illegal here: a saturated region must **not** cause a FAWE worker (or
  any non-owner) to run the commit itself — the worker doesn't own the region, and §1b forbids
  caller-runs for owner-bound work. Instead the worker **blocks on the permit** (bounded, with
  timeout + cancellation), which naturally throttles *prepare* to the region's drain rate. This is
  the only legal wait (worker → owner, acyclic, bounded — §1b liveness). Tick threads never wait.
- **Latency-preserving priority.** Under saturation, plain FIFO would make the player's near chunks
  wait behind far ones. So the permit queue and the pump's drain queue are **priority queues keyed
  on viewer distance**: when a permit frees, the nearest-viewer prepared chunk gets it, and within
  a drain the nearest chunks commit first (§3.3). Latency-first spends its saturation budget on the
  chunks the player can see.
- **Liveness proof obligations (§1b):** every worker→owner wait has (a) a documented acyclic
  wait-for path (worker waits on region permit / region drain; region never waits on worker), (b)
  bounded timeout, (c) cancellation propagation (edit cancel releases permits + drops queued
  commits), (d) a thread-dump assertion in the harness that no tick thread is ever parked on a FAWE
  monitor.

---

## 6. §4d failure semantics

The operation completion future (C4) resolves only after every **accepted** commit + server-owned
finalizer succeeded, and every async failure reaches the actor/API **exactly once**. Latency-first
does not weaken this — it only changes *when the player sees blocks*, not *when the operation is
declared complete*.

- **Visibility ≠ completion.** A block is visible at step 6 of its chunk commit; the *operation*
  is complete only when all chunk futures + finalizers + history persistence settle
  (`EditSession.close()` / `flushQueue()`, APIC-057). The two are deliberately decoupled — that is
  the whole latency thesis — but the API contract (§4c completion point) is unchanged: staged
  counts are not success counts (W0.6 attack point 3).
- **Partial failure.** Multi-region edits are not globally atomic (§4b). If region B's commit fails
  after region A's committed and became visible, the operation returns an explicit partial-failure
  result with an undo record matching *exactly what committed* (§4d, C4). Viewer-near ordering does
  not change this: a visible-but-later-rolled-back block is undone via the normal history path, and
  the failure surfaces once. The transient-visible-then-undone window is the honest cost of sending
  blocks before the whole operation settles — documented (§5 compatibility inventory).
- **Exactly-once.** The current catch-and-log/`completeBlindly` behavior (W0.6 attack point 4:
  `FaweAPI.fixLighting`, lighting catch-and-print, `cancelEdit` swallow, `Operations.completeBlindly`)
  is replaced on the Folia path by a single failure sink per operation: the first failure from any
  region's pump/finalizer/permit-timeout is recorded, dedup'd, and delivered once at the completion
  point; subsequent failures on the same operation are attached, not re-thrown.
- **Cancellation / unload / disable / ownership migration** (§4d): pump drop-queue + permit release
  yields clean completion or explicit partial-failure with a valid undo record. A chunk whose owner
  migrated mid-drain is re-offered to the new owner (§3.3); if the region/world is gone, that
  chunk's commit fails into the operation's failure sink with its history correctly *not* recording
  it. **[NEEDS-RUNTIME]** NR-04: inject commit/tile/entity/light/packet/unload/cancel failures and
  assert `close`/`flushQueue` returns only after every accepted finalizer or throws one explicit
  partial-failure with matching history.

---

## 7. Migration of the ~20 `Fawe.isMainThread()` sites (C5)

60 raw hits across the tree; the core + adapter-26.1 Folia-relevant set is ~20 (the rest are other
adapters' Paper-only paths, unchanged). Each requalifies to a `FaweThreadContext` predicate with a
recorded rationale (C5 review artifact). Grouped by target predicate:

| Site | New predicate | Rationale (latency-relevant in **bold**) |
|---|---|---|
| `SingleThreadQueueExtent.submitUnchecked:258` | `ownsChunk(world,cx,cz)` | **The core inline fast path — was "am I main", becomes "do I own this chunk"; enables Tier-1 sub-tick commit.** |
| `NMSAdapter.setSectionAtomic:133` (per adapter) | `ownsChunk(world,cx,cz)` | **Skip send-lock + swap inline when owner (W0.5 §2c). Same generalization.** |
| `PaperweightFaweWorldNativeAccess.setBlockState:102`, `flush:280` | `ownsChunk(world,cx,cz)` | Live per-block write must be on the chunk owner; inline when already there. |
| `PaperweightPlatformAdapter:305,321` | `ownsChunk` (send path) | Packet build/send inline on owner (§3.4). |
| `QueueHandler.run:109` | **removed** (no global drain) | Replaced by per-region pumps; the global assertion always fails on Folia (hotspot #2). |
| `QueueHandler.sync/syncWhenFree:331,342,353,363` | `ownsChunk`/`ownsEntity`/global, derived | Context-carrying sync (§1.4). Inline when caller owns target. |
| `TaskManager.sync/taskNow/taskNowMain:195,311,324,341,366` | derived per §4c | Location-carrying variants; inline-on-owner where possible. |
| `TaskManager.runUnsafe:164,170` (`startUnsafe/endUnsafe`) | `isFaweWorker()` → no-op | DEGRADED (APIC-028): no `physicsFreeze`, no `AsyncCatcher` (§1b). Not a silent run — deterministic fail (W0.6 attack point 2). |
| `EditSessionBuilder:503` | `isFaweWorker()` | "run parallel if not main" → "if on a worker". |
| `PlatformCommandManager:677` | `isTickThread()` | Command dispatch context. |
| `AbstractPlayerActor:536,575` | `ownsEntity(player)` | Player-owned reads → entity scheduler. |
| `LocalSession:416` | `isTickThread()` | Sync-vs-async branch. |
| `AbstractChangeSet:415`, `LazyBaseEntity:27`, `SlowExtent:30`, `FaweCache:159` | `isFaweWorker()` / `isTickThread()` | History write-task / lazy entity / slow-extent / cache branch — none are ownership-sensitive. |

`Fawe.isMainThread()` itself (`Fawe.java:210`) stays as the Bukkit-backend implementation of
`isTickThread()`; on Folia it is never called directly (compile-error-driven: the method is
routed through `FaweThreadContext`). No call site keeps a raw identity comparison on the Folia
backend (C5).

---

## 8. Honest trade-offs — where latency-first loses

Latency-first is a set of *deliberate* trades against throughput, footprint, and complexity.
Naming where the throughput-first and simplicity-first rivals win on §8 dimensions:

| §8 dimension | Latency-first choice | Who wins it, and why |
|---|---|---|
| **Median/p95 completion time (huge edits)** | Viewer-near ordering + sliced sweep + immediate packets prioritize *first-visible*, not *last-committed*. Extra packet sends and re-sorting add per-drain overhead. | **Throughput rival wins.** Batched DELAY_PACKET_SENDING, unordered largest-sweep drains, and no double packets minimize total wall-clock for a `//set` of millions. Our own completion time is slightly worse. |
| **Throughput (blocks/s sustained)** | Small `sliceCap` (tuned for first-visibility) + inline budget guard cap per-tick work; two-tier routing adds a branch per edit. | **Throughput rival wins.** Bigger slices and single deferred packet pass push more blocks/tick. |
| **Peak heap / RSS, outstanding futures** | Eager per-region GET prefetch + priority queues + standing pumps raise peak outstanding snapshots and permits. | **Simplicity rival wins.** A lazy, on-demand, single-queue design holds fewer live snapshots. |
| **Per-region tick-time impact** | Immediate packet build+send inside the commit drain adds work to the region tick; inline Tier-1 adds prepare to the tick (budget-capped). | **Throughput rival wins** on worst-case single-region tick time; we cap it but do add packet work into the tick. |
| **Scheduler queue depth** | Standing pumps + priority reordering ≈ neutral (region-sweep already minimized task count). | ~Tie; region-sweep is common ground. |
| **Complexity / reviewability** | Two-tier routing, inline budget guard, viewer-near priority queues, standing pumps, immediate+deferred dual packets. Meaningfully more moving parts. | **Simplicity rival wins.** More code on the concurrency-critical path = more surface for the named risk (silent corruption / deadlock). This is the real cost. |
| **Time-to-visibility (interactive)** | **Tier-1 inline: sub-tick.** No rival with a pure detached-prepare→schedule model can go below the ~1-tick floor for owner-triggered brushes. | **Latency-first wins decisively** — the whole point. |
| **Time-to-first-visible-block (bulk, near player)** | **≈1 tick** via viewer-near ordering + immediate packets, independent of edit size. | **Latency-first wins.** Throughput rival's player sees nothing until the batched relight+packet pass. |
| **Shutdown drain time** | Standing pumps must flush on disable (bounded). Neutral. | ~Tie. |

**Where I would concede:** if the certification harness (§8) shows the two-tier inline path
materially widens per-region tick-time variance or the double-packet path measurably hurts p99
completion on large edits, the honest move is to make Tier-1 inline and immediate-packet
*opt-in* (config-gated, default on for brushes/wands, off for `//` bulk commands) and let bulk
edits use the throughput rival's batched path. The latency wins that survive that concession —
sub-tick interactive edits and viewer-near first-visibility — are the ones worth defending;
everything else is tuning.

**Biggest risk to this proposal:** the added concurrency complexity on the commit-critical path
is exactly the surface where the named risk (silent chunk corruption, deadlock) lives. Every
latency lever here is guarded to fail *toward* the safe/slow path (inline budget → offload,
priority → still bounded, immediate packet → configurable fallback), but a judge weighting §6
assurance and §1b compliance should read the two-tier model as the place that needs the double-
reviewer and the failure-injection gate the hardest.

---

## Appendix: claims requiring runtime verification

- **[NR-A]** `isOwnedByCurrentRegion(World,int,int)` per-call cost on the hot commit path (fast-path gate).
- **[NR-B]** Real average wake latency of the pump under uniform arrival (~½ tick expected vs W0.2's 51 ms worst-case-looking).
- **[NR-C]** `sliceCap` derived from a per-tick commit-time budget, not a fixed chunk count.
- **[NR-D]** Tracked-viewer read on the region thread for viewer-near ordering (W0.2 had `tracked_viewer=false`).
- **[NR-E]** Cross-region `connection.send` client-visible delivery (W0.2 `client_ack=pending`); else entity-scheduler fan-out.
- **[NR-F]** Inline Tier-1 prepare budget vs per-region tick-time variance (the concession trigger).
- **[NR-G]** Immediate+deferred dual-packet effect on p99 completion and bandwidth on lit bulk edits.
- **[NR-H]** NR-04 terminal completion failure injection (exactly-once, matching history).
- **[NR-I]** `getChunkAtAsync` continuation thread (W0.2 Q4, single weak observation) — re-dispatch regardless.
