# FAWE Folia Port — Architecture & Contracts (v3.1 — CO-SIGNED, unconditional: r3 all-PASS `codex-arch-cosign-r3.md`; F1 module-graph compile condition satisfied 2026-07-17)

Binding for all workers. Where this file and `spec.md` (v3, FROZEN) disagree, spec.md wins.
History: v2 synthesized the wave-0 design tournament (proposals A/B/C, judge ruling in
`spikes/tournament/judgment.md`); the adversarial co-signer REJECTED v2
(`codex-arch-cosign-r1.md`, 10 BLOCKING + 1 MAJOR); v3 applied every r1 amendment; round 2
(`codex-arch-cosign-r2.md`) WITHHELD with 4 blocking residuals + 1 freeze-record correction.
This **v3.1** applies all five (terminal identity = plan sequence; `NOT_ACCEPTED` completion
path; drain terminalization; frozen G4 diagnostic snapshot producer; F9 record CLOSED). The
`[W0-FREEZE]` status of the SPI below remains **conditional on the F1 compile proof of the
declared module graph**; per F10, harness implementation and numeric budget values are wave-1
dispatch gates, not architecture co-signature gates.

The design remains a three-layer synthesis:

- **Safety skeleton = Proposal C.** `RegionTicket` capability, a single `FoliaRegionDispatcher`
  choke point with ticket-typed callbacks, one exactly-once operation completion sink, a fixed
  deterministic phase order. Corrected per r1: capabilities are **core-owned and
  single-callback-scoped** (F1/F3), and the completion sink carries **keyed terminal records
  with applied receipts** (F5).
- **Throughput engine = Proposal B.** Dynamic per-region commit lanes, adaptive sliced
  region-sweep, snapshot profiles with stamped base validation (F6), and B's **full admission
  model restored** (Demand/stage/cancellation — F4) with deficit-round-robin interleaving.
- **Latency grafts = Proposal A**, config-gated, `[NEEDS-RUNTIME]`-tagged, with F7's hard
  eligibility conditions on the owner-inline path and F8's terminal ordering on packets/relight.

## 1. Design overview

Same jar, two backends, selected once at boot:

- **Paper/Spigot backend** — the existing code paths, behavior-equivalent within frozen
  budgets (spec §3). Untouched except for seam introduction (marked touch-points). Every new
  ownership parameter collapses to the main-thread identity, so Paper behavior is unchanged.
- **Folia backend** — new code in a dedicated Gradle module (§2), active only when Folia is
  detected. Never classloaded elsewhere. Fail-closed outside the certified matrix (spec §2b).

The Folia execution model inverts FAWE's current trick. Today: FAWE threads mutate live
sections directly, with AsyncCatcher globally disabled. On Folia (spec §1b) that is forbidden.
Instead:

```
FAWE worker threads (unchanged pools)          Owning region thread (per chunk, per callback)
──────────────────────────────────────         ──────────────────────────────────────────────
prepare: palettes, section objects,     ──►    commit: validate base stamps, swap sections,
masks, transforms, history diffs               run owner phases — each dispatcher callback
(detached data only, no live state)            under its own fresh RegionTicket, sub-ms
```

Proven at spike quality by W0.2 (`PIPELINE_SAFETY_OK`, `live_refs_on_worker=false`,
`owner_mismatches=0` across 3×16 tournament commits, save+restart readback exact).

**How the layers fit together on the commit path:**

1. **GET (region-owned capture, detached consumption).** For each needed chunk the dispatcher
   runs an owner callback (fresh `RegionTicket`) that captures an immutable snapshot (profile:
   BLOCKS / BLOCKS_LIGHT / FULL) **plus its validation stamps** (world/load epoch, FAWE mutation
   sequence, base-component fingerprints — F6, §3.6). Light-nibble reads — which hide a lazy
   `queueSectionData` *write* (W0.3 L2) — happen here on the owner, never on a worker. Snapshots
   are versioned per chunk, cached, single-flighted (§3.7). Workers consume detached data only (C2).

2. **Prepare (FAWE workers).** Workers build one detached `PreparedChunkCommit` per chunk:
   complete `LevelChunkSection` replacements, ordered tile/entity/POI/light plans, before/after
   history delta, packet intent, byte/work estimates, cancellation info, and the base stamps.
   No live NMS reference escapes to a worker (C2). Each plan carries a **plan sequence**
   allocated by the same-chunk sequencer, and its chunk is **registered with the operation's
   completion sink before its admission attempt** (F5/r2). Before a plan is *accepted*: its
   undo payload is written to history as `PREPARED`, a global prepared-bytes reservation
   exists, and `FoliaBackpressure.acquire` grants a per-region permit carrying the plan's
   `Demand` (F4). Rejection before acceptance terminates the registration exactly once with
   `NOT_ACCEPTED` (§3.6).

3. **Commit (owning region lanes).** The commit broker keeps one **lane (mailbox) per observed
   region** (internal `RegionKey` metadata — never in a core/public signature, F2). A lane drains
   as a **sliced region-sweep**: each region tick it commits up to an adaptive cap of ready
   chunks, then re-arms if backlog remains. Each chunk advances through the **fixed phase order**
   (§3.6); every owner phase is one dispatcher callback under one fresh ticket; cross-owner
   phases yield with detached receipts and resume with fresh capabilities (F3). Grouping is
   re-derived from live ownership at drain time; stale permits are transferred to the actual
   region's accounting before mutation (F4); base stamps are validated before the first mutation
   (F6). Region IDs are hints, never proof (spec §1b, W0.2 §3).

4. **Complete (exactly once).** Every registered chunk ends in exactly one keyed terminal
   record (`TerminalStatus` + `AppliedReceipt`). The operation completes only when admission is
   closed, every registered chunk is terminal, every required finalizer settled, and every
   APPLIED record reached its persistence boundary (F5; spec §4d, C4).

**Parallelism win** (spec §4b): lanes for different regions drain genuinely in parallel on their
region threads — Paper serializes all finalizers on one main thread; Folia need not. Multi-region
ops are NOT globally atomic unless certified (spec §4b/§4d).

**Liveness** (spec §1b): tick threads never wait on FAWE; owner-thread admission is nonblocking
`tryAcquire` only (F4); only FAWE workers await, worker→owner, bounded and cancellable.
Continuations that release existing work use reserved continuation capacity and never queue
behind the commits they unblock (F4). Caller-runs for owner-bound work is forbidden.

**Config-gated latency grafts** ride on top without weakening the skeleton — §3.8. Each defaults
conservatively, falls back to the safe path, and carries its `[NEEDS-RUNTIME]` tag.

## 2. Module & package layout (names are contracts)

**Module decision (r1-resolved; closes the W0.10 open item):** the Folia backend lives in a
dedicated Gradle module **`:worldedit-bukkit:folia`** with a **Java 25 toolchain** (forced by
folia-api's JVM 25+ class metadata; SS2 `Hooks/Folia` precedent).
`compileOnly("dev.folia:folia-api:26.1.2.build.8-stable")` is declared **only there** (pinned,
not a range — spec §2b). Its output is bundled **only into the Mojang/Paper artifact** and loaded
**only after reflective Folia detection**; the reobfuscated Spigot artifact excludes it.
`worldedit-core` remains **Java-21 output with zero Folia dependency**. `worldedit-bukkit`
proper gains no folia-api dependency.

- `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/` — **core-owned SPI**
  (F1: core never references a type declared by `worldedit-bukkit`, an adapter, or the Folia
  backend):
  - `FaweThreadContext.java` — thread-role predicate seam (§3.1). Ordinary (non-sealed)
    interface; platform modules implement it. Replaces raw `Fawe.isMainThread()` semantics.
  - `RegionTicket.java` / `EntityTicket.java` — **core-owned final capability classes** (§3.2),
    package-private constructors, minted only through the injected `TicketAuthority`.
  - `TicketAuthority.java` — same-package final class, non-public constructor, **single
    issuance** at backend bootstrap; injected into the platform dispatcher; never obtainable
    through a public accessor.
  - `ChunkTarget.java` / `EntityTarget.java` — core dispatch-target records (§3.5).
  - `RegionTask` / `RegionCall<T>` / `EntityTask` / `GlobalTask` — core-owned ticket-typed
    callback interfaces (§3.3).
- `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/` — Folia backend
  (Java 25 module):
  - `FoliaSupport.java` — reflective detection
    (`Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`), certified-matrix
    check, fail-closed guard. Detection runs BEFORE Paper detection, from `worldedit-bukkit`
    bootstrap, reflectively — the Folia module's classes load only after detection succeeds.
  - `FoliaTaskManager.java` — TaskManager impl on Folia schedulers.
  - `FoliaQueueHandler.java` — QueueHandler without the global sync-drain tick; routes the
    context-carrying internal surface (§3.5) to the dispatcher. Does NOT touch
    AsyncCatcher/physicsFreeze (§1b).
  - `FoliaRegionDispatcher.java` — the one class that talks to Folia's RegionScheduler /
    GlobalRegionScheduler / EntityScheduler / AsyncScheduler. Sole consumer of the injected
    `TicketAuthority`; mints and retires every ticket (§3.3).
  - `FoliaBackpressure.java` — per-region bounded admission with the full Demand/stage model
    (§3.4).
  - `FoliaCommitBroker.java` — per-region lane/mailbox set, sliced-sweep drain, DRR
    interleaving, rebind + permit transfer on split/merge (§3.7). `RegionKey` lives here as
    internal lane metadata only (F2).
  - `FoliaSnapshotCache.java` — versioned, leased, bounded GET snapshot cache (§3.7).
  - `OperationCompletion` + terminal-record types (§3.6) — the coordinator types reference no
    platform type and may live in core; their producers live behind the dispatcher.
- `worldedit-bukkit/adapters/adapter-26.1/` — gains the Folia-aware paths (only certified
  adapter, spec §2b). New classes prefixed `Folia` beside the `Paperweight` ones; shared logic
  extracted, never forked-and-drifted. The ~13 live-state adapter methods (w03 §7 / w05 Part 3)
  each gain a `RegionTicket` parameter — the grep-able C1 danger set.
- `plugin.yml`: `folia-supported: true`.
- Harness: `harness/` at repo root (committed), adapted from SS2's; scripts own server
  download/boot/scenarios/gates. `[W0-FREEZE]` for scenario/gate naming. G1–G8 perf producers:
  see §7.

## 3. Frozen semantic contracts

The C1–C6 anchors carry over; §3.1–§3.6 freeze the corrected SPI (`[W0-FREEZE]` conditional on
the F1 compile proof and §7 companion freezes). §3.7 is the engine behavior; §3.8 the grafts.

**C1 — Ownership routing.** Every live-state touch (`spikes/recon-adapters-nms.md` §6) goes
through `FoliaRegionDispatcher` on the owning context, under a `RegionTicket`/`EntityTicket`
minted for that single callback. No direct `MinecraftServer.execute`, `MCUtil.MAIN_EXECUTOR`,
legacy `BukkitScheduler`, or off-thread live-section CAS on the Folia backend, ever.
AsyncCatcher/physicsFreeze/Timings global toggles are never invoked on Folia. Adapter methods
require a ticket; only the dispatcher (via the injected authority) mints one; review of the
danger set is `git grep 'RegionTicket'`. Per amendment A1.4 **as extended by r1 F8**: completion
callbacks of any server-async facility are UNTRUSTED thread contexts — they may capture detached
results only, and must re-dispatch to the owning coordinator context **before touching live
state OR FAWE shared operation state** (completion sink, history, backpressure accounting).
Ticket typing enforces the live-state half; the shared-state half is a coordinator-API rule
(r4: the completion executor is the owning coordinator context for detached completion/history
state ONLY — it grants no live-state ownership and no permission to mutate backpressure outside
its existing dispatcher contract):
`OperationCompletion` and backpressure mutation methods are reachable only from dispatcher-run
contexts, asserted at entry.

**C2 — Detached data discipline.** Everything FAWE workers consume/produce is detached: GET
snapshots (+ stamps), prepared SET sections, history diffs. A detached object holds no reference
to any live NMS collection, chunk, section, entity handle, light-engine object, or viewer
collection — **and no ticket** (F3). Adapter methods split into `prepare*` (off-thread) /
`commit*` (region-thread, ticketed).

**C3 — Context-carrying sync.** Spec §4c: every public entry point is PRESERVED with
deterministic context derivation, DEGRADED with deterministic failure, or excluded by signed
amendment. The ten public `QueueHandler` descriptors are preserved **verbatim** (§3.5, F2).
Internal callers migrate to the core-target `syncOn` variants by compile-error-driven migration
with a requalification record.

**C4 — Operation completion.** Per spec §4d, realized by the F5-corrected protocol in §3.6:
registration-before-scheduling, keyed terminal records with applied receipts, explicit admission
close, completion only after persistence boundaries.

**C5 — Fawe.isMainThread() requalification.** Every call site (~20, inventoried in
`spikes/recon-queue-threading.md`) is requalified to `isTickThread()`, `ownsChunk(...)`,
`ownsEntity(...)`, `isFaweWorker()`, `isGlobalContext()`, or removed (assertion-only sites
subsumed by ticket-required signatures). Requalification record = review artifact. A build-time
check rejects new production references to `Fawe.isMainThread()` from Folia-enabled source sets.
**(r12 — perimeter clarification.)** The C5 inventory and build guard cover every production
predicate used to authorize inline execution or select an ownership-sensitive fallback, including
`Fawe.isMainThread()`, `Bukkit.isPrimaryThread()`, `MinecraftServer.isSameThread` equivalents,
direct server-thread identity comparisons, and raw platform tick-thread predicates outside the
approved backend context implementation. Approved backend predicate implementations are explicit
exceptions. Every discovered call site requires a C5 disposition; live-state fallbacks additionally
remain subject to C1 and may not use the legacy Bukkit scheduler. (Count note: the "~20" above is
stale — the re-derived census is ≈32 for `Fawe.isMainThread()` alone, before this widening.)

**C6 — Global signals.** Global TPS / `MinecraftServer.currentTick` gating is replaced on Folia
by per-region signals: `FoliaBackpressure.pressure(region)` snapshots, region-drain schedule
delay, slice runtime (spec §8). `AsyncPreloader` TPS>18 and `QueueHandler.getAllocate()` get
Folia equivalents keyed on the target region.

### 3.1 `FaweThreadContext` — core seam (F1-corrected)

**Resolution is deliberately partial and fail-closed (r12 Q2).** `FaweThreadContext.current()` is
unavailable before platform-context registration and throws `IllegalStateException` on premature
access. No platform-neutral fallback context is permitted: defaulting to legacy main-thread
identity is fail-open on Folia, while defaulting to non-tick/no-ownership changes Paper scheduling
semantics. Each backend MUST register its context exactly once before constructing or starting any
component, cache, supplier, scheduler, command path, or adapter capable of reaching a requalified
predicate. The registered context remains available through `stopAccepting`, drain, and disable
completion. A production call before registration is a bootstrap-order defect; tests MUST install
an explicit context. After Bukkit registration, the predicates retain the legacy Paper meanings.

Body from Proposal C; **F1 semantic diff:** no `sealed`/`permits` clause — core cannot name
downstream implementations, and unnamed-module sealed implementations would need a shared
package. The type is a platform-implemented core interface with a single-registration resolver.

```java
package com.fastasyncworldedit.core.util.task;

/**
 * Thread-role classification. Bukkit backend collapses tick/owner to the main thread;
 * Folia backend answers per region/entity/global scheduler ownership.
 * Pure predicate object — never blocks, never hops, never touches live state.
 * Implemented by the active platform backend; resolved through a single core resolver.
 */
public interface FaweThreadContext {

    static FaweThreadContext current() { return ContextResolver.resolve(); } // single resolver

    boolean isTickThread();                         // any server tick thread (region/entity/global)
    boolean ownsChunk(World world, int chunkX, int chunkZ);
    boolean ownsEntity(Entity entity);
    boolean isGlobalContext();                       // Folia global-region thread; Paper main
    boolean isFaweWorker();                          // extent-carrying FAWE prepare worker

    /** Fail-fast guard used at every seam that requires ownership. */
    default void requireOwns(World world, int cx, int cz) {
        if (!ownsChunk(world, cx, cz)) {
            throw new WrongOwnerException(this, world, cx, cz);
        }
    }
}
```

- **Bukkit backend:** `isTickThread() == ownsChunk(..) == isGlobalContext() == old isMainThread()`;
  `isFaweWorker()` = membership in the FAWE pool (explicit thread-factory marker, not a name
  comparison). Zero behavior change on Paper.
- **Folia backend:** delegates to `TickThread` / Folia ownership queries. Never disables or
  bypasses `TickThread` (spec §1b hard bar).
- The resolver accepts exactly one platform registration at bootstrap; re-registration fails.

### 3.2 `RegionTicket` / `EntityTicket` — core-owned capabilities (F1/F3-corrected)

**F1:** capabilities are **core-owned final classes** — not interfaces implemented downstream —
so non-forgeability does not depend on cross-module sealing. Constructors are package-private;
the only minting path is `TicketAuthority`, a same-package final class with a non-public
constructor whose **sole instance is issued exactly once** at backend bootstrap and injected
into the platform dispatcher. No public accessor exposes it; a second issuance attempt throws.
Platform modules neither implement nor construct tickets directly.

**F3 (validity contract):** a ticket is valid **only for the lexical dynamic extent of one
dispatcher callback** and is retired by the dispatcher in its `finally` block. A ticket may
never be stored in a field, detached plan, future, callback result, or finalizer. Every
cross-owner phase records a detached receipt, yields without waiting, and resumes through the
dispatcher with a fresh target-specific capability. Entity work uses an entity-bound
`EntityTicket` (exact-entity assertion at execution time), never a chunk ticket.

```java
package com.fastasyncworldedit.core.util.task;

/**
 * Proof — valid only for the lexical dynamic extent of the single dispatcher callback it was
 * minted for — that the current thread owns the named region and may touch its live state.
 * Minted only by the injected TicketAuthority; retired in the dispatcher's finally block.
 * Never stored in a field, detached plan, future, callback result, or finalizer.
 */
public final class RegionTicket {

    RegionTicket(/* package-private: TicketAuthority only */) { /* ... */ }

    public World world() { /* ... */ }
    public boolean owns(int chunkX, int chunkZ) { /* ... */ }

    /** Fail-fast: throws if this ticket does not own (cx,cz), is used off its minting thread,
     *  or has been retired. */
    public void assertOwns(int chunkX, int chunkZ) { /* ... */ }

    /** True only until the dispatcher retires this ticket (end of its one callback). */
    public boolean isLive() { /* ... */ }

    /** Monotonic mint sequence — identifies an individual callback mint for DIAGNOSTICS ONLY.
     *  MUST NOT key terminal completion; the terminal key is the plan sequence (§3.6). */
    public long sequence() { /* ... */ }
}

/** Entity-bound analogue: exact-entity ownership proof for the extent of one entity callback. */
public final class EntityTicket {

    EntityTicket(/* package-private: TicketAuthority only */) { /* ... */ }

    public Entity entity() { /* ... */ }
    public void assertOwns(Entity entity) { /* ... */ }  // exact-entity assertion
    public boolean isLive() { /* ... */ }
    public long sequence() { /* ... */ }
}
```

Defense-in-depth: no ticket in scope → an adapter live-state call cannot compile (the common
mistake); an escaped/retired ticket fails `assertOwns`/`isLive` at runtime (the exotic one).

### 3.3 `FoliaRegionDispatcher` — single choke point (F1/F3/F10-corrected)

Skeleton from Proposal C; **semantic diffs:** callback types are core-owned (F1); every
submission carries a `TaskKind` label feeding the §8 instrumentation (F10); entity callbacks
receive an `EntityTicket` (F3); lifecycle control added (F10). **Inline execution guarantee
(made explicit per r1 F11):** `onRegion`/`onEntity` run the callback **inline, synchronously,
when the calling thread already owns the target** (ticket still minted and retired normally);
otherwise they schedule on the owning Folia scheduler. This guarantee is a contract of THIS
interface — v2 wrongly presented it as an inherited Proposal-C property. G-A1 builds on it.

Core-owned callback types (`com.fastasyncworldedit.core.util.task`):

```java
@FunctionalInterface public interface RegionTask { void run(RegionTicket ticket); }
@FunctionalInterface public interface RegionCall<T> { T call(RegionTicket ticket); }
@FunctionalInterface public interface EntityTask { void run(EntityTicket ticket); }
@FunctionalInterface public interface GlobalTask { void run(); }
```

Dispatcher (Folia module; references core types only):

```java
package com.fastasyncworldedit.bukkit.folia;

/** The ONE class that talks to Folia schedulers. Every region hop, ticket mint/retire,
 *  and instrumentation label lives here (C1). */
public interface FoliaRegionDispatcher {

    /** Phase/instrumentation label for §8 timers (F10). Not an authorization. */
    enum TaskKind { SNAPSHOT_CAPTURE, COMMIT, FINALIZER, PACKET, LEGACY_GLOBAL, ASYNC }

    /** Run on the region owning (world,cx,cz); the task receives a fresh RegionTicket,
     *  retired in this dispatcher's finally block. Inline when the caller owns the target. */
    CompletionStage<Void> onRegion(World world, int cx, int cz, TaskKind kind, RegionTask task);
    <T> CompletionStage<T> onRegion(World world, int cx, int cz, TaskKind kind, RegionCall<T> call);

    /** Run on the entity's owning context; the task receives a fresh EntityTicket.
     *  Entity retirement completes the stage exceptionally. Inline when the caller owns it. */
    CompletionStage<Void> onEntity(Entity entity, TaskKind kind, EntityTask task);

    /** Global-region thread (config, cross-cutting lifecycle only). No ticket: owns no chunk. */
    CompletionStage<Void> onGlobal(TaskKind kind, GlobalTask task);

    /** Lifecycle (F10): stop new submission; already-running callbacks may finish. */
    void stopAccepting(Throwable reason);

    /** Bounded drain: completes when in-flight callbacks settle or the deadline expires,
     *  reporting unresolved tickets/tasks. Never awaited from a tick thread. */
    CompletionStage<DrainReport> drain(Duration deadline);

    record DrainReport(int unresolvedTasks, int liveTickets, List<String> unresolvedLabels) {}
}
```

**Drain-report snapshot at expiry (r15 — reconciles the bounded-expiry rule with the full-report
contract; NO frozen SPI change, `DrainReport` is unchanged).** The dispatcher maintains an
immutable diagnostic snapshot **incrementally** as tasks and tickets are registered and retired.
One atomic snapshot contains the exact `unresolvedTasks` count, the exact `liveTickets` count, and
a complete immutable view of the corresponding unresolved-task labels from the same snapshot
epoch. Label indexing uses structural sharing or equivalent bounded-per-update bookkeeping; it
MUST NOT rebuild or sort the complete label population on a tick thread.

At drain-deadline expiry the bounded flush-expiry hook performs only: (1) its idempotent waiter
claim, (2) one atomic snapshot read, and (3) submission of that already-built immutable
`DrainReport` through its registered producer. It performs no label scan, copy, sort, user callback
or direct future completion. Iterating or rendering the immutable label view happens later on the
diagnostic consumer, never inside the expiry hook. The snapshot linearization point defines the
report's deadline state; work settling after that point does not rewrite the published report.

*G6 consequence:* `unresolvedTasks` and `liveTickets` remain exact and mutually coherent at
expiry; any non-zero count still fails G6 exactly as specified; `unresolvedLabels` remains
complete diagnostic evidence, neither capped nor truncated; the certification procedure is
unchanged and may serialize or sort labels after receipt. Label order need only be deterministic —
the frozen interface does not require lexical sorting at expiry.

*General rule:* any producer requiring an unbounded terminal diagnostic must maintain a publishable
immutable snapshot incrementally, or define an explicit capped diagnostic contract in advance.
**Flush-expiry hooks never gain an implicit unbounded-snapshot exception.**

- **Callbacks are typed, not `Runnable`** — live work must accept the capability and name its
  target (w06 attack point 1). `onGlobal` grants no ticket by omission.
- **No raw scheduler leaks:** `RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler` /
  `AsyncScheduler` referenced only inside the implementation (CI-enforceable grep).
- **Instrumentation (F10):** the implementation records, per `TaskKind` and per region:
  schedule delay, callback runtime, ticket mint/retire counts — the G3/G4 producer seam.
- **`RegionKey` does not appear here** (F2): lane grouping metadata is internal to
  `FoliaCommitBroker`. Returned stages complete through the FAWE completion executor, never
  running arbitrary continuations on a tick thread.
- Backed by `RegionScheduler.execute(plugin, world, cx, cz, task)` (A5, W0.2:
  `owner_mismatches=0`).

### 3.4 `FoliaBackpressure` — full admission model (F4-corrected)

**F4 semantic diff from v2:** v2 froze Proposal C's two-method `acquire(RegionKey, Duration)`
and kept B's multi-resource behavior only as prose — which cannot carry demand, priority,
cancellation, stage transfer, or byte accounting, and permits admission cycles (finalizer
continuations queueing behind the commits they unblock). v3 **restores Proposal B's SPI**
(below, copied from `proposal-B-throughput.md` §1.4) and adds the three r1-mandated
corrections: owner-thread nonblocking `tryAcquire`, reserved continuation capacity, and permit
transfer on region merge. This interface is Folia-module-internal; `RegionKey` here is internal
lane metadata, legal per F2.

```java
package com.fastasyncworldedit.bukkit.folia;

public interface FoliaBackpressure {

    enum Priority {
        INTERACTIVE,
        NORMAL,
        BULK
    }

    enum Stage {
        READY,
        SCHEDULED,
        COMMITTING,
        FINALIZING
    }

    record Demand(
            UUID operationId,
            int chunks,
            long preparedBytes,
            int finalizerChains,
            Priority priority,
            boolean previouslyAccepted,
            long deadlineNanos
    ) {

        public Demand {
            if (chunks <= 0) {
                throw new IllegalArgumentException("chunks must be positive");
            }
            if (preparedBytes < 0 || finalizerChains < 0) {
                throw new IllegalArgumentException("negative demand");
            }
        }
    }

    record Limits(
            int maxReadyChunksPerRegion,
            long maxReadyBytesPerRegion,
            int maxFinalizersPerRegion,
            int maxWaitersPerRegion,
            int maxGlobalReadyChunks,
            long maxGlobalReadyBytes,
            int maxGlobalFinalizers
    ) {
    }

    record Pressure(
            int readyChunks,
            long readyBytes,
            int finalizerChains,
            int waiters,
            int scheduledDrains,
            long oldestReadyNanos,
            long scheduleDelayEwmaNanos,
            long sliceRuntimeEwmaNanos
    ) {
    }

    interface Permit extends AutoCloseable {

        RegionKey region();

        Demand demand();

        Stage stage();

        /**
         * Stages may advance only in the declared order.
         */
        void enter(Stage next);

        /**
         * Idempotent. Releases all remaining accounting.
         */
        @Override
        void close();
    }

    Limits limits();

    /**
     * Completes on a FAWE executor. The cancellation signal completes when
     * the operation is cancelled. Never called from a tick thread.
     */
    CompletionStage<Permit> acquire(
            RegionKey region,
            Demand demand,
            CompletionStage<?> cancellationSignal
    );

    /**
     * F4: owner-thread admission. Immediate and nonblocking — returns empty instead of
     * ever waiting or returning a future the caller might await. The ONLY admission
     * entry point legal from a tick thread (G-A1 uses it exclusively).
     */
    Optional<Permit> tryAcquire(RegionKey region, Demand demand);

    /**
     * F4: continuation capacity. A continuation required to release existing accepted work
     * (entity-phase resume, light materialization, neighbor settlement) draws from a reserved
     * per-region continuation budget and never queues behind the commits it unblocks.
     */
    CompletionStage<Permit> acquireContinuation(
            RegionKey region,
            Demand demand,
            CompletionStage<?> cancellationSignal
    );

    /**
     * F4: region merge/split transfer. Before any mutation under a permit whose observed
     * region is stale, the permit's accounting is transferred to the actual current region.
     * Returns false when the target region cannot admit it now — the plan defers or fails
     * before mutation; it never mutates under stale accounting.
     */
    boolean transfer(Permit permit, RegionKey actualRegion);

    Pressure pressure(RegionKey region);

    void recordScheduleDelay(
            RegionKey region,
            long delayNanos
    );

    void recordSlice(
            RegionKey region,
            int chunks,
            long runtimeNanos
    );

    void stopAccepting(Throwable reason);
}
```

Permit lifecycle (Proposal B): `READY` (bytes + ready chunk accounted) → `SCHEDULED` (a drain
accepted it) → `COMMITTING` (owner mutation executing) → `FINALIZING` (ready bytes/chunk
released; finalizer capacity held) → `close()` (all released). Invalid transitions terminate
that chunk plan exceptionally.

Initial certification values (config-backed; frozen or revised by §8 — `[NEEDS-RUNTIME]`):
256 ready chunks / region · 64 MiB prepared SET / region · 64 finalizer chains / region ·
256 admission waiters / region · 65,536 global ready chunks · `min(1 GiB, maxHeap/4)` global
prepared bytes · 4,096 global finalizer chains. The reserved continuation budget is carved out
of (not added to) the per-region finalizer capacity. Per-region limits do not replace the
global byte cap; global caps bound process resources only, never a per-region health signal.

**Saturation behavior** (Proposal B §5.3, unchanged): no owner-bound task on the submitting
thread; the worker pipeline stops producing for a saturated region; bounded async waiters;
legacy synchronous A/W callers await with bounded deadline and no locks; R/E/G callers that
would need to wait are rejected before accepting mutations; full waiter queue or expired
deadline → rejection before acceptance (terminalized `NOT_ACCEPTED` per §3.6); the operation
completes as failure or explicit partial failure; the undo record includes only applied
receipts. **No caller-runs for owner-bound work, ever.** Only worker→owner waits exist
(acyclic, bounded, cancellable, thread-dump-assertable).

**Frozen diagnostic snapshot producer (r2 amendment 4 — the G4 seam).** Per the required
amendment: freeze a per-region and global diagnostic snapshot producer containing ready
chunks/bytes, waiters, finalizer chains, packet bytes, scheduled drains, live tickets,
outstanding futures, outstanding chunks, rebind count, and oldest-ready age. The producer is
implemented by `FoliaCommitBroker`, aggregating `FoliaBackpressure.pressure` with the
dispatcher's ticket counters and the broker's own future/rebind accounting:

```java
/** Per-region or global (region == null) diagnostic snapshot. Frozen G4 producer. */
record DiagnosticSnapshot(
        Optional<RegionKey> region,              // empty = global aggregate
        int readyChunks,
        long readyBytes,
        int waiters,
        int finalizerChains,
        long packetMailboxBytes,
        int scheduledDrains,
        int liveTickets,                         // dispatcher mint/retire counter delta
        int outstandingFutures,                  // dispatcher stages not yet settled
        int outstandingChunks,                   // registered, non-terminal plans (§3.6)
        long rebindCount,                        // lane rebinds after split/merge
        long oldestReadyNanos
) {}

/** On FoliaCommitBroker (frozen): */
DiagnosticSnapshot diagnostics(RegionKey region);
DiagnosticSnapshot diagnosticsGlobal();
```

**Exact `FAWE_QUEUE` mapping (w08 G4):** each emission of the diagnostic line
`FAWE_QUEUE depth= inflight= outstanding= region=` is produced from one snapshot as:

- `depth=` `readyChunks` (admitted plans not yet accepted by a drain);
- `inflight=` `scheduledDrains + finalizerChains` (plans accepted by a drain or holding
  finalizer capacity — the value gated against `maxInFlightPerRegion` by perf-06);
- `outstanding=` `outstandingChunks` (registered, non-terminal — the §4d-visible backlog);
- `region=` `worldId:observedRegionId` from the snapshot's `RegionKey`, or the literal
  `global` for the global aggregate.

The remaining snapshot fields (bytes, waiters, live tickets, outstanding futures, rebinds,
oldest-ready age) are emitted on the extended diagnostic line of the same producer for G3/G4
analysis. Harness wiring and numeric thresholds remain wave-1 gates (§7 F10); the seam above
is the frozen architecture-side producer.

### 3.5 Context-carrying sync (F2-corrected)

**Reachable target-bearing routing (r17 Q1).** `QueueHandlerRouting` — a final
`@ApiStatus.Internal` class in the same package as `QueueHandler` — is the cross-package internal
facade for the protected `syncOn` family: static `syncOn(ChunkTarget, RegionCall<T>)`,
`syncOn(ChunkTarget, RegionTask)`, `syncOn(EntityTarget, EntityTask)`, `syncOnGlobal(GlobalTask)`,
and `permitsLegacyLocationFreeLiveState()`. It delegates to the active queue handler and names only
core-owned targets and callbacks; **callers never obtain or name a Folia dispatcher**. The protected
methods remain subclass extension points, and `TaskManager` gains **no** new supported entry points.
*Public-surface notice:* these are public JVM declarations because cross-package modules must link
to them, but they are `@ApiStatus.Internal`, recorded `INTERNAL-NONAPI`, excluded from the §4c
consumer contract, and carry no compatibility promise — the 653 `PRESERVED` declarations are
unchanged.

**Multi-target work MUST partition (r17 Q1).** Multi-target work partitions by `ChunkTarget` and
dispatches each partition separately. **Proving ownership of one — or even of all — targets does
NOT authorize executing foreign-region work in a single callback.**

**Lazy entity NBT (r17 Q2 — degradation NOT authorized).** A Folia-enabled adapter constructing
`LazyBaseEntity` from a live entity MUST supply an `EntityTarget` for that same entity, via the
additive constructor `LazyBaseEntity(EntityType, EntityTarget, Supplier<LinCompoundTag>)`. Lazy NBT
serialization executes through `QueueHandlerRouting.syncOn(target, ...)` under a fresh
`EntityTicket`. An owning caller may complete inline; A/W callers may await only under the existing
bounded-wait rule; a non-owning tick caller **fails before dispatch**, because this synchronous
getter cannot await. The two-argument constructor remains compatibility-only for non-Folia-enabled
adapters, and Folia-enabled source sets are build-guarded against using it.

**Section CAS (r17 Q3).** `NMSAdapter` gains
`protected static <S> CompletionStage<Boolean> setSectionAtomic(World, IntPair, S[], S expected,
S value, int layer)`. It performs **exactly one** section CAS, solely inside
`QueueHandlerRouting.syncOn(new ChunkTarget(world, pair.x(), pair.z()), ...)`, and its callback
asserts the fresh `RegionTicket`. **There is no non-owner CAS branch and no FAWE-lock fallback on
Folia.** Adapter-26.1 forwarding methods and callers compose this stage rather than blocking a tick
thread. The legacy String overload survives for excluded Paper adapters: it first requires
`permitsLegacyLocationFreeLiveState()`, failing before live access on Folia; on the permitted Paper
backend it uses `isGlobalContext()` — definitionally identical to the legacy main-thread predicate —
and retains the existing lock/CAS behaviour.

**W1-exit condition (r17).** **Zero temporary C5 allowances may survive W1 exit** in Folia-enabled
source sets. Any *permanent* compatibility allowance must identify an excluded source set, carry a
build-enforced non-reachability condition from the Folia adapter graph, and name an explicit
future-enablement owner. Deferral requires a signed degradation/re-scope; it is **not** implicitly
authorized. ("No worse than upstream" is not Folia certification.)

**Ownership guards are authorization, not routing (r12 Q1).** An ownership predicate is an
authorization check, not a routing mechanism. When a callback carries an entity, chunk, or region
target, the true branch may execute inline only in the owning context. The false branch MUST route
through the corresponding target-bearing `syncOn` or ticketed dispatcher entry. It MUST NOT fall
back to location-free `sync` or `syncWhenFree`. Location-free current-tick execution is legal only
for callbacks whose contract is explicitly current-context-safe and carries no foreign ownership
target. A wrong-owner callback fails before side effects if its target-bearing route is
unavailable. *Consequence:* an ownership guard whose false branch funnels into location-free `sync`
is INERT — it is strictly less safe than the untranslated legacy call, which would at least have
enqueued.

**F2 semantic diffs from v2:** (a) `RegionKey` is banned from every core or public scheduling
signature — the internal target is a **core** `ChunkTarget`/`EntityTarget` (a `RegionKey` has
no chunk anchor and is not schedulable); (b) every internal asynchronous overload returns
`CompletionStage`, including the `Runnable` form; (c) the ten existing public descriptors are
preserved **verbatim** — v2's frozen example silently altered them and omitted eight; that is
void.

Core dispatch targets:

```java
package com.fastasyncworldedit.core.util.task;

public record ChunkTarget(World world, int chunkX, int chunkZ) {
    public ChunkTarget { Objects.requireNonNull(world, "world"); }
}

public record EntityTarget(Entity entity) {
    public EntityTarget { Objects.requireNonNull(entity, "entity"); }
}
```

**Preserved public surface — the ten `QueueHandler` descriptors, verbatim from
`QueueHandler.java:203-327` (binary/source compatibility, w06 Y/Y):**

```java
public <T> Future<T> async(Runnable run, T value)
public Future<?> async(Runnable run)
public <T> Future<T> async(Callable<T> call)
public <T> Future<T> sync(Runnable run)
public <T> Future<T> sync(Callable<T> call) throws Exception
public <T> Future<T> sync(Supplier<T> supplier)
public <T> Future<T> syncWhenFree(Runnable run, T value)
public <T> Future<T> syncWhenFree(Runnable run)
public <T> Future<T> syncWhenFree(Callable<T> call) throws Exception
public <T> Future<T> syncWhenFree(Supplier<T> supplier)
```

Their Folia derivation rule (§4c): `async` remains detached FAWE work, never used for live
state. A location-free `sync`/`syncWhenFree` called from a tick context runs in that current
context; called from A/W it derives the **global region** deterministically. A tick-thread
caller whose callback needs a *different* owner fails fast before side effects ("wrong tick
fails"). The Folia `QueueHandler` has no global one-tick drain loop.

**New internal, context-carrying migration target (core signatures; `CompletionStage`
everywhere, including the `Runnable` form):**

```java
protected <T> CompletionStage<T> syncOn(ChunkTarget target, RegionCall<T> call)
protected CompletionStage<Void>  syncOn(ChunkTarget target, RegionTask task)
protected CompletionStage<Void>  syncOn(EntityTarget target, EntityTask task)
protected CompletionStage<Void>  syncOnGlobal(GlobalTask task)
```

- Internal callers migrate to `syncOn(...)` by compile-error-driven migration; the
  requalification record (site → target/rationale) is the C3 review artifact. Internal lambdas
  that contain a world, chunk, player, or entity may not use the location-free fallback.
- `syncOn` never blocks a tick thread. A synchronous `TaskManager` wrapper may await its stage
  only from A/W, with the operation deadline or configured owner-wait timeout, while holding no
  chunk, session, queue, or history lock.
- `runUnsafe` → **DEGRADED**: fails fast with `UnsupportedOperationException` + caption before
  the callback (w06 attack point 2).

### 3.6 Commit phases, snapshot validation, and exactly-once completion (F3/F5/F6/F8-corrected)

**Base-stamp validation (F6).** Region-thread serialization replaces the chunk send lock — it
does **not** replace stale-base validation, and FAWE's own `ChunkVersion` cannot see vanilla,
player, or third-party mutation, nor an unload/reload epoch reset (W0.2 never exercised
overlapping external mutation). Therefore every snapshot — and every `PreparedChunkCommit`
derived from it — carries:

- a **world-instance/load epoch** (invalidated by world unload/reload),
- the **FAWE mutation sequence** (`ChunkVersion(K)`), and
- **fingerprints or identities of every live base component the plan replaces** (per-section
  content fingerprint or object identity; tile/entity identity sets for the touched subset).

The owner validates **all** stamps before the first mutation of the chunk. Any mismatch
performs **no mutation** and triggers a bounded recapture/reprepare cycle (bounded attempts,
then explicit `FAILED_BEFORE_MUTATION`). Region rebind never waives this validation.
`[NR-J]` measures the fingerprint cost.

**Phase order per accepted chunk (F8 terminal order).** Every owner phase is **one dispatcher
callback under one fresh ticket, retired in the dispatcher's `finally`** (F3). Cross-owner
phases yield with detached receipts and resume with fresh capabilities via
`acquireContinuation` (F4). The order:

```
 1. SECTIONS        owner cb: validate ALL base stamps → install prepared section replacements
                    → on unexpected failure restore already-replaced sections where possible
                    → bump ChunkVersion(K), invalidate GET entries → record applied bitmap
 2. CHUNK FIELDS    same cb: setLightCorrect, mustNotSave, heightmaps (w05 GetBlocks:751-752)
                    + L1 light injection (fillLightNibble et al., committed chunk only, w03 §3.1)
 3. TILES           owner cb: getBlockEntity / loadWithComponents; failure stops the phase and
                    records the exact successful subset
 4. ENTITIES        additions on the owner of the target location; existing-entity work yields
                    to entity callbacks (EntityTicket each); the chunk region never waits; when
                    all entity receipts settle, resume on the chunk owner
 5. BEACON/BE REMOVAL  owner cb (removeBeacon moved off the worker — w05 Part 3)
 6. POI + NEIGHBORS owner cb for POI; neighbor effects crossing an ownership boundary are
                    decomposed by destination chunk and dispatched to each destination owner;
                    the next phase begins only after all required neighbor receipts settle
 7. G-A3 EARLY PACKET (optional, config-gated — §3.8): block-visibility packet before relight
 8. RELIGHT COMPUTE detached NMSRelighter on workers (w03 L3) — never a terminal point
 9. LIGHT MATERIALIZATION  fresh owner re-dispatch: materialize relight output into the chunk
                    (relight callbacks arrive on non-owner threads — W0.2; A1.4)
10. PACKET SEND     required packet build/enqueue on the owner (A1/A3); per-viewer entity
                    mailbox for cross-owner viewers (w05 2d fallback pending [NR-E]); with
                    G-A3 enabled this is the follow-up light packet. The phase completes when
                    all server-side send enqueues succeed, not on client ack
11. APPLIED PERSIST the APPLIED history record (applied section bitmap, tile IDs, entity
                    UUIDs/actions, POI/neighbor IDs, light sections, packet result, terminal
                    failure) reaches its configured persistence boundary
12. TERMINAL        GET cache eviction for K, then the chunk terminal record (below)
```

Relight *submission* is never a terminal point (F8): the chunk is terminal only after its light
materialization, required packet enqueues, and APPLIED persistence settle. FAWE's
`DELAY_PACKET_SENDING` batching is the default packet timing; G-A3 is the gated early-packet
inversion.

**Cross-region neighbor ordering (F8):** decomposing boundary neighbor effects per destination
owner weakens same-tick neighbor ordering across region boundaries relative to Paper. This
weakening is **not established by this document**: it is recorded as a pending disposition on
`INV-DEG-006` (w07 compat inventory) and must be either certified or added to the signed
amendment file before wave-1 code relies on it.

**Write-ahead history (Proposal B §6.2):** `PREPARED` (operation ID, chunk ticket/version +
stamps, before/after section data, tile/entity/POI/light deltas, deterministic phase item IDs)
is durable before owner mutation for persistent history; `APPLIED` as in phase 11. A restart
finding `PREPARED` without `APPLIED` resolves via the stored fingerprints and item identities
before exposing the undo entry. In-memory history uses the equivalent publication point.

**Exactly-once completion (F5 — replaces v2's two-record sink; r2 amendments 1–2 applied).**
v2's `Committed/CommitFailed` fold could not represent partial execution, had no
registration/closure protocol, and decremented on duplicates. The corrected protocol:

**Terminal identity (r2 amendment 1, required wording):** `ChunkTerminalRecord` and
`OperationCompletion` use a per-chunk plan sequence allocated by the same-chunk sequencer
before registration and scheduling. The plan sequence remains invariant across dispatcher
callbacks, ownership rebinds, recapture/reprepare attempts, and finalizers.
`RegionTicket.sequence()` identifies an individual callback mint for diagnostics only and
MUST NOT key terminal completion.

**Registration and rejection (r2 amendment 2, required wording):** Every chunk ticket entering
admission is registered before its admission attempt. Rejection before acceptance terminates
that registration exactly once with `NOT_ACCEPTED` and the rejection cause. Every accepted
chunk remains registered before scheduling. Admission closure preserves the aggregate rejection
cause so an operation with no committed mutation resolves `FAILED`, not successful empty
completion.

```java
/** Six-state terminal classification (Proposal B §6.1). */
enum TerminalStatus {
    NOT_ACCEPTED, NO_CHANGE, FAILED_BEFORE_MUTATION,
    COMMITTED, PARTIALLY_COMMITTED, CANCELLED_BEFORE_MUTATION
}

/** History persistence settlement of one applied receipt (r4 amendment 2). */
enum HistorySettlement { NOT_REQUIRED, DURABLE, UNAVAILABLE }

/** Exact applied subset — the undo/report source of truth. */
record AppliedReceipt(
        long appliedSectionBitmap,
        List<String> appliedTileIds,
        List<EntityAction> appliedEntities,      // UUID + action actually performed
        List<String> appliedPoiNeighborIds,
        long appliedLightSections,
        PacketPhaseResult packetResult,
        HistorySettlement historySettlement      // finalized before result publication (r4 am. 2)
) {}

/** Terminal record, keyed. Duplicates by key are ignored (counted diagnostically). */
record ChunkTerminalRecord(
        UUID operationId,
        long chunkKey,
        long planSequence,                       // same-chunk sequencer allocation, invariant
                                                 // across callbacks/rebinds/recapture (r2 am. 1)
        TerminalStatus status,
        AppliedReceipt applied,                  // empty receipt for pre-mutation statuses
        Optional<Throwable> failure
) {}

/** One coordinator per operation. OPEN → COMPLETING → SUCCEEDED | FAILED | PARTIAL. */
public interface OperationCompletion {

    /** Registration happens exactly once per chunk plan, BEFORE its admission attempt
     *  (and therefore before scheduling). planSequence comes from the same-chunk sequencer. */
    void register(long chunkKey, long planSequence);

    /** No further registration after this; completion becomes reachable. Preserves the
     *  aggregate rejection cause of NOT_ACCEPTED terminals (r2 amendment 2). */
    void closeAdmission();

    /** Idempotent per (operationId, chunkKey, planSequence); duplicates never decrement. */
    void terminal(ChunkTerminalRecord record);

    CompletionStage<OperationResult> future();   // completes exactly once
}
```

The operation completes only when: admission is closed, **and** every registered chunk has a
terminal record, **and** every required finalizer has settled, **and** every APPLIED record has
reached its configured persistence boundary. `SUCCEEDED` additionally requires every accepted
mutation committed, all required finalizers succeeded, and all packet sends enqueued. Failures
aggregate internally; the actor/API receives one terminal result. A partially committed chunk
is representable (`PARTIALLY_COMMITTED` + its exact `AppliedReceipt`) — undo operates only on
the applied subset; whole-operation rollback is never attempted automatically (other regions may
have observed their commits). Cancellation after mutation begins does not abandon
consistency-required phases (Proposal B §6.3). **Staged ≠ committed** (w06 attack point 3):
EditSession boolean/int returns describe staged preparation; terminal success is
`OperationCompletion.future()` — surfaced at `close()`/`flushQueue()` (APIC-057).

**Unload/disable (Proposal B §6.5; r2 amendment 3 applied):** unload before mutation →
`FAILED_BEFORE_MUTATION`; after a phase began → exact applied receipt + partial failure.
Shutdown: `stopAccepting` first, unaccepted work cancelled, scheduled callbacks finish within
the bounded `drain` deadline; no tick context waits for shutdown drain. **Drain
terminalization (required wording):** Expiry of the shutdown drain deadline does not create an
`INCOMPLETE` terminal state. Every registered plan that has not mutated terminates as
`CANCELLED_BEFORE_MUTATION` or `FAILED_BEFORE_MUTATION`. Every plan that has mutated reaches
persistence settlement (§3.6b) with its exact receipt. Its `TerminalStatus` continues to
describe the world mutation outcome: fully applied work remains `COMMITTED`, partially
applied work is `PARTIALLY_COMMITTED`; persistence failure is represented only by
`HistorySettlement.UNAVAILABLE` and the resulting `OperationResult` classification. Non-zero unresolved tasks or live tickets in `DrainReport` constitute a
failed certification gate; the report does not substitute for operation terminalization.
Drain duration and unresolved counts remain certification measurements (F10 seam).

**§3.6b Persistence settlement + completion service (adjudication 2026-07-17 of the task-11
NEEDS_CONTEXT escalation; CO-SIGNED r6, re-CO-SIGNED r9 after the r8 notification-isolation
amendment — r4 amendments 1–5 + r5 drain-status correction + r8 amendment 1 applied;
`codex-arch-cosign-r4..r9` = `.codex/arch-cosign-r{4,5,6,7,8,9}.msg`).**

*Persistence settlement.* The completion gate's "every APPLIED record has reached its
configured persistence boundary" is defined as **settlement**: the persistence attempt either
(a) durably succeeds (`DURABLE`), or (b) terminally fails (`UNAVAILABLE`) after the
configured bounded retry policy (attempt count + backoff; config-gated, W1-exit numeric slot
`HISTORY_PERSIST_RETRIES`). Every persistence attempt has a finite timeout, and the complete
retry sequence has a finite settlement deadline. Timeout, cancellation, or exceptional
completion consumes an attempt. On shutdown, the remaining settlement deadline is clamped to
the drain deadline. Exhaustion produces terminal `UNAVAILABLE` settlement. No configuration
may select unbounded attempts, backoff, or attempt duration. The retry timer remains
lifecycle-guaranteed until settlement flush completes. A terminal persistence failure NEVER
decrements or bypasses the gate — it settles it with a recorded outcome:
- The affected `AppliedReceipt` carries `historySettlement = UNAVAILABLE` (the
  `HistorySettlement` field of §3.6; exact applied subset is still reported; nothing is
  invented). No receipt exposed through `OperationResult` may remain pending: the coordinator
  finalizes the receipt's settlement before publishing the operation result. Terminal
  deduplication remains keyed only by `(operationId, chunkKey, planSequence)`.
- **Status separation (r4 amendment 4):** `TerminalStatus` describes world mutation only and
  is not changed solely by persistence failure. `OperationResult` is `PARTIAL` when any world
  mutation was applied and any required settlement or finalizer failed; `FAILED` when no
  world mutation was applied; `SUCCEEDED` only when all required persistence settlements are
  `DURABLE`. The actor-facing message states that the listed chunks committed but their
  undo/history could not be persisted. Exactly once, never `SUCCEEDED` under a failed
  required settlement.
- *History scope:* if the history implementation publishes one operation-scoped undo entry,
  any `UNAVAILABLE` required settlement makes that entire entry unusable. A durable subset
  may remain usable only when the persistence format provides independently addressable
  subset entries and the actor result identifies that subset explicitly. History must never
  be exposed as usable when its boundary was not reached (spec §4d sentence 3).
This satisfies, simultaneously: the gate (settlement, not skip), exactly-once actor
completion (spec §4d sentence 2), and history honesty. Spec §4d's "valid matching undo
record" clause binds its enumerated interruption classes (cancellation, timeout, unload,
migration, disable) — a persistence-subsystem fault is not in that class and yields the
explicit lossy-failure result above; no amendment to spec §4d is required.

*Completion service.* Terminal aggregation runs on the completion service's dedicated
single-thread control executor — a **backend-owned completion service** created at backend
bootstrap and shared by per-operation coordinators. Publication of
`OperationCompletion.future()` and every other externally observable stage completion is
delegated as an immutable outcome to the service's lifecycle-owned isolated notification
tasks (r8 amendment 1). Synchronous consumer continuations never run on the control
executor. Completion flush waits for internal transitions and outcome publication, not for
arbitrary consumer continuations to return. Its thread is **not**
`FaweThread`-marked (§3.1: the marker denotes extent-carrying prepare workers only). The
service has `ACCEPTING → FLUSHING → TERMINATED` lifecycle states (r4 amendment 3). Every
asynchronous producer (persistence and finalizer callbacks included) registers before
initiating its boundary and deregisters only after its coordinator transition has executed.
Flush first closes producer registration, then waits for the registered-producer count to
reach zero and for all queued completion work to execute; only then may the executor shut
down (`stopAccepting` → drain → completion flush → executor shutdown).

**Drain-expiry terminalizers (r10 Q1).** Every plan producer and admission-open coordinator
producer registers, atomically with producer registration, a bounded idempotent flush-expiry
hook. Entering `FLUSHING` publishes the absolute drain deadline to every outstanding hook;
deadline expiry invokes each hook exactly once. The plan-state owner atomically freezes its last
applied state and terminalizes it: no mutation → `CANCELLED_BEFORE_MUTATION` or
`FAILED_BEFORE_MUTATION`; any mutation → the truthful world-mutation `TerminalStatus`, exact
current receipt, and `HistorySettlement.UNAVAILABLE` where persistence cannot settle durably. The
hook runs no user code or live-state work and cannot leave its producer registered. `flush` is
itself deadline-bounded and waits after expiry only for these bounded internal transitions and
immutable outcome publication, never for consumer continuations. No registered plan or
admission-open coordinator may lack this hook.

**Post-termination inline fallback (r10 Q4, replacing the earlier concession).** Post-termination
inline fallback is permitted only for a non-tick asynchronous callback, under the coordinator
serialization primitive, and only for bounded internal state work that runs no user code,
performs no live-state access, and does not scan an unbounded registration set. A tick-thread
caller encountering `TERMINATED` fails before acquiring the serialization primitive or mutating
state; it never executes the inline fallback. Operation coordinators and sequencer registrations
hold lifecycle producers until admission closure, sequencing publication, and terminal outcome
publication respectively, so no valid tick-thread transition can arrive after service
termination. Per C1: this executor
is the owning coordinator context for detached completion/history state only; it grants no
live-state ownership and no backpressure mutation outside the dispatcher contract.

**§3.6c Settlement/notification separation for admission completions (re-plan adjudication
2026-07-17 of the task-13 terminal corrective; CO-SIGNED r9 — r7 amendments 1–4 and the r8
ordering/isolation hardening applied; `.codex/arch-cosign-r9.msg`).**

The task-13 corrective loop proved a real tension: a single serialized completion executor
cannot both order state transitions and keep deadline expiry non-blockable while
`future.complete` runs synchronous user continuations. Resolution — two phases with
different guarantees:

1. **State settlement** (admission granted/rejected/expired/cancelled; permit accounting;
   waiter removal): executes under the backpressure accounting lock or equivalent atomic
   internal machinery. It NEVER runs user code, never completes futures, and is therefore
   non-blockable. Deadline expiry is a state settlement: once settled, the expiry is
   effective for every subsequent admission decision immediately, regardless of when its
   notification is delivered. Settlement order is the serialization that matters; it is
   total per region under the accounting lock.
2. **Notification delivery** (completing the acquire stages, invoking the rejection hook):
   after settlement, a delivery command is handed to the INJECTED completion sink — the
   backend-owned §3.6b completion service. Backpressure owns NO completion thread, no
   fallback drainer, no serialization primitive of its own. **Producer-fence enrollment
   (r7 amendment 1; ordering hardened by r8 amendment 2):** completion-service
   producer-lease acquisition is admission PREFLIGHT and occurs before
   `OperationCompletion.register` and before backpressure accounting. If the lease cannot
   be acquired because the service is `FLUSHING` or `TERMINATED`, the request fails
   synchronously before plan registration or any accounting side effect.

   **Obligation-scoped producer fencing (r11 Q1 — RESCINDS r10 Q2's coupling of the admission
   lease to plan-producer registration).** A plan's `PlanProducerToken` fences
   `OperationCompletion.register` and terminalization. It is acquired outside the owner-thread
   fast path and is independent of admission notification. `tryAcquire` is synchronous and
   produces no asynchronous delivery; it acquires no `AdmissionDeliveryLease`, touches no
   completion-service lock, allocates no notification stage, and starts no thread.
   `Optional.empty()` is a fast-path miss, not an admission rejection. The caller either enters
   the asynchronous path or terminalizes the already-registered plan through its plan producer.
   The asynchronous `acquire` and `acquireContinuation` paths lazily acquire one waiter-specific
   `AdmissionProducer` immediately before waiter accounting. Failure to acquire it rejects before
   waiter accounting; the already-registered plan is notified through its independent plan
   producer. A G-A1 call lacking a pre-acquired `PlanProducerToken` is ineligible and falls back
   before calling `tryAcquire`. **Ownership split:** task 13 owns only waiter-specific
   asynchronous producers; task 14 owns plan-producer preflight and registration. Once a plan is
   registered, its lease is guaranteed and every settlement, including lifecycle rejection,
   has a fenced delivery path to exactly one terminal notification. The lease remains
   registered through execution of its delivery command. During `FLUSHING`, delivery
   commands associated with existing leases remain accepted.
   Backpressure never invokes the post-termination inline fallback from a tick thread.
   Reaching `TERMINATED` with an outstanding admission-delivery lease is an invariant
   failure. Completion flush thereby accounts for outstanding deadline/cancellation
   notifications.
   **Notification isolation (r7 amendment 2):** the completion service's single control
   executor never directly invokes an externally observable future completion that can run
   arbitrary synchronous continuations on that control thread. It publishes immutable
   delivery outcomes through lifecycle-owned, isolated notification tasks. A blocked
   consumer continuation may delay only that consumer; it cannot stop other notifications,
   coordinator transitions, producer-fence quiescence, or shutdown flush. The notification
   mechanism remains part of the one backend-owned completion service.
   Delivery delay never blocks settlement or execution of subsequent admission decisions.
   All settled reservations remain visible to those decisions and may legitimately
   contribute to saturation until released (r7 amendment 4).
3. **Attempt identity and supersession (r7 amendment 3):** every admission request has an
   internal monotonic `admissionAttemptId`; its settlement and delivery command carry that
   identity. Delivery is idempotent per attempt and cannot affect another attempt.
   Re-acquiring a terminally rejected registered plan requires a new plan
   sequence/registration. If a granted attempt is cancelled before grant delivery, the
   undelivered grant is superseded by cancellation, its permit accounting is released
   exactly once, and delivery reports cancellation rather than exposing a closed permit.
   If grant was already delivered, cancellation follows the normal permit lifecycle. No
   public SPI change; the identity belongs to the internal waiter/delivery collaborator.
4. **Tick-thread settlement paths (REPLACED by r14 Q2 — the earlier wording was overbroad).**
   Admission-attempt settlement with an unpublished outcome enqueues that outcome through the
   attempt's live `AdmissionProducer`. **After successful grant publication, permit lifecycle
   transitions — `transfer`, `enter(FINALIZING)` and `close` — are pure, synchronous accounting
   operations and require no admission producer or delivery lease.** They complete no stage,
   invoke no callback, and publish no permit-lifecycle notification. If releasing capacity makes a
   queued waiter eligible, the resulting waiter work is submitted through **that waiter's own live
   `AdmissionProducer`**, never through the released producer of the permit causing the capacity
   change.
   These tick-thread accounting paths remain nonblocking under §1b: they **MUST NOT wait on
   `accountingLock` or enter completion serialization**. Their implementation must use
   owner-local/atomic accounting or an equivalent bounded no-wait handoff that guarantees
   exactly-once release. `close` remains idempotent. No permit-lifecycle transition directly
   notifies anyone; observable effects are limited to synchronous permit state, pulled pressure
   snapshots, and indirect completion of newly eligible waiters through those waiters' producers.
   **(r12 Q4, retained)** Tick-thread paths never acquire the post-termination serialization lock
   and never perform aggregate result construction.
5. The §3.4 backpressure SPI javadoc "completes on a FAWE executor" binds the DELIVERY
   phase; stage-completion threads are the completion service's notification tasks, per
   §3.6b.
6. **Admission-waiter drain hook (r11 Q2).** Every queued asynchronous waiter owns one
   `AdmissionProducer` with a bounded idempotent flush-expiry hook registered before the waiter
   enters accounting. At drain-deadline expiry the hook atomically settles that attempt only: it
   removes a queued waiter and reports lifecycle rejection, or supersedes a granted-but-undelivered
   permit with cancellation and releases its accounting exactly once. It then publishes the
   immutable outcome through the same producer. The hook performs no waiter-set scan, user
   callback, or future completion. The producer remains fenced until outcome publication, after
   which its timer and hook are cancelled and released. The resulting rejection terminalizes the
   associated registered plan as `NOT_ACCEPTED`; plan-key deduplication handles any concurrent
   plan-level drain terminalizer.
7. **Deadline scheduling (r11 Q3).** Admission deadlines are scheduled exclusively through the
   waiter's lifecycle-fenced `AdmissionProducer.schedule(...)`, backed by the completion service's
   dedicated retry timer. Backpressure must NOT use `CompletableFuture.delayedExecutor`,
   `ForkJoinPool.commonPool`, or any unfenced executor. Deadline firing first linearizes expiry
   through a nonblocking attempt-state transition, making the attempt ineligible for grant
   immediately; bounded accounting removal and immutable delivery submission then run through the
   completion-service control path. The timer thread never waits on the accounting lock and never
   runs delivery or user code. Cancellation or settlement cancels the retained timer handle.
   Backpressure owns no timer and no executor.
8. **Caller-supplied cancellation stage (r11 Q4).** A caller-supplied cancellation stage is an
   UNTRUSTED thread context. Backpressure attaches only an O(1), nonblocking hop whose inline body
   captures the cancellation outcome and submits an immutable cancellation command through the
   waiter's registered `AdmissionProducer`. Accounting settlement runs on the completion-service
   control path; notification uses isolated publication. No accounting lock, waiter scan, permit
   transition, hook invocation, future completion, or notification-task creation may run on the
   thread completing the supplied stage. The same rule applies when the stage is already complete
   at attachment.

Consequence for the frozen SPI: `FoliaBackpressure` implementations take the completion
sink as an injected collaborator (task-17 wiring; task-14 passes it through). No public
signature changes.

**§3.6d Producer collaborator contract (r13 — concrete signatures for the three r11 types;
clarification of r11, NO frozen SPI change: neither `OperationCompletion` nor `FoliaBackpressure`
changes).** Two service acquisitions only; `AdmissionDeliveryLease` is created ATOMICALLY with its
`AdmissionProducer` and has no independent acquisition — an independent one would recreate the
preflight gap r11 closed.

```java
public final class OperationCompletionService {
    public Optional<PlanProducerToken> tryAcquirePlanProducer(
            UUID operationId, long chunkKey, long planSequence,
            FlushExpiryHook ownerFlushExpiry, Consumer<Throwable> transitionFailure);

    public <T> Optional<AdmissionProducer<T>> tryAcquireAdmissionProducer(
            long admissionAttemptId, PlanProducerToken registeredPlan);

    public final class PlanProducerToken {
        public UUID operationId();
        public long chunkKey();
        public long planSequence();
        /** Queues the exact terminal record. True only when this call claims terminalization. */
        public boolean terminal(ChunkTerminalRecord record);
        /** Terminalizes as NOT_ACCEPTED with an empty receipt and the supplied cause. */
        public boolean rejectAdmission(Throwable cause);
    }

    public final class AdmissionProducer<T> {
        public AdmissionDeliveryLease<T> delivery();
        /** Installs waiter expiry + transition-failure settlement. Exactly once, BEFORE waiter
         *  accounting. Returns false when lifecycle expiry already won — accounting must not begin. */
        public boolean arm(FlushExpiryHook waiterFlushExpiry, Consumer<Throwable> transitionFailure);
        /** O(1), nonblocking submission to the control path. Takes no lifecycleLock. */
        public boolean submit(Runnable transition);
        /** The SOLE admission-deadline mechanism. */
        public ScheduledFuture<?> schedule(Duration delay, BooleanSupplier linearizeExpiry,
                Runnable expirySettlement);
        /** Orders plan NOT_ACCEPTED terminalization before exceptional notification publication. */
        public boolean reject(Throwable cause);
    }

    public final class AdmissionDeliveryLease<T> {
        public CompletionStage<T> future();
        /** Publishes the successful immutable outcome exactly once. */
        public boolean deliver(T immutableOutcome);
    }
}
```

`AdmissionDeliveryLease` has **no** `deliverExceptionally`: all failure delivery goes through
`AdmissionProducer.reject(...)`, which preserves plan-terminal ordering. The Folia path gains the
non-SPI overload `DefaultOperationCompletion.register(PlanProducerToken)`, which consumes the
token's registration capability and validates all three identity fields; the frozen
`OperationCompletion.register(long, long)` is unchanged. Task 14 hands the registered token to
task 13 through the internal `DefaultFoliaBackpressure.bind(PlanProducerToken)` adapter, which
still implements the frozen `FoliaBackpressure` unchanged — **no `ThreadLocal` and no
`operationId` lookup is permitted**.

*Lifecycle.* `PlanProducerToken`: acquired immediately before plan registration, outside every
tick-thread fast path; acquisition MAY take `lifecycleLock`; `ACCEPTING` registers producer +
`ownerFlushExpiry` atomically, `FLUSHING`/`TERMINATED` return empty without side effects;
`register(token)` consumes it exactly once and a live token stays consumable during `FLUSHING`
unless expiry won first; `terminal`/`rejectAdmission` are O(1), nonblocking, take no
`lifecycleLock`; the coordinator owns release after terminal processing and required persistence
settlement — task 14 never closes it; `TERMINATED` with a live token is an invariant failure.
`AdmissionProducer`: acquired ONLY by asynchronous `acquire`/`acquireContinuation`, immediately
before waiter accounting, and requires an already-consumed still-live token; acquisition may take
`lifecycleLock`, allocate the isolated stage and install an internal expiry trampoline but starts
NO thread; on `FLUSHING`/`TERMINATED` it returns empty and task 13 calls
`registeredPlan.rejectAdmission(cause)` since the plan is already registered; `arm` precedes
accounting and its internal trampoline guarantees no producer ever lacks a flush terminalizer;
`schedule` is legal only after `arm`, is clamped to the published drain deadline, and its timer
invokes only the O(1) `linearizeExpiry` before submitting the prebuilt `expirySettlement`;
`submit`, `reject` and timer firing take no `lifecycleLock` — this is the r11 Q4 cancellation hop;
release occurs only after one immutable outcome is published and cancels the retained timer and
hook; producers stay usable through `FLUSHING`, none may survive `TERMINATED`.
`AdmissionDeliveryLease`: `future()` is isolated and starts no thread; delivery is O(1),
nonblocking, valid during `FLUSHING`; release follows outcome publication, never consumer
continuation.

**Three-outcome CAS (r14 Q1).** `AdmissionProducer<T>` gains a third outcome entry:

```java
/** Publishes cancellation exceptionally WITHOUT terminalizing the associated plan as
 *  NOT_ACCEPTED. Competes with deliver(...) and reject(...) on the same attempt-level CAS. */
public boolean cancel(CancellationException cause);
```

Each admission attempt has one outcome CAS with three terminal outcomes: grant, rejection, or
cancellation. `AdmissionDeliveryLease.deliver(...)`, `AdmissionProducer.reject(...)` and
`AdmissionProducer.cancel(...)` compete on that CAS; exactly one returns `true`. "Granted but
undelivered" means backpressure state is `GRANTED` while this outcome CAS remains unclaimed.
Successful `deliver(...)` is the grant-delivery linearization point, even if isolated notification
or consumer observation occurs later. Plan consequences: `reject` winning terminalizes the plan
`NOT_ACCEPTED` and retry requires a NEW plan sequence; **`cancel` winning completes the admission
future exceptionally but does NOT terminalize the plan — the same registered plan sequence may
retry admission**; `deliver` winning means later cancellation follows the normal permit lifecycle.
If operation-level cancellation abandons the plan rather than retrying, task 14 explicitly
terminalizes it `CANCELLED_BEFORE_MUTATION` through `PlanProducerToken` — admission cancellation
never infers that decision. When cancellation supersedes a backpressure-level `GRANTED` state
before `deliver(...)`, task 13 releases the permit accounting exactly once before calling
`cancel(...)`.

*Plan/waiter ordering.* Identities stay distinct: plan dedup on `(operationId, chunkKey,
planSequence)`, admission-delivery dedup on `admissionAttemptId`. Waiter rejection order is:
(1) backpressure linearizes waiter/accounting settlement; (2) `AdmissionProducer.reject(cause)`
submits `registeredPlan.rejectAdmission(cause)`; (3) the same serialized command publishes the
exceptional admission outcome; (4) a concurrent plan-level drain terminalizer competes on the plan
key — the first plan terminal transition wins and the other is a duplicate that never decrements
completion accounting. A delayed rejection notification therefore cannot race a re-acquire under
the old plan sequence, because re-acquisition requires a new plan sequence.

**Unconsumed plan-token ownership (r16).** `PlanProducerToken` gains:

```java
/** Releases an unconsumed token without registering or terminalizing a plan and without
 *  publishing an outcome.
 *  @return true if this call changed ACQUIRED to ABORTED; false if abort or flush expiry already won
 *  @throws IllegalStateException if registration already consumed the token */
public boolean abort(Throwable cause);
```

Acquiring a `PlanProducerToken` creates a **linear obligation**: the holder MUST either transfer it
by a successful `DefaultOperationCompletion.register(token)` or abort it. `abort(cause)` atomically
cancels the unconsumed token's flush hook, deregisters its producer, and records the cause
diagnostically. It creates no plan registration, terminal record, or observable outcome.
**`register(token)` is transactional:** every validation or insertion failure before successful
ownership transfer MUST invoke `token.abort(failure)` before rethrowing — so the four rejection
paths clean up automatically and task 14 needs no exception-specific handling, though it MUST call
`abort(...)` if it elects not to call `register(...)` at all. Once consumed, the coordinator
exclusively owns release. Abort and flush expiry compete idempotently; exactly one deregisters.

*Abandoned tokens.* Abandonment without `register` or `abort` is an **invariant failure**, and must
be detectable as an unconsumed-token diagnostic/count. During `FLUSHING` the token stays temporarily
consumable; at the drain deadline its expiry hook records the abandonment, aborts it, and
deregisters the producer — so flush remains **bounded rather than hanging**. **No GC finalizer or
`Cleaner` may provide correctness.**

*Admission producers — the rule does NOT transfer unchanged.* There is **no**
`AdmissionProducer.abort(...)`. `arm(...) == false` means lifecycle expiry already owns the attempt:
the internal expiry trampoline MUST reject the associated registered plan as `NOT_ACCEPTED`, publish
the exceptional admission outcome, and release the producer — the caller performs no accounting and
needs no further cleanup call. Before `arm`, `AdmissionProducer.reject(Throwable)` is explicitly
legal and a caller hitting a setup failure after acquisition but before accounting MUST use it,
since the attempt already belongs to a registered plan and cannot disappear silently. Abandoning an
unarmed producer is an invariant failure; at flush expiry its trampoline force-rejects and releases
it while recording the unarmed-producer diagnostic.

*Amendment-3 boundary.* These collaborators neither change nor assume
`OperationCompletion.closeAdmission()`. If `tryAcquirePlanProducer` returns empty, no plan was
registered and these types provide NO aggregate pre-registration-cause channel; if A3.1 is signed,
task 14 passes that refusal cause through the amended contract. Until then that classification
remains the open user gate. The obsolete internal shapes `tryAcquireLease(..., registrationKey)`,
lease-coupled `registerProducer(...)` and `claimRegisteredProducer(...)` are REPLACED.

### 3.7 Engine behavior — lanes, slicing, interleaving, GET lifecycle

**Sliced region-sweep via dynamic lanes.** W0.2 ranked region-sweep first (4 tasks vs 8/16,
364 µs max task; visibility scheduler-delay-dominated ≈ 1 tick, equal across strategies). The
broker keeps one lane per observed region (internal `RegionKey`); a large operation does not
need one scheduling call per chunk. At drain time it obtains the actual current region, merges
mailboxes that now resolve to the same region, calls `ownsChunk` per candidate, processes owned
candidates, and rebinds unowned ones: **transfer the permit to the actual region's accounting
via `FoliaBackpressure.transfer`** (F4), retain the global accepted reservation, redispatch as
a discovery anchor. If transfer/migration cannot settle before the deadline, that plan
terminates **without mutation**; base-stamp validation is never waived by rebind (F6).

**Adaptive slice cap (reconciliation R7 — B's controller, C's liveness rationale).** The cap is
a per-tick commit-time budget, never a fixed chunk count: target owner time 750 µs; "do not
begin another chunk" threshold 1.5 ms; initial cap 8; min 1; max 32; every 32 slices grow
+2 cap / +125 µs target (≤32 / ≤1.5 ms) when backlog exists, schedule delay ≤ 1 tick + 5 ms,
and slice p95 < target; halve (floors 1 chunk / 250 µs) when schedule delay > 75 ms or a slice
exceeds 1.5 ms. The synchronous section/tile portion of one chunk is never split to meet the
timer. Controls use only that lane's queue depth, observed schedule delay, and measured
runtime — never global TPS (C6). Final numbers `[NEEDS-RUNTIME]` (W0.8/W0.10a).

**Interleaving (Proposal B §3.4).** Weighted deficit-round-robin per lane — Interactive 4 /
Normal 2 / Bulk 1 cost units; at most four consecutive chunks per operation before the next is
considered. Same-chunk tickets execute in registration order across all operations; disjoint
chunks may overtake; no global ordering implied. Finalizer continuations re-enter the same
mailbox under the same fairness policy but draw from reserved continuation capacity (F4).

**GET snapshot lifecycle (Proposal B §2 + F6 stamps).** Profiles: BLOCKS (section
palettes/ordinals, heightmaps, optional biomes) / BLOCKS_LIGHT (+ light nibbles) / FULL
(+ tile NBT, entity NBT/UUID/location, POI inputs). No live reference, no ticket, in any
snapshot (C2/F3). Capture: register the per-chunk `ChunkTicket`; await the preceding same-chunk
mutation ticket's terminal state; read `ChunkVersion(K)`; reserve snapshot bytes; single-flight
the operation-local cache by `(K, version, profile)`; owner callback (fresh ticket) revalidates
ownership, copies data, captures light nibbles including lazy `queueSectionData` creation,
captures entity state locally or via entity callbacks, **records the F6 stamps**, refreshes the
region hint; completes on the FAWE completion executor; workers lease via reference-counted
`SnapshotLease`. Cache: strong references while cached/leased; global `min(512 MiB, maxHeap/8)`,
per-op `min(128 MiB, global/4)`, ≤4,096 entries/op, ≤16 MiB single snapshot; weighted-LRU
eviction of zero-lease entries; superset profile may satisfy a subset, never silently widened.
Invalidation: `ChunkVersion(K)` bumped on the owner at first effective mutation; older entries
leave lookup; live leases stay valid as point-in-time data; world unload bumps the load epoch,
invalidates all entries, and completes in-progress captures exceptionally. A later mutating op
cannot capture before the preceding same-chunk ticket terminates.

### 3.8 Config-gated latency grafts (Proposal A; F7/F8-constrained)

Each defaults conservatively, falls back to the safe/throughput path, and is `[NEEDS-RUNTIME]`.

- **G-A1 — Tier-1 owner-inline fast path.** Builds on the dispatcher's explicit inline
  guarantee (§3.3). **Eligibility (F7 — ALL required, checked before any side effect):**
  1. the same-chunk sequencer proves **no predecessor** ticket for the chunk;
  2. an owner-only permit is acquired **immediately via `tryAcquire`** — never awaited;
  3. the required `PREPARED` history boundary is **already satisfied without tick-thread I/O**
     (in-memory history, or a pre-satisfied durable boundary);
  4. preparation is **framework-owned, callback-free, and bounded** (within the inline budget,
     default ~2 ms, configurable).
  Pattern, mask, transform, custom-extent, persistence, and arbitrary plugin callbacks **never
  execute through G-A1** (w06: caller-supplied callback code keeps its contracted thread).
  Any condition failing → the operation enters the normal detached lane before any side effect.
  The inline path still runs as a normal `onRegion` callback (ticket minted/retired) and still
  registers with the completion sink before executing. **Config:** default on for brushes/wands,
  off for `//` bulk. `[NR-A]` predicate hot-path cost; `[NR-F]` inline budget vs tick-time
  variance (concession trigger).
- **G-A2 — viewer-near commit ordering within a lane drain.** Orders commits inside a drain by
  minimum distance to any tracked viewer, **within** a DRR priority class; may cause an earlier
  yield but may not bypass same-chunk ordering or DRR fairness. **Config:** gated. `[NR-D]`
  tracked-viewer read on the region thread (W0.2 had `tracked_viewer=false`).
- **G-A3 — early block packet (phase 7).** Sends the block-visibility packet before relight,
  with the required follow-up light packet at phase 10 — inverting `DELAY_PACKET_SENDING`,
  which remains the default. Costs up to double chunk-packet bandwidth on lit edits and a brief
  stale-light window. Never changes the terminal order: phases 8–12 still gate the terminal
  record (F8). **Config:** gated. `[NR-E]` cross-region send client delivery (W0.2
  `client_ack=pending`), else entity-mailbox fan-out; `[NR-G]` dual-packet p99/bandwidth.

**`[NEEDS-RUNTIME]` register:** `[NR-A]` ownership-predicate hot-path cost · `[NR-B]` lane wake
latency under uniform arrival · `[NR-C]` adaptive slice cap numbers · `[NR-D]` tracked-viewer
read (G-A2) · `[NR-E]` cross-region send delivery (G-A3) · `[NR-F]` inline budget vs tick-time
variance (G-A1) · `[NR-G]` dual-packet p99/bandwidth · `[NR-H]` terminal completion failure
injection (exactly-once, matching history) · `[NR-I]` async chunk-load continuation thread
(re-dispatch regardless) · `[NR-J]` base-stamp fingerprint cost on capture/commit (F6). None
gate the mechanism GO (W0.2); all are W0.8/W0.10a/certification inputs.

## 4. Conventions

- FAWE upstream style: 4-space indent, existing header (HEADER.txt), existing package
  idioms; English everywhere; no FQN inline (imports).
- The Folia module uses its Java 25 toolchain (§2); core stays Java-21 output.
- Logging via existing FAWE `LogManagerCompat` loggers; operator-facing degradation
  messages are explicit and documented (spec §5).
- Commits: Conventional Commits, no attribution footers, no process context.
- Paper-path edits: minimal, marked with a `// Folia port:` comment ONLY where a seam
  replaces an existing call (grep-able inventory of touch-points).

## 5. Exclusive resources (single-writer)

- `harness/` runtime dirs + Folia test servers (ports 25601/25602 for 26.1.1/26.1.2): owned
  by the harness task, then by wave-certification runs — never two concurrent server runs
  on one port.
- `adapter-26.1` source tree: one writer per wave.
- `worldedit-bukkit` main-module bootstrap files (`WorldEditPlugin`, `FaweBukkit`) and the new
  `worldedit-bukkit/folia` module: one writer per wave.
- Gradle build files: orchestrator-owned (workers propose diffs in their task file).
- Codex implementation thread: one persistent thread, dispatched serially by the
  orchestrator (never two concurrent tasks on the same thread).

## 6. Reference artifacts

- `spikes/recon-queue-threading.md` — queue/threading map (hotspot inventory; C5 site list).
- `spikes/recon-adapters-nms.md` — NMS danger-zone table (§6 = C1's checklist; the ~13
  live-state methods that gain a `RegionTicket` parameter).
- `spikes/recon-editsession-paths.md` — command/history/EditSession paths.
- `spikes/w02-pipeline-results.md` — the regionized-write pipeline spike; every strategy/latency
  number and the mandatory non-owner-callback re-dispatch finding cite it.
- `spikes/w06-api-context-audit.md` — the §4c APIC table (382 declarations);
  `spikes/w06b-apic-gap-closure.md` — the F9 gap closure (271 rows; §7). Combined census: 653.
- `spikes/w08-perf-budgets.md` — §8 budget structure + the G1–G8 measurement-gap register.
- `amendment-1-draft.md` — A1.1–A1.4 (user-signed; Codex co-sign rides this architecture round).
  A1.4 as extended by r1 F8 covers FAWE shared operation state, and the cross-region
  neighbor-ordering weakening (INV-DEG-006) must land there, not here.
- `spikes/tournament/proposal-{A,B,C}-*.md` + `spikes/tournament/judgment.md` — tournament
  entries, ruling, semantic-diff provenance (F11).
- `codex-arch-cosign-r1.md` / `codex-arch-cosign-r2.md` — the co-sign findings applied by v3
  and v3.1 respectively.
- SS2 precedent (patterns only, different repo): PlatformScheduler SPI, adoption mode,
  harness/gates, migration-record discipline, `Hooks/Folia` dedicated-module build shape.

## 7. Freeze-gate record (F9 closed; F10 producers owned by parallel workers)

**F9 — CLOSED.** `spikes/w06b-apic-gap-closure.md` adds 271 APIC rows: 10 QueueHandler
declarations, 257 source declarations across 24 Extent-derived types, and 4 Fawe UUID-executor
declarations. All are PRESERVED with deterministic context derivation. Combined §4c census:
653 declarations.

**F10 — §8 seam and budget producers (G1–G8, w08 §5).** The architecture-side seams are frozen
(TaskKind timers §3.3, diagnostic snapshot producer §3.4, lifecycle drain §3.3, terminal timing
§3.6); harness implementation is delivered (G1–G8 green 2026-07-17); numeric budget values are
**W1-exit gates per Amendment A2.3 (no wave-2 dispatch before the signed numeric freeze), not
architecture co-signature gates** (r2). Each signal's named producer, wired before wave-1
dispatch by the parallel harness/orchestrator workers:
- **G1** operation driver emitting `FAWE_PERF` at the §4d completion point — sourced from
  `OperationCompletion` terminal timing (§3.6 seam);
- **G2** `@mark` directive + `perf-report.sh` (harness worker);
- **G3** per-region schedule/slice/phase timers — sourced from the dispatcher/broker `TaskKind`
  instrumentation (§3.3/§3.7 seams);
- **G4** queue depth / outstanding futures/tickets/chunks — sourced from the frozen
  `DiagnosticSnapshot` producer on `FoliaCommitBroker` (§3.4), emitted per the exact
  `FAWE_QUEUE depth/inflight/outstanding/region` mapping defined there;
- **G5** RSS/GC samplers, **G6** shutdown drain via `@mark` + the `drain` report (§3.3
  lifecycle seam), **G7** staged 26.1.1 runtime, **G8** `@restart` cross-restart undo —
  harness/orchestrator work per w08.

Every numeric slot in w08 §6 must be populated before wave 1 dispatch (spec §8: budgets frozen
before core implementation).
