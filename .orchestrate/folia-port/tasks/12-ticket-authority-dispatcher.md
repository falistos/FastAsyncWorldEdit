# Task 12 — TicketAuthority minting + FoliaRegionDispatcher on Folia schedulers (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** task 10 (FaweThreadContext resolver + Bukkit backend — for the inline-when-
  owned decision). Consumed by tasks 14, 16, 17.
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: scheduler SPI /
  adapters-NMS / thread-ownership. **Double-reviewer (concurrency & thread-ownership)** —
  THIS is the single choke point (C1); a leak here defeats every downstream invariant.

## Mandate
Implement the capability-minting authority and the one class that talks to Folia's schedulers,
frozen in architecture §3.2 and §3.3: `TicketAuthority` (sole minting path for
`RegionTicket`/`EntityTicket`, single issuance at bootstrap) and a `FoliaRegionDispatcher`
implementation over `RegionScheduler` / `GlobalRegionScheduler` / `EntityScheduler` /
`AsyncScheduler`, with the explicit **inline-when-owned** execution guarantee (§3.3, F11) and
the §8 `TaskKind` instrumentation seam.

## In scope
- `TicketAuthority` (core, same-package final class): implement `mintRegion`/`mintEntity`/
  `retire(RegionTicket)`/`retire(EntityTicket)`. Constructor non-public; **single issuance** —
  a second issuance attempt throws (architecture §3.2). No public accessor exposes it.
- `RegionTicket` / `EntityTicket` bodies (core): implement `world()`/`owns()`/`assertOwns()`/
  `isLive()`/`sequence()`. F3 validity contract: valid only for the lexical dynamic extent of
  one dispatcher callback; `assertOwns` throws if not owning (cx,cz), used off its minting
  thread, or retired; `isLive()` true only until retirement. `EntityTicket.assertOwns` is an
  exact-entity assertion. `sequence()` is DIAGNOSTICS ONLY (see cross-ref to task 11 flag).
- `FoliaRegionDispatcher` **implementation** (folia module; the interface is already frozen
  in tree — create the impl class, propose a name, e.g. `FoliaRegionDispatcherImpl`):
  - `onRegion(world,cx,cz,kind,RegionTask/RegionCall)`: run on the owning region; mint a fresh
    `RegionTicket`, retire it in a `finally`. **Inline, synchronously, when the calling thread
    already owns the target** (ticket still minted/retired); otherwise schedule on
    `RegionScheduler.execute(plugin, world, cx, cz, task)` (A5, W0.2 `owner_mismatches=0`).
  - `onEntity(entity,kind,EntityTask)`: run on the entity's owning context with a fresh
    `EntityTicket`; entity retirement completes the stage exceptionally; inline when owned.
  - `onGlobal(kind,GlobalTask)`: global-region thread; **no ticket** (owns no chunk).
  - `stopAccepting(reason)` / `drain(deadline)` returning `DrainReport` — never awaited from a
    tick thread; the drain report's unresolved counts feed the G6 certification gate.
  - **Instrumentation (F10/G3):** record per `TaskKind` and per region: schedule delay,
    callback runtime, ticket mint/retire counts. Expose the mint/retire counters so task 14's
    `DiagnosticSnapshot` producer can read `liveTickets`/`outstandingFutures`.
  - Returned stages complete through the FAWE completion executor — **never run arbitrary
    continuations on a tick thread**; no caller-runs for owner-bound work (spec §1b).
- Registration of the Folia `FaweThreadContext` and the injected `TicketAuthority` instance is
  task 17's job; here, expose the injection points and report them.

## Out of scope
- `FoliaBackpressure` admission (task 13), lane/broker drain (task 14), snapshot cache (wave 2).
- Adding the `RegionTicket` parameter to the ~13 live-state adapter methods (that is the
  adapter GET/SET path — wave 2). This task delivers minting + dispatch only.
- Bootstrap selection/wiring (task 17).

## Binding references
- architecture.md §3.2 (capabilities, F1/F3), §3.3 (dispatcher interface + inline guarantee F11
  + no raw scheduler leaks, CI-grep-enforceable), C1 (ownership routing: no direct
  `MinecraftServer.execute` / `MCUtil.MAIN_EXECUTOR` / legacy `BukkitScheduler` / off-thread
  live-section CAS; AsyncCatcher/physicsFreeze/Timings never toggled), §1b, §2 (module layout).
- amendment-1-draft.md A1.4 (completion callbacks of any server-async facility are UNTRUSTED
  thread contexts — re-dispatch to the owner before touching live OR FAWE shared state).
- `spikes/w02-pipeline-results.md` (RegionScheduler proven: `owner_mismatches=0`; relight
  completion callbacks land on non-owner threads → re-hop required),
  `spikes/recon-adapters-nms.md` §6 (the danger set the ticket protects).
- In-tree frozen: `RegionTicket.java`, `EntityTicket.java`, `TicketAuthority.java`,
  `FoliaRegionDispatcher.java` (interface), core callback types `RegionTask/RegionCall/
  EntityTask/GlobalTask`.

## Signature / structural flags
- The `FoliaRegionDispatcher` interface exists but has **no implementation class** in tree —
  create it (name not frozen; propose in record).
- `RegionTicket.sequence()` javadoc currently says it keys terminal records — this is WRONG per
  v3.1 (diagnostics only). Coordinate with task 11's rename flag; do not rely on `sequence()`
  for completion keying.
- Confirm the Folia `plugin` handle / scheduler access available in the folia module (the module
  is `compileOnly` folia-api + `compileOnly` worldedit-core). If the plugin instance needed for
  `RegionScheduler.execute(plugin, ...)` is not reachable, flag the injection gap (task 17).

## Files to touch
- `worldedit-core/.../util/task/TicketAuthority.java`, `RegionTicket.java`, `EntityTicket.java`
  — implement bodies.
- `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/` — CREATE the
  dispatcher impl. References core types only (F1).

## Context & decisions
- Correctness > performance (spec §4b), but the dispatcher is the hottest scheduler seam:
  inline-when-owned must add no scheduling hop; the ticket must be allocation-lean.
- Async-first, virtual-threads-by-default for the FAWE completion executor where the executor
  is FAWE-owned (user Java conventions) — but tick threads never block (spec §1b).
- Fail-fast internally (retired/escaped ticket → exception); graceful operator message at the
  API boundary.

## Executor constraints
- Folia module compiles at **Java 25** (architecture §2); core stays Java 21. No folia-api type
  in any core signature (F1). No git commits. No FQN inline. English. `// Folia port:` marker
  only where a seam replaces an existing call.
- Gradle invocations always `--no-configure-on-demand`.

## Escape hatch
If the inline-when-owned decision cannot be made without a live ownership query that the
Bukkit/Folia context (task 10) does not yet expose, STOP and report the exact predicate needed
(NEEDS_CONTEXT) rather than approximating ownership by region-ID (region IDs are hints, never
proof — spec §1b, W0.2 §3).

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- CI-grep clean: `RegionScheduler`/`GlobalRegionScheduler`/`EntityScheduler`/`AsyncScheduler`
  referenced only inside the dispatcher impl (architecture §3.3). Danger-set review:
  `git grep 'RegionTicket'` (C1).
- Single-issuance: a second `TicketAuthority` issuance throws; a retired/escaped ticket fails
  `assertOwns`/`isLive`.
- Runtime confirmation deferred to harness (pending until task 17 wires bootstrap):
  `JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
  harness/scenario.sh pipeline-probe --version 26.1.2`.

## Test / verification strategy
Orchestrator runs the compile proof + grep gates. Targeted deterministic tests (spec §6):
scheduler routing (inline vs scheduled), ticket lifecycle (mint/retire/escape/off-thread),
single issuance. Runtime owner-mismatch=0 confirmed via harness pipeline-probe post-wiring.

---
## Dev record (worker fills this in on completion)

- **Status:** DONE_WITH_CONCERNS
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/TicketAuthority.java`
    — one-shot issuance plus owner-checked mint/retire (`:35-98`).
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/RegionTicket.java`
    — thread-bound/live region capability and diagnostics-only sequence (`:33-98`).
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/EntityTicket.java`
    — exact-entity/thread/live capability (`:28-82`).
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaRegionDispatcher.java`
    — task-14 diagnostics accessors (`:37-82`).
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcher.java`
    — scheduler choke point, lifecycle/drain, completion handoff, and metrics (`:63-660`).
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaCompletionStage.java`
    and `FoliaCompletableFuture.java` — completion-executor-confined stage/future views
    (`FoliaCompletionStage.java:31-317`, `FoliaCompletableFuture.java:31-143`).
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaTargetAdapter.java`,
    `FoliaWorldHandle.java`, and `FoliaEntityHandle.java` — bootstrap-owned, no-live-read target
    unwrapping seam (`FoliaTargetAdapter.java:25-33`, handle records `:26-31`).
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/TicketAuthorityTest.java`
    — issuance, region/entity escape, retirement, and sequence tests (`:49-123`).
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcherTest.java`
    — inline/scheduled routing, retirement/failure, continuation, lifecycle, metrics, and bounded
    drain tests (`:90-382`).
- **Deviations:**
  - Named the package-private implementation `DefaultFoliaRegionDispatcher`; its bootstrap
    constructor injects `Plugin`, the sole `TicketAuthority`, `OperationCompletionService`, and
    `FoliaTargetAdapter` (`DefaultFoliaRegionDispatcher.java:83-118`).
  - Added the target-adapter/handle seam because core `World`/`Entity` cannot be passed to Folia
    scheduler APIs; task 17 must provide the pure unwrap implementation and graph wiring.
  - Added completion stage/future wrappers so even late non-async continuations attached through
    `toCompletableFuture()` re-enter the FAWE completion executor (`FoliaCompletionStage.java:306-316`,
    `FoliaCompletableFuture.java:40-143`).
  - Drain uses both `AsyncScheduler` and an `OperationCompletionService.Producer` deadline; the
    latter survives plugin scheduler cancellation during disable (`DefaultFoliaRegionDispatcher.java:249-288`).
  - Per instruction, Gradle was not run. Direct Java-21 core and Java-25 Folia compilation passed;
    direct JUnit execution passed 4/4 core tests and 10/10 Folia tests. Full graph compile and
    runtime harness remain orchestrator/task-17 gates.
- **Attack points:**
  - Inline decisions use only `FaweThreadContext.current().ownsChunk/ownsEntity`; scheduled paths
    use the matching Folia scheduler, and tickets mint/retire inside callback `finally` blocks
    (`DefaultFoliaRegionDispatcher.java:154-215`, `:335-420`).
  - Entity retirement and duplicate scheduler signals are single-claim and settle exceptionally;
    all stage completion and shared settlement re-enter the completion service
    (`DefaultFoliaRegionDispatcher.java:197-210`, `:448-482`).
  - Stop/drain bookkeeping is lock-coherent; drain is bounded by a lifecycle-owned producer timer
    and reports unresolved labels without tick-thread waiting (`DefaultFoliaRegionDispatcher.java:236-288`,
    `:465-513`).
  - Metrics are per target plus `TaskKind` and expose submission/callback delay/runtime and
    mint/retire totals; `liveTickets()`/`outstandingFutures()` are public SPI reads
    (`DefaultFoliaRegionDispatcher.java:291-305`, `:553-605`).
  - Cross-review findings were closed: all new/modified files have upstream headers; the
    `toCompletableFuture()` continuation leak, plugin-timer-only drain, incoherent label/count
    snapshot, and missing scheduled-entity/failure/off-thread tests were fixed.
  - Production scheduler-type grep is confined to `DefaultFoliaRegionDispatcher`, except the
    pre-existing textual names in task-16 skeleton comment `FoliaTaskManager.java:7`; there is no
    production scheduler-type reference there. Danger-set grep found no dispatcher use of
    `MinecraftServer.execute`, `MCUtil.MAIN_EXECUTOR`, `BukkitScheduler`, `AsyncCatcher`,
    `physicsFreeze`, or `Timings`.
- **Escalation:** AUTHORIZED — architecture v3.1 and co-signed §3.6b r6 were present and compatible;
  no ownership-context blocker remained. Task 17 must issue/inject the authority, plugin,
  completion service, and target adapter. Task-13 `RegionKey` identity/rejection-hook wiring was
  deliberately untouched for task 14; the task-16 comment-only grep overlap above is flagged for
  its owner/orchestrator.

### Alignment pass (task-11 API reconciliation)

- **Status:** DONE
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcher.java:257-294`
    — registered the drain producer with its transition-failure callback and published only the
    completion service's isolated drain view.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcher.java:333-338,460-555`
    — kept raw futures private, settled accounting before publication, failed drains closed, and
    moved tick-origin terminal transitions through the isolated notification path.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcher.java:204-210`
    — retained and documented the Folia entity-scheduler minimum one-tick delay.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaCompletionStage.java`
    and `FoliaCompletableFuture.java` — deleted after all dispatcher views moved to
    `OperationCompletionService.isolateStage`.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcherTest.java`
    (`:124-172`, `:219-242`, `:369-437`)
    — asserted isolated continuation confinement, the entity delay contract, drain transition
    failure, producer terminal rejection, and post-termination tick callback behavior.
- **Deviations:**
  - None from the binding recipe. No `worldedit-core` file was edited and no task-11 or task-13
    overlap was reconciled here.
  - Per instruction, Gradle was not run. Direct Java-21 core dependency compilation and Java-25
    Folia production/test compilation passed; direct JUnit execution passed 13/13 Folia tests.
- **Attack points:**
  - MINOR 1, post-TERMINATED inline tick-side: fixed mechanically. All dispatcher rejection and
    claimed-completion call sites use `submitCompletion`; a tick caller enqueues through an
    isolated notification before invoking the completion service, so concurrent or completed
    termination cannot run the transition inline on that tick thread
    (`DefaultFoliaRegionDispatcher.java:333-338,460-468,547-555`). The post-termination test also
    asserts that the consumer continuation runs notification-side, never control-side
    (`DefaultFoliaRegionDispatcherTest.java:400-437`).
  - MINOR 2, missing producer-state guard: fixed mechanically. `requestFinishDrain` checks the
    waiter's terminal/request state and routes a rejected `Producer.complete` transition through
    the single-CAS `failDrain` path (`DefaultFoliaRegionDispatcher.java:490-541`); both producer
    failure modes are tested (`DefaultFoliaRegionDispatcherTest.java:369-398`).
  - MINOR 3, entity latency: no code-level lower-latency option exists in the Folia API;
    `EntityScheduler.execute` treats delays below one tick as one tick, while `run` also schedules
    for the next tick. The explicit `1L` is retained as a documented platform constraint and
    asserted in the scheduled-entity test (`DefaultFoliaRegionDispatcher.java:204-210`,
    `DefaultFoliaRegionDispatcherTest.java:219-242`).
  - The binding registration order is `AtomicReference`, failure-aware `registerProducer`, waiter
    construction, reference publication, then lifecycle transitions. `failDrain` terminal-CASes
    once, removes the waiter, cancels the deadline, and completes only the private raw future
    exceptionally (`DefaultFoliaRegionDispatcher.java:257-294,524-541`).
  - Both public completion paths use `OperationCompletionService.isolateStage`, and dispatcher
    accounting settles before raw submission outcome publication
    (`DefaultFoliaRegionDispatcher.java:460-468,543-545`). Tree-wide searches found no remaining
    obsolete wrapper or no-argument `registerProducer` reference.
- **Escalation:** AUTHORIZED — all three MINORs were resolved within the frozen §3.6/§3.6b/§3.6c
  contracts. No core change or new design decision was required; Gradle and task-11/task-13 files
  remained deliberately untouched.

### Alignment pass 2 (r13 producer API)

- **Status:** NEEDS_CONTEXT
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcher.java`
    (`:76`, `:258-301`, `:497-534`, `:560-567`, `:582-630`) — migrated drain registration to
    the r13 producer API, added a stable drain key and pre-publication expiry barrier, retained
    isolated publication, and preserved the tick-thread completion hop.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcherTest.java`
    (`:369-405`, `:525-539`, `:572-613`) — covered completion-flush expiry, exactly-once visible
    terminalization and producer release; migrated the producer mock; made the fake ownership
    context thread-bound for the r12 check.
- **Deviations:**
  - The signature and atomic-registration migration are complete, but the alignment cannot be
    certified DONE because the frozen full `DrainReport` construction is not a strictly bounded
    expiry transition. No output semantic was improvised.
  - A short monitor around registration, waiter construction, reference publication, and queue
    insertion closes the race where concurrent expiry could otherwise observe a null waiter
    (`DefaultFoliaRegionDispatcher.java:258-271,508-512`).
  - Per instruction, Gradle was not run. Scoped Java-21 dependency compilation and Java-25
    `javac --release 25 -Xlint:all` production/test compilation passed. The only production lint
    was the pre-existing overload warning in the untouched `FoliaRegionDispatcher` interface;
    direct JUnit execution passed 14/14 tests.
- **Attack points:**
  - The producer key is `DrainProducerKey(long drainId)`, allocated from a dispatcher-local
    monotonic sequence (`DefaultFoliaRegionDispatcher.java:76,258,582-588,629-630`). It is stable
    for the producer lifetime, distinguishes concurrent drain waiters, and names the domain role
    without using mutable waiter identity.
  - Registration preserves the sealed order: `AtomicReference`, failure-aware atomic producer
    registration, waiter construction, reference publication, then lifecycle transitions. The
    expiry hook is installed in that registration; the obsolete `onFlushing` call is gone
    (`DefaultFoliaRegionDispatcher.java:258-301`).
  - Idempotence is established by `finishRequested.compareAndSet(false, true)` plus the
    `finished` guard/CAS (`DefaultFoliaRegionDispatcher.java:497-504,532-534`). The r13 service
    invokes a generic producer's expiry hook once and releases that producer after the hook even
    when its transition fails. The regression test repeats `flush`, observes one deadline-task
    cancellation, and asserts zero registered producers (`DefaultFoliaRegionDispatcherTest.java:369-405`).
  - The hook runs no user callback and performs no live-world access. It does, however, enter
    `finishDrain`, which copies and sorts every outstanding label and linearly removes the waiter
    (`DefaultFoliaRegionDispatcher.java:508-534`). Those operations are not cardinality-bounded,
    so r10's bounded-hook property is not established.
  - The r12 Q4 check passed without a production change. A tick caller enters only an isolated
    notification stage and returns before `OperationCompletionService.execute`; the notification
    thread later encounters `TERMINATED`, so the tick thread neither acquires completion
    serialization nor settles dispatcher accounting (`DefaultFoliaRegionDispatcher.java:560-567`).
    The thread-bound test context and existing post-termination test exercise this distinction
    (`DefaultFoliaRegionDispatcherTest.java:439-475,572-613`).
  - The first pass remains intact: both raw futures are private, both public views use
    `isolateStage`, accounting settles before publication, and `failDrain` retains its single-CAS
    exceptional terminalization (`DefaultFoliaRegionDispatcher.java:301,464-472,537-557`).
- **Escalation:** NEEDS_CONTEXT — the frozen dispatcher SPI requires a `DrainReport` containing
  all unresolved labels, while r10 requires a bounded expiry hook. Producing that report currently
  requires an unbounded label copy/sort. Which behavior is binding at completion-flush expiry:
  permit the current full snapshot as bounded, return a bounded degraded/capped report, complete
  the drain exceptionally and publish diagnostics elsewhere, or introduce a precomputed bounded
  snapshot contract? The latter three change observable semantics or data ownership and were not
  authorized. No `worldedit-core`, backpressure, task-manager, or queue-handler file was edited.

### Alignment pass 3 (r15 drain snapshot)

- **Status:** DONE
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcher.java`
    (`:75-78`, `:133-138`, `:323-351`, `:467-527`, `:536-623`, `:680-855`) — replaced the split
    counters/label map with one atomic immutable drain epoch, added the structurally shared label
    index, and made normal and expiry terminalization publish the epoch's prebuilt report.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcherTest.java`
    (`:376-509`, `:607-675`) — proved no expiry-time snapshot build or label traversal, identical
    natural/expiry reports, concurrent ticket/task epoch coherence, and deterministic label order.
- **Deviations:**
  - Label bookkeeping is a persistent AVL tree ordered by monotonic submission ID. Registration
    and task retirement copy/rebalance one root-to-leaf path, `O(log n)` worst case; ticket
    mint/retire updates reuse the label root and cost `O(1)`. No update copies or sorts the complete
    label population (`DefaultFoliaRegionDispatcher.java:692-855`).
  - The package-private constructor accepts a no-op-in-production snapshot-work probe so the
    expiry regression test can fail on either report construction or label traversal. This is a
    test seam only and does not change the dispatcher SPI (`DefaultFoliaRegionDispatcher.java:112-138`,
    `DefaultFoliaRegionDispatcherTest.java:376-408,607-627`).
  - Drain waiters are keyed by their existing `DrainProducerKey` in a concurrent map, removing the
    former linear queue removal from terminalization (`DefaultFoliaRegionDispatcher.java:78,276-288,554-571`).
  - Per instruction, Gradle was not run. Scoped Java-25 production and test compilation passed with
    `javac --release 25 -Xlint:all`; direct JUnit execution passed 17/17 tests in three consecutive
    runs. Both final standards and r15-spec reviews passed after their test-proof findings were fixed.
- **Attack points:**
  - Task registration/settlement and ticket mint/retire all serialize under `lifecycleLock`; each
    builds one `DrainSnapshot` containing both exact counts, the complete persistent label view,
    and its already-built `DrainReport`, then publishes it with one atomic reference write. A
    reader can therefore observe only one coherent epoch (`DefaultFoliaRegionDispatcher.java:339-357`,
    `:467-488`, `:511-529`, `:598-623`).
  - Expiry claims `finishRequested` once, performs exactly one `diagnosticSnapshot.get()`, and
    submits that report through the registered producer. `finishDrain` receives the report and
    neither scans, copies, sorts, nor rebuilds labels; later settlement cannot mutate the persistent
    view captured at the linearization point (`DefaultFoliaRegionDispatcher.java:536-559`).
  - Natural zero-remaining settlement calls the same `requestFinishDrain` path as deadlines and
    expiry. A controlled producer test distinguishes actual settlement from explicit expiry at the
    same state and asserts structural report equality (`DefaultFoliaRegionDispatcherTest.java:422-440`).
  - The expiry test disables all instrumented snapshot construction/traversal during flush and
    still observes exact counts, complete labels, exactly-once terminalization, and producer release
    (`DefaultFoliaRegionDispatcherTest.java:376-419`). Concurrent region callbacks exercise label
    registration/settlement and ticket mint/retire while repeated drains assert untorn epochs
    (`DefaultFoliaRegionDispatcherTest.java:443-500`).
  - Label iteration is submission-ID order and therefore deterministic without expiry-time sorting;
    independent dispatchers produce the same ordered evidence (`DefaultFoliaRegionDispatcherTest.java:503-509`).
- **Escalation:** AUTHORIZED — r15 resolves the prior bounded-hook/full-report conflict without a
  frozen SPI or core change. No `worldedit-core`, `DefaultFoliaBackpressure`, `FoliaTaskManager`, or
  `FoliaQueueHandler` file was edited in this pass; no remaining escalation is required.
