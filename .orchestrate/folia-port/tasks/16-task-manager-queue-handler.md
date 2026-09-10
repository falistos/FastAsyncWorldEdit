# Task 16 — FoliaTaskManager + FoliaQueueHandler (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** task 10 (FaweThreadContext resolver — ownership predicates), task 12
  (FoliaRegionDispatcher — the routing target of the context-carrying surface). Task 13
  (backpressure) consumed indirectly via the dispatcher.
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: queue pipeline /
  scheduler SPI. Double-reviewer (concurrency & thread-ownership) per spec §6.

## Mandate
Make the two platform service implementations in the folia module real: `FoliaTaskManager`
(FAWE `TaskManager` on Folia's three schedulers) and `FoliaQueueHandler` (QueueHandler without
the global sync-drain tick, routing the §3.5 context-carrying internal surface to the
dispatcher). These are the seams through which ALL legacy FAWE scheduling flows on Folia.

## In scope
- `FoliaTaskManager` (folia module): implement the 7 skeleton methods against Folia's
  schedulers — `GlobalRegionScheduler` (task/repeat/later without a location context),
  `AsyncScheduler` (async/repeatAsync/laterAsync), `EntityScheduler`/`RegionScheduler` routing
  where the frozen APIC disposition rows map a call to an owned context. Tick-interval semantics:
  Folia scheduler delays are in ticks like Bukkit — preserve the `interval`/`delay` contract of
  the base class exactly. **CORRECTION (2026-07-20, orchestrator task-writing error, found by the
  task-16 routing review — same class as FD-1):** earlier drafts of this file cited an
  "architecture §3.5 C4 scheduler mapping table". **No such table exists** — C4 is *Operation
  completion* (`architecture.md:187`) and §3.5 has no scheduler table. The binding artifacts are
  the frozen APIC rows **APIC-019–028** and **APIC-092–101** in `spikes/w06-api-context-audit.md`
  and `spikes/w06b-apic-gap-closure.md`. The delivering worker made exactly this substitution and
  recorded it; that substitution is CORRECT and is now the binding reference.
- Task-id/cancellation bridge: `TaskManager.cancel(int)` must remain functional — maintain the
  id→`ScheduledTask` mapping internally (Folia returns handles, not ints). Bounded map,
  cleaned on completion (H-LEAK).
- `FoliaQueueHandler` (folia module): implement `startUnsafe`/`endUnsafe` WITHOUT the global
  sync-drain tick (architecture §2 strikes it); route submission through the dispatcher's
  context-carrying surface (§3.5). MUST NOT touch AsyncCatcher/physicsFreeze (§1b; W0.5:
  physicsFreeze is orphaned dead code on the Folia path).
- Honor the QueueHandler binary-compat constraint from the §4c audit (w06: QueueHandler's 10
  public sync/async methods are PRESERVED) — the Folia subclass overrides behavior, never
  signature.
- Async-first (user conventions): IO/queue work lands on `AsyncScheduler` or FAWE pool, never
  on a region tick thread; sync joins only where the base contract demands one.

## ADDED SCOPE — C6: the struck drain's time budget is currently ORPHANED (2026-07-20)

The routing review found that striking the global sync-drain tick removed not just the loop but
its **time budget**, and nothing replaced it. The old drain executed sync work inside an adaptive
5–50 ms TPS-aware slice via `QueueHandler.getAllocate()`. On Folia every `sync` now becomes its own
unbudgeted global-region task and they all execute in one tick; `getAllocate()` is dead code.

This is squarely a **C6** obligation — C6 names `QueueHandler.getAllocate()` explicitly and requires
a Folia equivalent keyed on the target region — and C6 was missing from this task's binding
references (orchestrator error, now corrected above). The reviewer correctly noted it is not a
defect against the brief as written, and that it appears in neither the dev record's Deviations nor
its Attack Points, i.e. **nobody owned it**. This task owns it now: `FoliaQueueHandler` is the
Folia `QueueHandler`.

Deliver a per-region-keyed budget equivalent, or STOP and escalate NEEDS_CONTEXT with a concrete
proposal if the right budget cannot be derived without the task-14 slice controller. Do not leave
it unbudgeted and unrecorded — an unbounded number of unbudgeted global tasks in a single tick is
exactly the C6 failure mode.

## ADDED SCOPE — architecture ruling r12 (2026-07-20, after the initial delivery)

The task-15 double review found that three ownership guards are **inert**: their false branch
funnels into location-free `TaskManager.sync`, whose short-circuit runs the payload inline on the
non-owning region thread the guard just excluded. r12 Q1 ruled (new §3.5 paragraph, binding):
*an ownership predicate is authorization, not routing; the false branch MUST route through the
target-bearing `syncOn`/ticketed dispatcher and MUST NOT fall back to location-free `sync`/
`syncWhenFree`; a wrong-owner callback fails before side effects if its target-bearing route is
unavailable.*

**This task owns the final target-bearing routes.** Task 15 has WITHHELD the three guards and
recorded them as blocked on this task; task 15 cannot close until these land:

1. `LazyBaseEntity` — an entity-target route so a non-owning caller reaches
   `mcEntity.save(output)` through the owning entity context, never inline.
2. `PaperweightFaweWorldNativeAccess` cached multi-chunk flush — a chunk-target route for
   `setBlockState(...)` + `sendChunk(...)` covering EVERY cached target, not an anchor chunk.
3. `NMSAdapter`'s World overload — its two branches currently run the identical live-section CAS,
   so its `ownsChunk` gates nothing; the non-owning branch needs a real target-bearing route.

Additionally, per the r12 C5 perimeter widening: `BukkitBlockCommandSender.java:194` guards live
chunk access with `Bukkit.isPrimaryThread()` and falls back to the legacy `BukkitScheduler`, which
**C1 bans outright**. Replace that fallback with target-bearing dispatch. Task 15 records the
site; the fix is this task's.

**DEFINITION OF DONE — remove the C5 allowances you unblock.** Task 15's corrective RESTORED the
withheld sites to their legacy predicate and registered each one as an exact-line, counted
allowance in `build-logic/src/main/kotlin/buildlogic.common.gradle.kts` (`c5Allowances`). Those
entries are the visible marker of a deferred obligation. When you land a target-bearing route for
a site, you MUST delete its allowance entry in the same change. An allowance that outlives its
reason silently converts "temporarily withheld" into "permanently legacy" — which is precisely the
failure mode the guard exists to prevent. Sites you unblock here:
`LazyBaseEntity.java`, `NMSAdapter.java`, `PaperweightFaweWorldNativeAccess.java` (count 2),
`BukkitBlockCommandSender.java`. Leaving one in place is an escalation, not a default.

## Out of scope
- Bootstrap registration/selection of these implementations (task 17).
- The chunk GET/SET pipeline itself (wave 2) — `FoliaQueueHandler` routes, it does not commit.
- Any `Fawe.isMainThread()` call-site migration (task 15).

## Binding references
- architecture.md §2 (platform services in the folia module; sync-drain tick struck), §3.5
  (context-carrying internal surface; the binding routing rows are APIC-019–028 / APIC-092–101,
  NOT a "C4 table" — see the correction above), §1b (no global toggles), and **C6 (added
  2026-07-20 — it was missing from this list, a second orchestrator error in this file)**.
- spec §4c via `spikes/w06-api-context-audit.md` (QueueHandler rows: 10 public methods
  PRESERVED) and `spikes/w06b-apic-gap-closure.md`.
- In-tree skeletons: `worldedit-bukkit/folia/.../FoliaTaskManager.java` (7 methods),
  `FoliaQueueHandler.java` (startUnsafe/endUnsafe). Base classes:
  `worldedit-core/.../util/TaskManager.java`,
  `worldedit-core/.../queue/implementation/QueueHandler.java` (NOTE: carries a small
  uncommitted wave-0 modification — read the current tree, not upstream).
- `spikes/recon-queue-threading.md` (legacy scheduling inventory).

## Files to touch
- `worldedit-bukkit/folia/.../FoliaTaskManager.java` — implement (folia-api allowed here).
- `worldedit-bukkit/folia/.../FoliaQueueHandler.java` — implement.
- New folia-module helpers if needed (id bridge, scheduler adapters) — same package.

## Executor constraints
- Java 25 toolchain in the folia module; folia-api types NEVER escape the module's public
  surface into core/bukkit (architecture F1/§2).
- Gradle always `--no-configure-on-demand`. No git commits. No FQN inline. English.

## Escape hatch
If an APIC routing row needs a region context that the call site cannot supply (no location/entity at
hand), do NOT guess a region: escalate NEEDS_CONTEXT with the call-site list — architecture
§3.5 decides between global-region routing and API-context rejection per row.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- No global sync-drain: grep-able absence of the struck drain loop; no Bukkit
  `BukkitScheduler` usage anywhere in the folia module.
- `cancel(int)` round-trip works for every submit shape; the id map drains (H-LEAK).
- Runtime confirmation deferred to harness after task 17 wiring:
  `JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
  harness/scenario.sh smoke-set --version 26.1.2` (26.1.2 only; 26.1.1 does not exist).

## Test / verification strategy
Orchestrator runs the graph compile proof. Scheduler-routing deterministic tests (which
scheduler receives which call shape) are in-scope per spec §4 "targeted deterministic tests" —
implement them as plain JUnit against a scheduler-recording fake, no server required.

---
## Dev record (worker fills this in on completion)

- **Status:** DONE_WITH_CONCERNS
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaTaskManager.java:57`
    — scheduler routing, inherited context rows, bounded ID bridge, owner waits, and lifecycle cancellation.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaQueueHandler.java:32`
    — preserved QueueHandler surface, direct dispatcher routing, when-free priority, and unsafe rejection.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/FoliaTaskManagerTest.java:29`
    — scheduler, interval, cancellation, leak-bound, inherited-context, wait, and parallel tests.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/FoliaQueueHandlerTest.java:35`
    — descriptor, target-routing, priority, cancellation, failure, no-drain, and unsafe tests.
- **C4 / frozen API mapping, row by row:**
  - APIC-019: `repeatAsync`, `async`, and `laterAsync` route to `AsyncScheduler`; tick values are converted
    to 50 ms units and repeating IDs remain cancellable (`FoliaTaskManager.java:153`).
  - APIC-020: location-free `repeat`, `task`, and `later` route to `GlobalRegionScheduler`; the inherited
    QueueHandler drain registration alone returns the struck `-1` sentinel (`FoliaTaskManager.java:132`).
  - APIC-021: inherited `taskNow(..., async)` retains its explicit branch; `taskNowAsync` offloads only a
    tick-thread caller and otherwise stays in the current caller context (`FoliaTaskManager.java:234`).
  - APIC-022: `taskNowMain`, `taskSoonMain`, and `taskWhenFree` run inline on any tick context, use G off
    tick, and use A for the explicit async branch (`FoliaTaskManager.java:224`).
  - APIC-023: positive IDs map to `ScheduledTask`; `cancel(int)` is nonblocking, bounded to 65,536 live
    handles, race-safe before handle attachment, and releases capacity (`FoliaTaskManager.java:213`).
  - APIC-024: inherited `objectTask` begins through `task` and fragments through `later`, so every fragment
    and its completion callback target G (`FoliaTaskManager.java:178`, `FoliaTaskManager.java:187`).
  - APIC-025: both `parallel` overloads reject tick callers; W execution uses interruptible `FutureTask`s,
    one configured deadline, interrupt restoration, and aggregate failures (`FoliaTaskManager.java:284`).
  - APIC-026: `wait` rejects tick callers, uses its explicit bounded timeout, and restores interruption;
    inherited `notify` remains caller-local and nonblocking (`FoliaTaskManager.java:311`).
  - APIC-027: synchronous TaskManager callbacks run inline in R/E/G and otherwise submit to G with the
    configured owner-wait timeout and cancellation on timeout/interruption (`FoliaTaskManager.java:259`).
  - APIC-028: inherited `runUnsafe` reaches `startUnsafe`, which throws before the callback; no global
    safety switch is changed (`FoliaQueueHandler.java:140`).
  - APIC-092–094: the three public async descriptors are inherited verbatim and stay on QueueHandler's
    detached FAWE secondary pool (`QueueHandler.java:214`; inheritance is pinned by the descriptor test).
  - APIC-095–097: the three location-free sync descriptors run inline in R/E/G and otherwise submit to G;
    cancellation claims before execution and failures complete once (`FoliaQueueHandler.java:47`).
  - APIC-098–101: all four `syncWhenFree` descriptors use the same derived owner. Off tick, one deferred G
    turn gives already-queued normal sync work a head start, but is not strict target-local priority; tick
    callers remain eligible for inline execution (`FoliaQueueHandler.java:115`).
  - §3.5 chunk-call and chunk-task rows route their exact world/chunk to `onRegion`; the entity row routes
    its exact entity to `onEntity`; the global row routes to `onGlobal` (`FoliaQueueHandler.java:98`).
- **Deviations:**
  - The task text names a "§3.5 C4 scheduler mapping table", but the current architecture's C4 is operation
    completion and §3.5 has no scheduler table. The frozen APIC-019–028 and APIC-092–101 tables are the
    available row contracts and were discharged above.
  - Gradle graph compilation and the runtime harness were not run, as directed by the orchestrator. Direct
    Java 25 compilation against the pre-built core classes and deterministic no-server tests pass.
  - No mapped row requests a sub-tick entity delay. The context-carrying entity row inherits task 12's
    documented `EntityScheduler.execute` one-tick minimum; no synthetic zero-delay behavior was added here.
- **Attack points:**
  - Folia exposes no task-completion listener. One-shot completion, bridge cancel, and immediate terminal
    returns remove their entries; failed repeating invocations remain tracked because Folia reschedules them.
    A bounded reaper rotates external terminal states; task 17 must still call `cancelAll()` before shutdown.
  - The struck drain registration depends on `FoliaTaskManager` being installed before constructing
    `FoliaQueueHandler`; task 17 owns and must test that bootstrap order.
  - The location-free G fallback remains legal only for global-safe callbacks. Location-bearing internal
    callers must use the §3.5 `ChunkTarget`/`EntityTarget` surface.
  - The one-turn `syncWhenFree` deferral is not strict priority and adds one global tick off thread. Strict
    priority and C6 budgeting need the task-14 target-local lane/slice controller; see Corrective 1.
- **Escalation:** AUTHORIZED — no scheduler row lacked required context. Opaque location-free rows have the
  frozen G fallback, and all chunk/entity rows arrive through an exact context-carrying target. No C4/APIC
  row was escalated as NEEDS_CONTEXT.

### Corrective 1 (2026-07-20)

- **Status:** BLOCKED — corrective code is complete; C6 and strict target-local priority require the
  task-14 lane/slice surface described under Escalation.
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaTaskManager.java:170`
    — repeating failure lifecycle, constant-cost reaping, waiter cancellation, and context guard.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaQueueHandler.java:35`
    — bounded dispatch futures, explicit one-turn semantics, and fail-closed scheduler derivation.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaTickThreadGuard.java:10`
    — eager context resolution and platform-predicate agreement check.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/FoliaTaskManagerTest.java:93`
    — Folia repeat semantics, constant-cost reaping, shutdown release, and cross-thread attach/cancel tests.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/FoliaQueueHandlerTest.java:42`
    — exact descriptor ownership/throws, deadline, and concurrent cancel/claim tests.
- **Point-by-point disposition:**
  1. **FIXED.** A throwing repeating callback remains registered and cancellable because Folia reschedules it.
     Only one-shot callbacks complete their registration; explicit cancellation remains exactly once.
  2. **FIXED.** Submission no longer scans the map. A constant one-candidate rotation maintains a bounded
     reaper ring; saturation checks at most 32 candidates before deterministic rejection and retry.
  3. **FIXED.** Queue futures use the configured owner-wait timeout and suppress callbacks if timeout wins.
     `cancelAll()` now cancels the private `awaitGlobal` future, releasing its worker immediately on shutdown.
  4. **DEVIATION RECORDED.** The existing extra G turn is only an ordering bias for already-queued work, not
     strict priority. All four override javadocs and the original record now state the weaker guarantee.
  5. **NEEDS_CONTEXT.** No target-region identity or adaptive slice controller is exposed to these classes;
     the concrete task-14 integration proposal is under Escalation. Global sync remains unbudgeted meanwhile.
  6. **FIXED IN THIS SEAM.** Production construction eagerly requires a registered context. Every scheduler
     derivation/wait cross-checks it against Folia's platform tick predicate and fails before submission on
     disagreement. Task 17 still owns the Folia implementation, registration, and bootstrap-order test.
  7. **FIXED.** Tests now use latches/atomics/volatile publication for real cross-thread handoffs. A worker
     dispatcher exercises cancel-before-claim and cancel-after-claim; a scheduler worker exercises
     cancel-before-attach and proves exactly one permit returns. Reflection pins declaring class and checked
     exceptions, so an async override or throws drift fails the descriptor test.
- **Folia fake semantics:** `FakeScheduledTask` now catches callback failures as Folia does. A failed repeating
  invocation transitions `RUNNING → IDLE` and may fire again; a one-shot transitions to `FINISHED`; cancellation
  during execution remains `CANCELLED_RUNNING`. The production test asserts the repeat stays tracked across two
  failed invocations and is then cancelled by its original ID. It no longer models Bukkit termination.
- **Deviations:**
  - Strict APIC-098–101 priority and the C6 time budget are not implementable through the frozen dispatcher
    methods currently available to task 16. The one-turn compatibility bias remains explicit, not overclaimed.
  - The r12 target-bearing routes newly added to this task file were read and intentionally not started; the
    corrective brief reserves them for a separate round after task 15's corrective settles.
  - Gradle and the runtime harness remain orchestrator-owned. Verification uses Java 25 against the pre-built
    core output and the deterministic test runner.
- **Attack points:**
  - `Bukkit.isPrimaryThread()` is a fail-closed second opinion, not the Folia context implementation. Task 17
    must register that implementation before either service is constructed and retain it through shutdown.
  - External scheduler-wide cancellation has no completion listener. The bounded candidate ring reclaims such
    terminal handles under later submissions; orderly shutdown must invoke `cancelAll()` first.
  - A timed-out callback already running on an owner thread cannot be interrupted safely. Its Future unblocks,
    while the owner callback is allowed to finish; a timeout that wins before claim prevents all side effects.
- **Escalation:** NEEDS_CONTEXT — co-sign a package-private target-lane scheduler owned by task 14 and wired by
  task 17. It should accept exact global/chunk/entity targets plus `NORMAL`/`WHEN_FREE`, derive the live
  `RegionKey` only inside the dispatcher/broker, enqueue normal work ahead of when-free work, and apply the R7
  lane-local adaptive slice controller before dispatching each callback under its own fresh ticket. Task 16 can
  then route all seven sync descriptors and four `syncOn` rows through that seam, discharging strict priority
  and C6 together without guessing a region or reintroducing the struck repeating global drain.

### Corrective 2 (2026-07-20)

- **Status:** BLOCKED — C6 and strict `syncWhenFree` priority are implemented through task 14's broker.
  The four r12 caller migrations remain `NEEDS_CONTEXT`: the frozen target-bearing surface is protected and
  two current call-site shapes do not carry the target named by the mandate.
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaQueueHandler.java:31`
    — broker-backed global/chunk/entity scheduling, lane priority, and adaptive-slice participation.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/FoliaQueueHandlerTest.java:104`
    — concurrent priority, integrated four-of-ten slice, exact target execution, and fail-before-side-effect
    tests.
  - `.orchestrate/folia-port/tasks/16-task-manager-queue-handler.md:273` — this record.
- **C6 / priority disposition:**
  - All seven off-tick public `sync`/`syncWhenFree` shapes enter `scheduleSyncGlobal`; normal work uses
    `SyncPriority.NORMAL`, when-free work uses `SyncPriority.WHEN_FREE` (`FoliaQueueHandler.java:245-275`).
  - All four protected context-carrying rows enter the matching broker seam with `NORMAL`: chunk call,
    chunk task, entity task, and global task (`FoliaQueueHandler.java:178-211`).
  - Therefore queued NORMAL work is polled before WHEN_FREE in the same lane, including a normal submission
    made after the when-free lane has been armed but before its drain claims work. Both priorities share the
    broker's lane-local adaptive clock/cap; no global TPS or `MinecraftServer.currentTick` signal is read.
  - Integrated global and chunk-target tests advance their lane clock by 200 microseconds per callback and
    require exactly four of ten callbacks in the first 750-microsecond slice. Direct/deferred-unmeasured
    execution runs ten and fails (`FoliaQueueHandlerTest.java:128-181`). The concurrent test requires
    `normal, when-free`; the old one-turn gate produces `when-free, normal` and fails
    (`FoliaQueueHandlerTest.java:104-125`).
- **r12 site disposition:**
  - `LazyBaseEntity`: NOT RE-ROUTED. It stores only `Supplier<LinCompoundTag>`; the originating entity captured
    by `PaperweightFaweAdapter` is not present in this class, so an `EntityTarget` cannot be constructed here.
  - `NMSAdapter`: NOT RE-ROUTED. The current tree has only the `(String worldName, IntPair, ...)` overload;
    the mandate's target-bearing `World` overload does not exist, and adapter-26.1 still calls the string form.
  - `PaperweightFaweWorldNativeAccess`: NOT RE-ROUTED. It carries every chunk coordinate and could split the
    flush per target, but it cannot invoke the protected `QueueHandler.syncOn(ChunkTarget, ...)` surface.
    No anchor-chunk approximation was made.
  - `BukkitBlockCommandSender`: NOT RE-ROUTED. It carries the command block chunk, but likewise has no legal
    caller path to the protected target-bearing surface. The banned scheduler fallback therefore remains
    visibly allowlisted rather than being replaced with another location-free route.
- **C5 allowances deleted:** NONE. The exact entries for `LazyBaseEntity`, `NMSAdapter`,
  `PaperweightFaweWorldNativeAccess` (count 2), and `BukkitBlockCommandSender` remain because none of their
  routes can honestly be discharged. The `FoliaTickThreadGuard` allowance remains task 17's as directed.
- **What task 15 may close:** no withheld ownership row yet. Task 15 can treat task 16's C6/priority dependency
  as closed, but rows 16, 27, 31, 32, and 33 in `c5-requalification-record.md` remain blocked on the routing
  seam and target propagation below.
- **Deviations:**
  - The dispatcher-only compatibility constructors deliberately leave target lanes unwired; any off-tick or
    target-bearing submission fails before its payload. Task 17 must inject the fully wired task-14 broker
    through the broker constructor (`FoliaQueueHandler.java:35-109,288-292`). This avoids using task 14's
    explicitly diagnostics-only two-argument broker constructor for lane work.
  - The four authorized external files and their allowances were deliberately not edited. Removing a legacy
    predicate without a callable target route would recreate the inert-guard defect r12 forbids.
  - Gradle and the graph compile were not run. Direct Java 25 compilation against the pre-built core/Bukkit
    outputs and deterministic no-server tests passed.
- **Attack points:**
  - Task 17 must select the broker-injected constructor. Selecting a dispatcher-only compatibility constructor
    leaves target discovery unavailable by design; `unavailableTargetLaneFailsBeforeThePayloadRuns` proves the
    callback has zero side effects (`FoliaQueueHandlerTest.java:320-340`).
  - `PaperweightFaweWorldNativeAccess.flush` requires one scheduled unit per distinct cached `ChunkTarget` (or
    a broker API accepting the complete set). One anchor cannot authorize the remaining chunks.
  - `LazyBaseEntity` needs the source entity propagated from `PaperweightFaweAdapter.getEntity`; `NMSAdapter`
    needs the WorldEdit `World` propagated through `PaperweightPlatformAdapter.setSectionAtomic` and its
    adapter-26.1 callers. Neither target can be recovered from the data currently present at the guarded site.
- **Escalation:** NEEDS_CONTEXT — co-sign and authorize a caller-accessible core target-routing façade over the
  frozen protected `QueueHandler.syncOn` methods (or widen those methods), with a Paper implementation and a
  Folia implementation backed by `FoliaCommitBroker`. Also authorize target propagation through
  `PaperweightFaweAdapter.getEntity` for `LazyBaseEntity` and through adapter-26.1's
  `PaperweightPlatformAdapter.setSectionAtomic` callers for `NMSAdapter`. Once those exact seams exist, this
  task can requalify the four predicates, split the cached flush by every target, delete all five counted C5
  allowances, and unblock task 15 without reflection, inferred regions, or location-free fallback.

### Corrective 3 (2026-07-20)

- **Status:** DONE — all r17 routes are implemented, every named temporary C5 allowance is removed, and the
  final independent specification and standards re-reviews both pass.
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/QueueHandlerRouting.java:20`
    — final `@ApiStatus.Internal` / `INTERNAL-NONAPI` facade exposing the five frozen r17 operations without
    exposing a queue handler, dispatcher, Folia type, or new `TaskManager` entry point.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/queue/implementation/QueueHandler.java:110`
    — Paper target-route fallback with bootstrap-injected sole `TicketAuthority`, fresh ticket retirement,
    and cancellation-before-claim suppression; all target methods remain protected.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/entity/LazyBaseEntity.java:35`
    — additive `EntityTarget` constructor, exact-ticket serialization, wrong-owner tick rejection before
    dispatch, bounded A/W wait, and cancellation after an unclaimed wait failure.
  - `worldedit-bukkit/adapters/adapter-26.1/src/main/java/com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_1/`
    `PaperweightFaweAdapter.java:371` — adapter-26.1 supplies the exact adapted entity target.
  - `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/NMSAdapter.java:178` — asynchronous
    World overload with exactly one CAS inside the asserted chunk ticket; the String overload first checks
    backend policy and retains Paper's global and worker lock/CAS paths.
  - `worldedit-bukkit/adapters/adapter-26.1/src/main/java/com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_1/`
    `PaperweightPlatformAdapter.java:230` and `PaperweightGetBlocks.java:424` — World forwarding and composed,
    bounded section stages; the three callers never block a tick thread.
  - `worldedit-bukkit/adapters/adapter-26.1/src/main/java/com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_1/`
    `ChunkTargetPartitions.java:40` and `PaperweightFaweWorldNativeAccess.java:275` — immutable per-target
    partitioning and one facade dispatch per exact `ChunkTarget`; synchronous A/W flush waits are bounded.
  - `worldedit-bukkit/src/main/java/com/sk89q/worldedit/bukkit/BukkitBlockCommandSender.java:172` — command
    block liveness probe routes through its exact world/chunk target; the BukkitScheduler fallback is gone.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaQueueHandler.java:179` — all
    facade targets enter broker target lanes with bounded claim futures; timed-out unclaimed callbacks skip
    the payload, and Folia rejects the legacy location-free live-state policy.
  - `build-logic/src/main/kotlin/buildlogic.common.gradle.kts:157` — JDK-AST constructor-arity guard across all
    four Folia-enabled projects; nested calls, lambdas, comments, and generic commas cannot evade it.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/queue/implementation/`
    `QueueHandlerRoutingTest.java:25` and `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/`
    `TicketAuthorityTest.java:134` — exact facade delegation, entity-owner execution, and zero-dispatch
    wrong-owner rejection.
  - `worldedit-bukkit/src/test/java/com/fastasyncworldedit/bukkit/adapter/NMSAdapterRoutingTest.java:53` — live
    ticket/context execution, exactly-one CAS counting, fail-before-access policy, Paper global/worker parity,
    and command-block owner execution.
  - `worldedit-bukkit/adapters/adapter-26.1/src/test/java/com/sk89q/worldedit/bukkit/adapter/impl/fawe/v26_1/`
    `PaperweightFaweWorldNativeAccessTest.java:45` and `worldedit-bukkit/folia/src/test/java/`
    `com/fastasyncworldedit/bukkit/folia/FoliaQueueHandlerTest.java:230` — N-target/N-dispatch owner callbacks
    and target-deadline suppression.
- **Route disposition:**
  - `LazyBaseEntity`: adapter-26.1 threads `EntityTarget(BukkitAdapter.adapt(entity))`; serialization uses
    `QueueHandlerRouting.syncOn(EntityTarget, EntityTask)` and asserts the fresh entity ticket.
  - `PaperweightFaweWorldNativeAccess`: cached changes plus send-only chunks partition by exact `IntPair`;
    `ChunkTargetPartitions.dispatchEach` constructs one `ChunkTarget` and one facade callback per partition.
    Because production dispatch lives inside that helper's per-entry loop, a callback spanning partitions is
    impossible by construction. The three-target test executes each callback on a different owner thread
    under a live ticket and rejects any value whose target differs from its partition.
  - `NMSAdapter`: adapter-26.1 passes the World and composes the returned stage. The production overload has
    no non-owner CAS and no Folia lock fallback; a counting test seam proves one CAS in the ticket callback.
  - `BukkitBlockCommandSender`: `isActive` submits `updateActive` through the sender block's exact
    `ChunkTarget`; the callback asserts ownership before chunk/type access.
- **C5 allowances deleted:**
  - `worldedit-core/.../LazyBaseEntity.java` — one counted allowance removed.
  - `worldedit-bukkit/.../NMSAdapter.java` — one counted allowance removed.
  - `adapter-26.1/.../PaperweightFaweWorldNativeAccess.java` — both counted allowances removed.
  - `worldedit-bukkit/.../BukkitBlockCommandSender.java` — one counted allowance removed.
  - The `Fawe.java` definition, `BukkitThreadContext`, and task-17 `FoliaTickThreadGuard` entries are the only
    remaining entries; none is a deferred task-16 site.
- **What task 15 may close:** rows 16, 27, 31, 32, and 33 in `c5-requalification-record.md`; no withheld
  task-16 ownership route or temporary allowance remains.
- **Verification:** no Gradle task was run. Direct `javac` verification passed for core/Bukkit main on Java
  21 and Folia/adapter-26.1 on Java 25, using pre-built core classes and the pinned 26.1.2 Paper/Folia APIs.
  Deterministic direct suites pass: core 7/7, Bukkit routing 5/5, adapter partition 1/1, and Folia queue 17/17.
  `git diff --check` passes for the authorized files; excluded adapter trees have no diff; targeted scans find
  no banned Bukkit scheduler or legacy main-thread predicate in the re-routed sites.
- **Deviations:**
  - C6 budgeting and strict `syncWhenFree` priority were accepted before this round and were not changed.
  - Gradle graph compilation and the integrated C5 task remain orchestrator-owned as instructed. The new C5
    rule uses the JDK parser rather than a source regex so legal nested/generic Java cannot bypass arity checks.
  - Excluded adapter trees are untouched and retain the legacy String overload's exact Paper behavior.
- **Attack points:**
  - Task 17 must issue the backend's sole `TicketAuthority`, register `FaweThreadContext`, and inject the
    authority through the protected QueueHandler constructor before the Paper queue is constructed. Until
    then, a Paper target route fails closed before scheduling; Folia ticket minting remains dispatcher-owned.
  - A deadline that wins before callback claim suppresses all side effects. An owner callback already claimed
    cannot be interrupted safely and is allowed to finish, while its caller remains terminally unblocked.
  - Fire-and-forget cached flushes do not synchronously surface a later owner failure; explicit `flush()` and
    section-CAS composition do propagate failures through their bounded stages.
- **Escalation:** AUTHORIZED — r17 supplied every missing seam and target. No Corrective-3 row remains
  `NEEDS_CONTEXT`; task 15 may close its five withheld rows, with production bootstrap completion owned by
  task 17 as already assigned.
