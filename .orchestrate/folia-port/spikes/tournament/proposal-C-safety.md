# Proposal C — Safety & Simplicity First

**Angle:** the design a maintainer can *prove* correct and an upstream can merge. One choke
point, one capability type, deterministic failure. The compiler — not convention — is what
keeps a live-state touch off the wrong thread.

**Thesis in one line:** make "touch live region state without owning it" a *compile error*,
funnel every region hop through a single instrumented class, and let every failure resolve
exactly once through one typed sink. Everything else is a consequence of those three moves.

All claims below are anchored to `w02-pipeline-results.md` measurements, the frozen contracts
(architecture §3 C1–C6), and spec §1b/§4b/§4c/§4d/§8. Where I extrapolate past the N=16
spike data I say so.

---

## 0. The three load-bearing bets

1. **`RegionTicket` capability token.** The only object that authorizes a live-state adapter
   call. It cannot be constructed by callers; only `FoliaRegionDispatcher` mints one, and only
   *inside* a region task. Every `commit*` / finalizer / packet / light adapter method gains a
   `RegionTicket` parameter. Off-thread code has no ticket → cannot compile the call. Illegal
   states become unrepresentable, and the mandatory non-owner-callback re-dispatch rule (W0.2
   §4) is enforced *by the type system*, not a code-review checklist.

2. **One choke point.** `FoliaRegionDispatcher` is the sole class that talks to any Folia
   scheduler (architecture §2/C1). Every region/entity/global hop, every ticket mint, every
   backpressure decision, every instrumentation counter lives there. Review = read one file;
   instrument = one file; audit "does live state ever get touched off-owner?" = grep for one
   parameter type.

3. **One completion sink, resolved exactly once.** A sealed `CommitOutcome` per commit, folded
   into a single `OperationCompletion` that a `CompletableFuture` completes exactly once. No
   `completeBlindly`, no catch-and-log, no double-wrap (kills w06 attack point 4). §4d
   exactly-once falls out of the type, not out of discipline.

These are deliberately *few* moving parts. The rival designs will beat me on peak throughput
(§8 below); I win on the metric that spec §4b ranks first — provable correctness of accepted
operations — and on merge/review cost.

---

## 1. Java SPI shapes

### 1.1 `FaweThreadContext` — the core seam (C5, replaces `Fawe.isMainThread()`)

Value-typed, immutable, resolved from the running thread. No behavior, only predicates — so it
is trivially testable and cannot itself cause an owner hop.

```java
package com.fastasyncworldedit.core.util.task;

/**
 * Thread-role classification. Bukkit backend collapses tick/owner to the main thread;
 * Folia backend answers per region/entity/global scheduler ownership.
 * Pure predicate object — never blocks, never hops, never touches live state.
 */
public sealed interface FaweThreadContext permits BukkitThreadContext, FoliaThreadContext {

    static FaweThreadContext current() { return ContextResolver.resolve(); } // single resolver

    boolean isTickThread();                         // any server tick thread (region/entity/global)
    boolean ownsChunk(World world, int chunkX, int chunkZ);
    boolean ownsEntity(Entity entity);
    boolean isGlobalContext();                       // Folia global-region thread; Paper main
    boolean isFaweWorker();                          // a FAWE pool thread (A/W)

    /** Fail-fast guard used at every seam that requires ownership. */
    default void requireOwns(World world, int cx, int cz) {
        if (!ownsChunk(world, cx, cz)) {
            throw new WrongOwnerException(this, world, cx, cz);
        }
    }
}
```

- **Bukkit backend:** `isTickThread() == ownsChunk(..) == isGlobalContext() == old isMainThread()`;
  `isFaweWorker()` = membership in the FAWE pool. Zero behavior change on Paper (architecture §2).
- **Folia backend:** delegates to `TickThread`/region ownership. Never disables or bypasses
  `TickThread` (spec §1b hard bar).
- `sealed` + two permits = the reviewer sees the *entire* set of implementations. No third
  backend can be added without touching the sealed clause (a reviewed choke).

### 1.2 `RegionTicket` — the capability token (the central bet)

```java
package com.fastasyncworldedit.core.util.task;

/**
 * Proof — valid only for the dynamic extent of the enclosing region task — that the current
 * thread owns the named region and may touch its live Minecraft state.
 *
 * Cannot be constructed outside this package. Only FoliaRegionDispatcher (and the Bukkit
 * main-thread executor) mint one, and only inside a scheduled owner task. Passing a ticket to
 * an adapter method is the ONLY way to reach live state on the Folia backend.
 */
public sealed interface RegionTicket permits FoliaRegionTicket, PaperMainTicket {

    World world();
    boolean owns(int chunkX, int chunkZ);

    /** Fail-fast: throws if this ticket does not own (cx,cz) OR is used off its minting thread. */
    void assertOwns(int chunkX, int chunkZ);

    /** True only while the minting task is still on-stack (see §1.3 escape defense). */
    boolean isLive();
}
```

Why this is the killer move for a safety-first angle:

- **Illegal call = compile error.** `PaperweightGetBlocks.commit(...)` becomes
  `commit(RegionTicket t, PreparedChunk c)`. A FAWE worker that tries to commit directly has no
  `RegionTicket` in scope and *cannot write the call*. The W0.2 non-owner relight callback
  (`Paper_Common_Worker_#0`, `owner=false`) physically cannot touch live state — it holds no
  ticket. To finalize it must re-enter through the dispatcher, which is exactly the mandatory
  re-dispatch rule (§4).
- **Review is a grep.** `git grep 'RegionTicket'` enumerates *every* live-state entry point in
  the codebase — the complete C1 danger set, machine-checkable, no human inventory drift.
- **Instrumentation is one place.** Mint/retire is inside the dispatcher; counting live-state
  touches, timing them, and asserting ownership all happen at ticket mint (§8 signals for free).

Runtime defense against ticket escape (a ticket smuggled out of its task and used later): every
ticket method re-checks `TickThread.isTickThreadFor(world, cx, cz)` and `isLive()` and throws
`WrongOwnerException` otherwise. Compile-time stops the *common* mistake (no ticket → no call);
runtime fail-fast stops the *exotic* one (escaped ticket). This is honest defense-in-depth, not
a claim of total compile-time proof. Tickets are lightweight per-task objects, never cached,
never returned from a finalizer.

### 1.3 `FoliaRegionDispatcher` — the single choke point (C1)

```java
package com.fastasyncworldedit.bukkit.folia;

/** The ONE class that talks to Folia schedulers. Every region hop, ticket mint,
 *  and backpressure decision lives here (architecture §2, C1). */
public interface FoliaRegionDispatcher {

    /** Run on the region owning (world,cx,cz); task receives a fresh RegionTicket. */
    CompletableFuture<Void>  onRegion(World world, int cx, int cz, RegionTask task);
    <T> CompletableFuture<T> onRegion(World world, int cx, int cz, RegionCall<T> call);

    /** Run on the entity's owning region (entity finalizers, block-bag flush). */
    CompletableFuture<Void>  onEntity(Entity entity, RegionTask task);

    /** Global-region thread (config, cross-cutting lifecycle only). */
    CompletableFuture<Void>  onGlobal(GlobalTask task);   // no RegionTicket: no chunk ownership

    @FunctionalInterface interface RegionTask { void run(RegionTicket ticket); }
    @FunctionalInterface interface RegionCall<T> { T call(RegionTicket ticket); }
    @FunctionalInterface interface GlobalTask { void run(); }
}
```

Deliberate design decisions:

- **Callbacks are typed, not `Runnable`.** A `RegionTask` receiving a `RegionTicket` cannot be a
  generic opaque lambda that hides a chunk/world/player (w06 attack point 1). To do live work
  you must accept the ticket and name your chunk — the hazard is surfaced at the signature.
- **`onGlobal` deliberately grants no ticket.** Global context owns no chunk; nothing region-live
  is reachable from it. This makes the "global callback touched region state" bug impossible by
  omission, not by review.
- **No raw scheduler leaks.** `RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler` /
  `AsyncScheduler` are referenced *only* inside the implementation. Grep for
  `RegionScheduler` outside this package = a review failure (mechanically enforceable in CI).
- Backed by `RegionScheduler.execute(plugin, world, cx, cz, task)` (A5 confirmed by W0.2:
  `owner_mismatches=0` across 3×16 tournament commits + safety + lighting legs).

### 1.4 `FoliaBackpressure` — bounded per-region in-flight commits (§1b, C6)

```java
package com.fastasyncworldedit.bukkit.folia;

/** Bounded in-flight commit accounting per region key. No caller-runs for owner-bound work. */
public interface FoliaBackpressure {

    /**
     * Reserve a commit slot for a region. The returned future completes on a FAWE worker when a
     * slot frees. NEVER executes owner-bound work on the caller (no CallerRunsPolicy). When the
     * region's slot budget AND its bounded wait-queue are exhausted, the future either:
     *   - completes later when a slot frees (worker awaits — the ONLY allowed wait, worker→owner,
     *     bounded timeout, cancellable), or
     *   - fails fast with RegionSaturatedException when a hard cap is hit (deterministic).
     */
    CompletableFuture<CommitPermit> acquire(RegionKey region, Duration timeout);

    interface CommitPermit extends AutoCloseable {
        RegionKey region();
        @Override void close();          // release slot; called in the commit task's finally
    }

    /** §8 signal surface (C6): per-region depth, not a fabricated global TPS. */
    int inFlight(RegionKey region);
    int queued(RegionKey region);
}
```

- **No caller-runs, ever, for owner-bound work.** Spec §1b forbids it explicitly and it is a
  correctness trap: `CallerRunsPolicy` would run a *region-owned* commit on the *worker* thread,
  a direct C1/§1b violation. My angle refuses the standard JDK backpressure idiom precisely here.
  Backpressure is a bounded per-region semaphore; the worker awaits its own permit
  (worker → owner, acyclic, bounded, cancellable — the only wait spec §1b allows).
- Signals are per-`RegionKey` (region scheduler in-flight + bounded queue depth), replacing the
  global TPS gate (`AsyncPreloader` TPS>18, `QueueHandler.getAllocate()` — C6).

### 1.5 Context-carrying sync (C3, replaces location-free `QueueHandler.sync`)

The location-free legacy surface (w06: `sync` ×3, `async` ×3, `syncWhenFree` ×4; TaskManager
APIC-020/022/027) is the C3 problem. Safety-first disposition:

```java
public interface QueueHandler {
    // PRESERVED public surface (binary/source compat, w06 Y/Y) with deterministic derivation:
    <T> T sync(Supplier<T> task);            // derives target: current tick context if on one,
    void  sync(Runnable task);               // else GLOBAL region (deterministic, documented).

    // NEW internal, context-carrying — the migration target:
    <T> CompletableFuture<T> syncOn(RegionKey key, RegionCall<T> call);
    void                     syncOn(RegionKey key, RegionTask task);
}
```

- Every *public* location-free entry point is **PRESERVED with deterministic context
  derivation** (never routed arbitrarily, never blocks a tick thread — §4c). Derivation rule:
  if the caller is already on a tick thread and the callback is current-owner-safe, run inline;
  otherwise target the **global region** deterministically. A tick-thread caller whose callback
  needs a *different* owner fails fast before side effects (§4c: "wrong tick fails").
- Every *internal* caller migrates to `syncOn(RegionKey, …)` by **compile-error-driven
  migration**: the location-free internal overloads are removed on the Folia path, so each of
  the ~140 `TaskManager.taskManager().{sync,task}` call sites (w06 evidence) must state its
  `RegionKey` or be requalified to global. The requalification record (call site → key/rationale)
  is the C3 review artifact.
- `runUnsafe` → **DEGRADED**, fails fast with `UnsupportedOperationException` + caption before
  the callback (w06 attack point 2: a no-op is *not* acceptable — it silently drops the promised
  suppression semantics). This is the honest disposition.

---

## 2. GET snapshot lifecycle

```
worker wants GET(world,cx,cz)
      │  cache hit (per-chunk, valid)? ── yes ──► return detached ChunkSnapshot (no hop)
      │  no
      ▼
dispatcher.onRegion(world,cx,cz, ticket -> capture(ticket))   ── region thread, ticket in hand
      │   capture(): copy palettes, biomes, tile NBT, entity NBT, block+sky light nibbles
      │              INCLUDING the lazy queueSectionData light-create (w03 §3.3 L2) — a *write*,
      │              so it MUST run here on the owner, never on a worker.
      ▼
ChunkSnapshot (immutable record, zero NMS handles — C2) ──► cache[key] ──► worker consumes
```

```java
/** Fully detached. No reference to any live NMS collection (C2). */
public record ChunkSnapshot(
        long key, int minSectionY,
        SectionData[] sections,      // copied palette+data, not live LevelChunkSection
        BiomeData biomes,
        List<CompoundTag> tileNbt,
        List<CompoundTag> entityNbt,
        NibbleArray[] blockLight,
        NibbleArray[] skyLight) {}
```

- **Capture is region-owned, consumption is detached.** The snapshot is minted under a ticket
  (`assertOwns` before every live read), then handed to workers as immutable data. W0.2 Leg 1
  proved this exact shape safe: section built off-thread, `live_refs_on_worker=false`, region
  ticked 3× during prepare.
- **Cache + invalidation:** keyed `(world,cx,cz)`; invalidated when that chunk commits (the
  commit task, holding the ticket, evicts the entry as its last step). Simple, single-writer per
  key, no cross-region cache coherence problem — because eviction happens on the owner.
- **`lazyCopy`/`WorldCopyClipboard` fix** (w06 attack point 6): these must yield a
  `ChunkSnapshot`, never a live extent. Enforced because the only way to read live is a ticketed
  region task that returns a detached record.

---

## 3. SET commit flow, strategy, behavior at large N

### 3.1 Flow

```
WORKER (A/W)                                    OWNER REGION THREAD (ticket minted here)
──────────                                      ────────────────────────────────────────
build PreparedChunk:                            dispatcher.onRegion(world,cx,cz, ticket -> {
  LevelChunkSection[] replacements                 backpressure permit already held
  ordered Finalizer list                           adapter.commit(ticket, prepared)   // swap
  (detached, no live refs — C2)                     runFinalizers(ticket, prepared)    // §4
   │                                                 evict GET cache[key]
   ▼                                                 permit.close()
backpressure.acquire(regionKey) ── permit ──►      }).whenComplete(recordOutcome)   // §6 sink
```

```java
public record PreparedChunk(long key,
                            LevelChunkSection[] replacements,
                            List<Finalizer> finalizers) {}   // fully detached
```

`adapter.commit(RegionTicket, PreparedChunk)` swaps sections (today's `setSectionAtomic` CAS —
but now *provably* on the owner, so the reflective CAS + `ChunkSendLock` are unnecessary and
dropped on the Folia path, per w05 §2c: region-thread serialization subsumes the send lock).

### 3.2 Strategy choice — region-sweep, sliced — justified against W0.2

W0.2 §3 ranking (16 chunks, 4 regions, one run):

| Rank | Strategy | commit_tasks | max_commit_task_us | visibility_us |
|---|---|---|---|---|
| 1 | region-sweep | 4 | **364** | 51321 |
| 2 | batched-neighbor | 8 | 705 | 51322 |
| 3 | per-chunk | 16 | 551 | 54964 |

Visibility is scheduler-delay dominated (~51 ms ≈ 1 region tick) in all three; actual commit
work is sub-millisecond. So visibility does **not** discriminate at N=16 — task *pressure* does.

**Choice: region-sweep grouping, with a mandatory per-task chunk cap (sliced sweeps).**

- Region-sweep wins the two metrics that matter for a safety angle: fewest tasks (4 vs 16 →
  least scheduler pressure, least instrumentation surface) and lowest max task cost (364 µs).
- **The cap is non-negotiable for liveness.** An unbounded sweep committing hundreds of
  chunks-per-region in one task can exceed a region tick and starve that region's own gameplay —
  a §1b liveness hazard. W0.2 §3 flags exactly this ("a sweep committing hundreds of chunks in
  one task could exceed a tick and must be sliced"). So a sweep is sliced into tasks each
  bounded to `C` chunks, `C` chosen so a task stays well under one tick (conservative default:
  derive `C` from the measured ~364 µs / 4-chunk → target < 2 ms/task ⇒ `C ≈ 16`, pending W0.8
  calibration). The rival that runs an *unbounded* sweep will beat me on task count but risks a
  region-tick overrun; I trade a little throughput for a hard liveness bound.
- **Grouping is derived at execution time, never precomputed.** W0.2 §3: the 4 clusters ↔ 4
  region IDs mapping was coincidental; Folia regions split/merge dynamically. The dispatcher
  re-derives the `RegionKey → chunks` grouping when the commit fires, from live ownership. A
  precomputed grouping is invalid by design and I reject it.

### 3.3 Behavior at large N (honest extrapolation — not measured)

- **Task count:** per-chunk = O(chunks); region-sweep-sliced = O(chunks / C) bounded per region.
  A 100k-chunk edit is 100k tasks per-chunk vs ~6k sliced sweep tasks. Fewer tasks = less
  scheduler contention and a smaller instrumentation footprint.
- **Backpressure interaction:** with a per-region in-flight bound `B`, workers producing faster
  than a region drains will *await permits* (bounded, worker→owner). This caps outstanding
  futures/tickets/chunks (§8 resource budget) at `B × regions` — a provable memory bound. A
  rival with unbounded pipelining wins p50 completion time on huge edits but has no
  outstanding-work bound; I take the bound.
- **The N=16 data does not give a budget or a slicing threshold** — that is W0.8/W0.10a. I set a
  conservative default `C` and a hard per-task tick-budget guard; the number is tuned later.

---

## 4. Finalizer ordering + mandatory non-owner-callback re-dispatch

### 4.1 Fixed total order per chunk commit (deterministic, single-threaded on the owner)

All steps run under the same `RegionTicket`, on the owning region thread, in this exact order:

1. **Section swap** — `adapter.commit(ticket, prepared)`.
2. **Chunk field writes** — `setLightCorrect`, `mustNotSave`, heightmaps (w05 Part 3:
   `GetBlocks:751-752`).
3. **Light injection (L1, region-local)** — `fillLightNibble` / `setLightingToGet` /
   `setSkyLightingToGet` / `removeSectionLighting`. All key on `SectionPos.of(getChunk().getPos())`
   — committed chunk only, no neighbor reach (w03 §3.1).
4. **Tile entities** — `getBlockEntity` / `loadWithComponents` (w05: `GetBlocks:726-737`).
5. **Entities** — `addFreshEntity` / removal (w05: `GetBlocks:688-704`, entity-region).
6. **Beacon / block-entity removal** — including `removeBeacon` (w05 Part 3: today runs on the
   *worker* inside `internalCall` — a live mutation off-owner; moved onto the ticketed commit).
7. **Neighbor / physics events** (only if `SideEffect.EVENTS`) — `updateNeighborsAt` etc. A
   neighbor owned by an **adjacent region** is re-dispatched per-neighbor via
   `dispatcher.onRegion(neighborWorld,ncx,ncz, …)` (w05 Q2 open hazard; §5 documents the
   cross-region ordering weakening if the runtime test forces it).
8. **Packet resend** — build `ClientboundLevelChunkWithLightPacket` + enumerate viewers on this
   region (A1/A3 confirmed, w02 Leg 3). Cross-region viewer send: direct if A2 holds
   (`cross_owner=true` did not throw), else per-viewer entity-scheduler hop (w05 2d fallback).
9. **Relight submission** — detached NMSRelighter (L3, w03), materialized via step 3 on the
   *next* commit. The server batch starlight engine is **not** invoked on Folia in wave 0.
10. **GET cache eviction** for `key`, then **complete the chunk outcome** (§6).

### 4.2 The mandatory rule (W0.2 §4, made structural)

> **Every completion callback that arrives on a non-owner thread MUST re-dispatch to the owning
> region via `FoliaRegionDispatcher` before touching any live state.**

W0.2 proved this is not optional: `RELIGHT_CHUNK_CALLBACK` and `RELIGHT_COMPLETE_CALLBACK`
landed on `Paper_Common_Worker_#0` with `owner=false`. `getChunkAtAsync` continuations are
similarly non-owner (Q4). My design makes the rule **impossible to violate**:

- A callback thread holds **no `RegionTicket`**. Every live-state adapter method *requires* one.
  Therefore the callback *cannot compile* a live-state call; its only path forward is
  `dispatcher.onRegion(...)`, which mints a fresh ticket on the correct owner. The re-dispatch is
  not a rule the author must remember — it is the only thing that type-checks.
- Ticket adds in `getChunkAtAsync` continuations (w03 Q4) likewise route through the dispatcher:
  cheap, and it removes the "was this continuation on the owner?" assumption entirely.

---

## 5. Bounded per-region backpressure (no caller-runs)

- **Bound:** a per-`RegionKey` semaphore of `B` in-flight commit slots + a bounded FIFO wait
  queue of depth `Q`. `acquire` returns a future that completes on a worker when a slot frees.
- **Wait discipline:** only FAWE workers (A/W) ever await, and only worker → owner (the single
  acyclic direction spec §1b permits). Bounded `timeout`, cancellable, thread-dump-assertable.
  Owner/tick threads never call `acquire` in a blocking way — a tick-thread commit path fails
  fast before it could wait (w06 attack point 7: tick-thread lifecycle calls are a deadlock
  boundary).
- **No caller-runs for owner-bound work.** Explicit and enforced: the backpressure executor uses
  neither `CallerRunsPolicy` nor any inline fallback that would run a region-owned task on the
  producer thread. Saturation → bounded wait, then `RegionSaturatedException` at a hard cap
  (deterministic, surfaced through the §6 sink). This is the one place I most visibly diverge
  from a throughput-max rival: they can let the producer help drain; I forbid it because on
  Folia "the producer" is a worker and the work is region-owned.
- **Signals (C6):** `inFlight(key)` / `queued(key)` replace the global TPS gate. `AsyncPreloader`
  and `getAllocate()` budgeting key on the target region's depth, not `MinecraftServer.currentTick`.

---

## 6. §4d failure semantics — exactly-once through one typed sink

```java
sealed interface CommitOutcome permits Committed, CommitFailed {}
record Committed(long chunkKey)                         implements CommitOutcome {}
record CommitFailed(long chunkKey, Throwable cause)     implements CommitOutcome {}

/** One sink per operation. Folds per-chunk outcomes; completes the operation future once. */
final class OperationCompletion {
    private final CompletableFuture<OperationResult> done = new CompletableFuture<>();
    private final AtomicInteger pending;
    private final Queue<CommitFailed> failures = new ConcurrentLinkedQueue<>();

    void record(CommitOutcome o) {
        if (o instanceof CommitFailed f) failures.add(f);
        if (decrementAndReachedZero()) {
            done.complete(failures.isEmpty()
                ? OperationResult.success(committedKeys())
                : OperationResult.partialFailure(committedKeys(), failures)); // matching undo record
        }
    }
    CompletableFuture<OperationResult> future() { return done; }  // completed exactly once
}
```

- **Exactly once (§4d):** `CompletableFuture.complete` is idempotent-guarded by the pending
  counter reaching zero exactly once. No `completeBlindly`, no catch-and-log, no
  `FaweAPI.fixLighting`-style swallow (w06 attack point 4). Every async failure — commit, tile,
  entity, light, packet, unload, cancellation — becomes a `CommitFailed` folded into the sink and
  delivered once to the actor/API.
- **History records exactly what committed** (§4d): the undo record is built from
  `committedKeys()`, the set of `Committed` outcomes — never from the *attempted* set. A
  partial failure returns `OperationResult.partialFailure` with a valid matching undo record
  (§4b: multi-region ops are not globally atomic; visibility/partial-failure governed by §4d).
- **Cancellation / unload / disable:** each in-flight commit checks its ticket's `isLive()` and
  the operation's cancel flag at step boundaries; a cancelled/unloaded chunk yields `CommitFailed`
  (clean partial) rather than a torn commit. Never a silent drop (spec §5: no silent
  semi-functioning).
- **Staged ≠ committed** (w06 attack point 3): EditSession boolean/int returns are detached
  preparation. Terminal success is the `OperationCompletion.future()` resolving — surfaced at
  `close()`/`flushQueue()` (APIC-057).

---

## 7. Migration of the ~20 `Fawe.isMainThread()` sites (C5)

Compile-error-driven: `Fawe.isMainThread()` is deprecated and, on the Folia path, has no
meaning — it is replaced call-site-by-call-site with a `FaweThreadContext` predicate. The
requalification record (site → predicate → rationale) is the C5 review artifact. Mapping rule:

| Original intent at the call site | New predicate | Rationale |
|---|---|---|
| "am I on the thread that may touch *this chunk's* live state?" | `ctx.ownsChunk(world,cx,cz)` | The real question is ownership of a specific chunk, not global identity. Most GetBlocks/NativeAccess sites. |
| "am I on a server tick thread at all?" (skip a hop, take a fast path) | `ctx.isTickThread()` | e.g. `setSectionAtomic` fast-path (`NMSAdapter:133`) — w05 already frames this as "am I the owner?" generalizing "am I main?". |
| "am I on a FAWE pool thread?" (choose sync vs async branch) | `ctx.isFaweWorker()` | Scheduling/branch decisions in QueueHandler/TaskManager. |
| "am I on the single global thread?" (global registry/lifecycle) | `ctx.isGlobalContext()` | Bootstrap/config sites; Paper main ≡ Folia global. |
| Guard that used identity only to *assert* correctness | `ctx.requireOwns(...)` or **removed** | Replaced by a ticket-required signature upstream of it → the identity check is now dead and deleted. |

- No call site keeps a raw identity comparison on the Folia backend (C5).
- The exact 20-site list comes from `recon-queue-threading.md`; each maps to one row above. The
  common case is `ownsChunk`, because nearly every historical `isMainThread()` was a proxy for
  "I may mutate this chunk now" — which on Folia is a per-chunk ownership question, not a global
  one. Several assertion-only sites *vanish* once the callee takes a `RegionTicket` (the ticket's
  own `assertOwns` subsumes them) — a net simplification, fewer lines than upstream.

---

## 8. Honest trade-offs — where safety-first costs performance, which §8 metrics rivals win

Spec §8 defines two comparisons: (1) non-Folia regression vs base, (2) Folia backend vs ported
Paper backend. My Paper path is untouched except at seams, so I expect to *pass* comparison (1)
cleanly (the `RegionTicket` param is a nulled `PaperMainTicket`, negligible). Comparison (2) is
where I concede ground:

| §8 metric | Where a throughput-max rival beats me | Why I pay it |
|---|---|---|
| **median / p95 completion time** (large single-region edit) | Rival with unbounded pipelining and unsliced region-sweep drains a region in one big task; I slice at `C` chunks/task, adding scheduler round-trips. | A sliced task provably stays under a region tick (liveness §1b). I refuse to risk a region-tick overrun for burst speed. |
| **throughput** (chunks/s under backpressure) | Rival can use caller-runs / producer-assist to keep draining when a region saturates. | Caller-runs on owner-bound work runs region state on a worker — a §1b/C1 violation. I take a bounded wait instead. |
| **per-region tick-time impact** (small edits) | Roughly a wash; my extra ticket mint + `assertOwns` is ~nanoseconds. | No real concession here; the ticket cost is noise vs the ~51 ms scheduler floor. |
| **relight-heavy workloads** (`//set` + full relight) | A rival that lands the starlight salvage (w03 L5) uses the server's fast engine; I DEGRADE to NMSRelighter (w03 L3), which is single-threaded per EditSession (`synchronized`) and slower/lower edge-quality. | Starlight-on-Folia cross-region border correctness is *unproven* (w03 Q3 OPEN, no Paper oracle). I will not ship an unverified cross-region light race to win a benchmark. If W0.8 shows NMSRelighter blows the frozen budget, *that* reopens the salvage — with its own certification gate — not before. |
| **outstanding futures / peak heap** (§8 resource) | No rival beats me here — my per-region bound `B` caps outstanding work at `B × regions`. | This is the metric my angle *wins*: a provable memory bound where an unbounded pipeline has none. |
| **latency floor per commit** | Every commit and every non-owner callback re-dispatch costs ~1 region tick (~51 ms, W0.2). A rival that batches more aggressively amortizes this better. | The re-dispatch is the price of the mandatory rule (§4). I make it structural rather than skip it. |

**The honest summary:** on raw §8 comparison-(2) speed metrics — p50/p95 completion and
chunks/s throughput under load — a rival willing to pipeline unboundedly, slice less, and salvage
starlight will post better numbers on large and relight-heavy edits. I win **correctness of
accepted operations** (spec §4b priority #1: exactly-once failure, no torn commits, provable
ownership), **bounded resources** (§8 outstanding-work / heap), and the two non-quantified
judging axes that this angle exists for: **merge cost** (smallest marked diff — one dispatcher
class, one ticket type, seam-only Paper edits) and **reviewability** (the C1 danger set is a
grep, the re-dispatch rule is a compiler error, failures resolve through one sink). Where spec
§4b says "correctness beats throughput whenever they conflict," this design is built to make that
trade-off structurally, not case by case.

**Smallest-diff claim, concretely:** new code is confined to
`com.fastasyncworldedit.bukkit.folia.*` (dispatcher, backpressure, task manager, queue handler)
+ `FaweThreadContext`/`RegionTicket` in core. Paper-path touch-points are limited to (a) adding
the `RegionTicket` parameter to the ~13 adapter live-state methods inventoried in w03 §7 / w05
Part 3, and (b) the ~20 `isMainThread()` requalifications (§7). Every touch is marked
`// Folia port:` and grep-able (architecture §4). No core rewrite for Paper users (spec §3).
