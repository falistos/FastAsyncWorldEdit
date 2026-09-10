# Task 11 — Completion protocol: OperationCompletion + ChunkTerminalRecord (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** none structurally (core coordinator). Consumed by tasks 12, 14.
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: history/persistence
  + concurrency. Double-reviewer (data persistence AND thread-ownership) per spec §6 — this is
  the exactly-once completion sink; corruption/loss here is the named risk.

## Mandate
Implement the exactly-once operation-completion protocol frozen in architecture §3.6
(F5 + r2 amendments 1–2): `OperationCompletion` with registration-before-scheduling, keyed
terminal records with applied receipts, explicit admission closure preserving the aggregate
rejection cause, and a same-chunk plan sequencer. Decision FD-1 stands: **CommitOutcome does
NOT exist** — the frozen §3.6 `ChunkTerminalRecord` protocol is the design. Do not reintroduce
any two-record Committed/CommitFailed fold.

## In scope
- `OperationCompletion` implementation (core): `register` (exactly once per chunk plan, BEFORE
  admission attempt/scheduling), `closeAdmission` (no further registration; completion becomes
  reachable; preserves aggregate `NOT_ACCEPTED` rejection cause so a no-committed-mutation
  operation resolves `FAILED`, never successful-empty), `terminal` (idempotent per
  (operationId, chunkKey, planSequence); duplicates counted diagnostically, never decrement),
  `future()` (completes exactly once). State machine OPEN → COMPLETING → SUCCEEDED | FAILED |
  PARTIAL. Completion only when: admission closed AND every registered chunk terminal AND every
  required finalizer settled AND every APPLIED record reached its persistence boundary.
  `SUCCEEDED` additionally requires every accepted mutation committed, all required finalizers
  succeeded, all packet sends enqueued (architecture §3.6).
- The **same-chunk plan sequencer**: allocates a per-chunk `planSequence` before registration
  and scheduling; invariant across dispatcher callbacks, ownership rebinds, recapture/reprepare
  attempts, and finalizers (r2 amendment 1). Same-chunk tickets execute in registration order.
- Fill `OperationResult` (currently an empty `// wave 1` shell) with a minimal coherent shape:
  terminal classification (succeeded/failed/partial) + per-chunk terminal records / receipts
  sufficient for undo and the actor-facing single result. `PacketPhaseResult` and
  `EntityAction` shapes: fill the minimum this task needs; note that wave 2 (chunk pipeline)
  may extend them.

## Out of scope
- Wiring the completion sink into the commit path / broker (task 14) or the dispatcher
  (task 12). This task delivers the coordinator + sequencer as standalone, unit-testable core
  types.
- Write-ahead history PREPARED/APPLIED persistence mechanics (wave 2). This task defines the
  "APPLIED reached its persistence boundary" gate as an injectable predicate/callback, not the
  persistence itself.

## Binding references
- architecture.md §3.6 (the entire exactly-once section — terminal identity r2 am.1,
  registration/rejection r2 am.2, the six-state `TerminalStatus`, `AppliedReceipt`,
  `ChunkTerminalRecord`, `OperationCompletion`, drain terminalization), §1b (liveness — the
  coordinator is reachable only from dispatcher-run contexts; `terminal`/`register` are the
  shared-state half of C1, asserted at entry), C4.
- spec §4d (operation semantics: success only after every accepted mutation + finalizer;
  every async failure reaches the actor exactly once; history describes exactly what committed).
- In-tree frozen types: `ChunkTerminalRecord.java`, `OperationCompletion.java`,
  `AppliedReceipt.java`, `TerminalStatus.java`, `OperationResult.java`, `PacketPhaseResult.java`,
  `EntityAction.java`. Implement AGAINST these.

## Skeleton state (RESOLVED 2026-07-17 — certification corrective C-F1F2)
The v3-stale skeleton this draft originally flagged (`ticketSequence` as terminal key) was
corrected by certification corrective C-F1F2 and re-verified verbatim against architecture
v3.1 §3.6: `ChunkTerminalRecord.planSequence` (invariant, same-chunk sequencer),
`OperationCompletion.register(long chunkKey, long planSequence)` documented pre-admission,
`RegionTicket.sequence()` re-documented diagnostics-only (MUST NOT key terminal completion).
Implement directly against the current tree — no rename authorization needed anymore.

## Files to touch
- `worldedit-core/.../util/task/OperationCompletion.java` — add a concrete implementation
  class (propose name); interface signatures are frozen as-is.
- `worldedit-core/.../util/task/ChunkTerminalRecord.java` — frozen as-is (no edits expected).
- `worldedit-core/.../util/task/OperationResult.java`, `PacketPhaseResult.java`,
  `EntityAction.java` — fill shapes.
- CREATE: same-chunk plan sequencer (core).

## Context & decisions
- Correctness > performance (spec §4b): exactly-once and no-silent-loss are non-negotiable.
- Async-first: `future()` is a `CompletionStage`; completion callbacks run on a FAWE executor,
  never a tick thread (architecture §3.3/§3.6).
- Partial commit is representable (`PARTIALLY_COMMITTED` + exact `AppliedReceipt`); undo
  operates only on the applied subset; no automatic whole-operation rollback (architecture §3.6).

## Executor constraints
- Java 21 output (core). No folia-api reference (F1). No git commits. No FQN inline. English.

## Escape hatch
If any frozen signature in the current tree contradicts architecture v3.1 §3.6 (should not
happen post-corrective — re-verified 2026-07-17), STOP with NEEDS_CONTEXT rather than editing
co-signed SPI signatures.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- Idempotency: duplicate `terminal(...)` for the same (operationId, chunkKey, planSequence) does
  not double-count and does not flip the aggregate result.
- No-commit operation: admission closed with only `NOT_ACCEPTED` terminals resolves `FAILED`.
- `future()` completes exactly once for every terminal shape (deterministic unit tests here are
  in-scope per spec §6 / §4 "targeted deterministic tests" — failure injection).

## Test / verification strategy
Targeted deterministic tests (spec §6): exactly-once, duplicate suppression, no-commit → FAILED,
partial → PARTIAL, drain terminalization (no INCOMPLETE state). Orchestrator runs compile proof
+ tests. `[NR-H]` (terminal completion failure injection) is a certification input, noted.

---
## Dev record (worker fills this in on completion)

- **Status:** DONE
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:16`
    — exactly-once coordinator; plan producers register with plan admission at line 60, result
    completion is gated at line 235, and bounded persistence settlement is implemented at line 422.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:21`
    — backend-owned single-thread serialization, producer fence, flush lifecycle, retry timer, and
    post-termination inline fallback; lifecycle transitions are at lines 56, 96, and 210.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/CompletionServiceStage.java:12`
    — read-only `CompletionStage` adapter binding implicit continuations, including late ones, to
    the completion service; the `CompletableFuture` bridge is at line 288.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/HistoryPersistencePolicy.java:6`
    and `HistorySettlement.java:3` — finite config-derived settlement bounds and terminal settlement
    vocabulary.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/AppliedReceipt.java:7`
    — finalized settlement field and defensive copies of every receipt list at lines 17–22.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationResult.java:10`
    — terminal classification, immutable result lists, operation-scoped history usability, listed
    unavailable chunks, and the actor-facing persistence-loss message at lines 33–50.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/PacketPhaseResult.java:6`
    and `EntityAction.java:9` — minimal validated packet and entity receipt shapes.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/SameChunkPlanSequencer.java:10`
    — invariant monotonic plan allocation and same-chunk predecessor gating.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/CompletionProtocolTestSupport.java:10`
    — shared completion-service/policy/receipt/await fixture.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletionTest.java:33`
    — coordinator coverage through line 550, including duplicates after termination, finalizers,
    `NO_CHANGE`, `PARTIALLY_COMMITTED`, packet failure, immutable receipts, persistence exhaustion,
    deadline, timeout, cancellation, and single/all-chunk `UNAVAILABLE` cases.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:27`
    — producer/flush fence, registered-plan late terminal, inline serialization/lock safety, plain
    completion thread, drain clamp, and finite-policy validation through line 190.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/SameChunkPlanSequencerTest.java:17`
    — same-chunk registration order with disjoint-chunk progress.
- **Deviations:** No Gradle command was run, as directed; the orchestrator retains the graph compile
  proof. Focused Java 21 `javac --release 21 -Xlint:all` compilation passed, all 25 task-scoped
  deterministic tests passed, and `git diff --check` passed. Numeric policy values and backend
  config/bootstrap wiring remain intentionally unset until the W1-exit freeze and task 14; the
  injectable `HistoryPersistencePolicy` exposes only finite attempt/timeout/backoff/deadline knobs.
- **Attack points:**
  - Task 14 must create one shared backend-owned `OperationCompletionService`, derive
    `HistoryPersistencePolicy` from the W1-frozen `HISTORY_PERSIST_RETRIES` policy, and preserve the
    frozen shutdown order (`stopAccepting` → drain → completion-service flush → executor shutdown).
  - `CompletionServiceStage` deliberately wraps the complete Java 21 `CompletionStage` surface so
    non-async continuations attached after terminal publication run on the active completion
    service; after `TERMINATED`, §3.6b's serialized inline fallback may use the attaching thread. A
    future JDK addition to that interface must be mirrored in the adapter.
- **Escalation:** None. The co-signed §3.6b adjudication resolves the former persistence and executor
  lifecycle blockers; independent standards/spec re-review found no remaining contract defect.

### Corrective 1 (2026-07-17)

- **Status:** DONE
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:50`
    — retry timer now removes cancelled tasks from its `DelayQueue`; producer registration requires
    a serialized exceptional-transition settlement at line 59, `runQueued` converts thrown
    transitions into that settlement plus deregistration at lines 186–210, and `schedule` returns
    the cancellable handle at line 321.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:46`
    — exposes a finite finalizer timeout; coordinator transition failure settlement is at line 246,
    the shared generation-guarded/drain-clamped deadline is at lines 503–565, bounded finalizer
    settlement is at lines 567–615, and persistence cancels deadline/attempt/retry handles through
    lines 617–729.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/CompletionServiceStage.java:12`
    — documents §3.6b's post-`TERMINATED` attaching-thread exception at line 14.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/SameChunkPlanSequencer.java:21`
    — adds a bounded package seam for deterministic exhaustion verification; allocation rejects
    before overflow at line 39.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/CompletionProtocolTestSupport.java:87`
    — shared bounded concurrent runner and completion-worker phase blocker at lines 87–158.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletionTest.java:75`
    — concurrent duplicate, terminal/admission-close race, exceptional coordinator transition, and
    wrong-context guard cases at lines 75, 106, 475, and 656.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:55`
    — producer-deregistration/flush race, throwing-transition settlement, never-settling finalizer
    clamp, and timer-queue cleanup at lines 55, 77, 215, and 250.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/SameChunkPlanSequencerTest.java:17`
    — asserts cross-chunk sequence uniqueness and exhaustion-before-overflow at lines 31–32 and 50.
- **Deviations:** No Gradle command was run, as directed. Focused Java 21
  `javac --release 21 -Xlint:all` compilation passed without warnings; all 34 task-scoped tests
  passed, the race-bearing suite completed 20 consecutive runs, and `git diff --check` plus the
  130-column check passed.
- **Attack points:** Task 14 must bind the finite finalizer timeout alongside the W1-frozen history
  settlement policy and keep every producer's exceptional-transition settlement coordinator-only;
  no producer API remains that can omit that settlement callback.
- **Escalation:** None. Corrective standards and spec re-reviews both pass; the generation token
  closes the stale deadline-handle race and the phase blocker forces the requested race
  interleavings before the completion worker can advance them.

### Corrective 2 (2026-07-17)

- **Status:** DONE
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:53`
    — the control executor now delegates immutable outcomes to per-task virtual-thread
    notifications; observable-stage isolation is exposed at lines 230–232, explicit executors are
    isolated at lines 264–276, and flush fences counted publications without waiting for consumer
    continuations at lines 341–438.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:101`
    — `tryAcquireLease` is the synchronous, ACCEPTING-only preflight; duplicate live attempt
    identities are rejected without a second lease, and `ProducerLease` provides one idempotent
    success/failure delivery through FLUSHING at lines 559–624. Termination is fenced by outstanding
    leases and guarded by an assert-plus-diagnostic invariant at lines 408–430.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/CompletionServiceStage.java:12`
    and `CompletionServiceFuture.java:14` — every implicit, explicit, late, and
    `toCompletableFuture()` continuation is routed through an isolated notification task; the
    CompletableFuture bridge preserves that rule after control-service termination at lines
    237–249 and `CompletionServiceStage.java:297–307`.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:61`
    — the operation result exposes only the isolated stage and publishes its finalized immutable
    `OperationResult` through the service at line 301 rather than completing it on the control
    executor.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:174`
    — a forever-blocked direct-executor consumer is held across another operation notification, a
    coordinator transition, and flush; lease preflight/FLUSHING delivery, duplicate identity and
    delivery idempotency, cross-attempt isolation, and the preflight/flush race are covered through
    line 284.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletionTest.java:193`
    — verifies operation publication on notification tasks and an explicit control-executor
    continuation attached through the CompletableFuture bridge after service termination at lines
    238–260.
- **Deviations:** No Gradle command was run, as directed. Focused Java 21
  `javac --release 21 -Xlint:all` production/test compilation passed; all 38 task-scoped
  deterministic tests passed with assertions enabled in 20 consecutive post-review runs.
  `git diff --check`, explicit trailing-whitespace checks, and the 130-column check passed. The
  terminal Standards and Spec reviews both pass after explicit-executor, duplicate-attempt, and
  termination-invariant hardening.
- **Attack points:**
  - Task 12 must mechanically replace
    `DefaultFoliaRegionDispatcher.java:255` with a failure-aware registration. Preserve the current
    final `DrainWaiter.producer` shape by creating an `AtomicReference<DrainWaiter>`, registering
    `failure -> failDrain(waiterReference.get(), failure)`, constructing the waiter with that
    producer, setting the reference, and only then calling `onFlushing`, `schedule`, or `execute`.
    `failDrain` must CAS the waiter terminal once, remove it, cancel its deadline task, and complete
    its internal future exceptionally without running consumer code on the control executor; add
    the `AtomicReference` import.
  - The same task-12 alignment must replace the control-executor continuation wrappers at
    `DefaultFoliaRegionDispatcher.java:288` and `:516` with
    `completionService.isolateStage(waiter.future)` and
    `completionService.isolateStage(submission.future)`. Keep those raw futures private, settle
    dispatcher accounting before publication at lines 454–462, and route the internal completions
    at lines 332, 457, 459, and 507 only through those isolated views. The obsolete
    `FoliaCompletionStage`/`FoliaCompletableFuture` pair can then be removed if no other caller
    remains.
  - Task 13's fresh implementation must inject the shared service, allocate its monotonic opaque
    `admissionAttemptId`, call `tryAcquireLease(id)` before `OperationCompletion.register` and before
    accounting, return `lease.future()`, and settle exactly once via `deliver` or
    `deliverExceptionally`. Any failure after lease acquisition but before waiter installation must
    use `deliverExceptionally` so flush cannot retain an abandoned lease; no private completion
    executor or fallback drainer remains. Task 14 passes the same backend-owned service through.
- **Escalation:** None. Corrective-2 implementation and independent terminal re-reviews conform to
  the rewritten §3.6b notification boundary and §3.6c producer-fence contract; the known task-12
  source obligations above are intentionally recorded rather than edited out of scope.

### Corrective 3 (2026-07-20)

- **Status:** NEEDS_CONTEXT — scope points 1–3 and 5–9 are implemented and verified. Point 4
  cannot be finalized because co-signed r11 landed during this corrective and expressly rescinds
  the r10 admission-lease/plan-registration coupling required by the dispatch. The current partial
  tree still contains the r10 capability shape and must not be treated as final against r11.
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:101`
    — mandatory keyed producer registration and expiry hooks; the now-superseded r10 lease
    capability overload is at line 126, ACCEPTING-only preflight at line 178, off-control boundary
    invocation at line 398, exact producer claiming at line 433, isolated publication startup at
    line 553, deadline expiry at line 616, and exactly-once hook execution at line 654.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:209`
    — post-termination inline work is documented and tick-thread rejected before serialization at
    lines 221–224; producer transitions fail loudly after completion at lines 774–806, and lease
    delivery remains idempotent per attempt at lines 952–989.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:64`
    — admission holds a lifecycle producer; the partial r10 claimed-plan overload is at lines
    78–110, contradictory terminals are counted separately at lines 204–211, plan/admission/
    finalizer drain terminalizers are at lines 310–371, and result publication reserves the
    terminal transition at lines 397–415.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:562`
    — boundary contracts document off-control invocation and finite coverage; finalizer invocation
    starts after its deadline at lines 713–733 and persistence arms its attempt timer before the
    boundary hop at lines 808–848.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/SameChunkPlanSequencer.java:59`
    — a queue head may terminalize before ready publication; ready publication is isolated at line
    92, drain expiry releases the lane at lines 95–138, and every ready view is isolated at line 154.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/ChunkTerminalRecord.java:18`
    and `OperationResult.java:17` — null applied receipts are rejected at lines 21 and 22.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:64`
    — pinned producer/flush race; FLUSHING producer claim, tick rejection, notification-lock
    isolation, capability mismatch/reuse, exactly-once expiry, and flush-winning preflight are at
    lines 134, 212, 240, 399, 436, and 464.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletionTest.java:78`
    — pinned duplicate/contradictory terminal races and terminal/admission-close race at lines 78,
    109, and 144; real drain expiry, truthful applied-state freezing, post-flush admission closure,
    blocked boundary prefixes, and unleased registration are at lines 677, 697, 745, 765, 808,
    and 888.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/SameChunkPlanSequencerTest.java:55`
    — overflow/uniqueness, immediate head terminal, lock-isolated ready consumer, post-termination
    ready isolation, and lane expiry are at lines 55, 69, 91, 121, and 142.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/CompletionProtocolTestSupport.java:87`
    — shared drain-expiry, leased-plan registration, bounded race, and pinned-control fixtures at
    lines 87, 97, 128, and 163.
- **Scope disposition:**
  1. DONE — `Registration.ready()` is routed through `isolateStage`; the late continuation test
     asserts it runs on neither the completion-control thread nor the attaching thread.
  2. DONE — corrected in Corrective 6: the admission lifecycle producer structurally prevents
     termination while registration is open; drain expiry closes registration, and the closed
     guard suppresses post-termination redispatch. Publication reservation controls result-state
     visibility but is not the mechanism that closes this defect.
  3. DONE — producer registration atomically installs bounded idempotent expiry hooks; FLUSHING
     publishes one absolute deadline, expiry queues each hook once, and producers deregister even
     when a hook fails. Actual missing-plan and truthful-applied-receipt tests drive real flush
     expiry rather than checking an enum.
  4. NEEDS_CONTEXT — r10 capability acquisition/consumption and its negative tests are present,
     but architecture §3.6c lines 941–958 now says r11 RESCINDS that coupling in favor of an
     independent `PlanProducerToken` and waiter-only `AdmissionProducer`.
  5. DONE — tick callers fail before the post-termination serialization lock; the fallback is
     documented as bounded detached-state work only, while coordinator/sequencer producers fence
     valid live transitions.
  6. DONE — finalizer and persistence boundary prefixes execute on service-owned virtual threads;
     settlement and per-attempt deadlines are armed first and cover synchronous prefixes. Tests
     hold each prefix indefinitely while drain expiry still completes.
  7. DONE — only non-head termination is rejected; a head can terminate before asynchronous ready
     publication and advances its successor.
  8. DONE — contradictory duplicates have a distinct diagnostic counter; completing-producer
     submissions throw; notification task creation occurs outside `lifecycleLock`; null applied
     receipts fail at construction.
  9. DONE for implemented seams — the suite uses the shared control-thread blocker plus bounded
     latches to pin all named races. The actual publication-thread diagnostic is asserted, and
     sequencer tests can observe a blocked consumer while another serialized transition proceeds.
- **Deviations:**
  - Amendment 3 remains completely untouched: `closeAdmission()` has no parameter, empty-map
    classification is unchanged, and the prohibited rejected-only classification assertions are
    neutralized with explicit `// pending Amendment 3` markers at
    `DefaultOperationCompletionTest.java:896` and `:933`.
  - No Gradle workload ran. One independent review probe attempted the wrapper, but the sandbox
    rejected its lock before Gradle configuration. Focused Java 21 `javac --release 21 -Xlint:all`
    production/test compilation and the standalone assertion-enabled runner passed all 54 task
    tests; `git diff --check` and the 130-column scan passed.
  - The r10 producer-registration capability implementation at
    `OperationCompletionService.java:126–164` and `DefaultOperationCompletion.java:78–110` is a
    known superseded partial, retained only because r11 supplies semantics but no frozen SPI
    signature with which to replace it safely in this bounded round.
- **Attack points:**
  - **Task-13 migration recipe is blocked by r11.** Current calls are
    `completionService.tryAcquireLease(attemptId)` at `DefaultFoliaBackpressure.java:169` and
    `completionService.<Permit>tryAcquireLease(attemptId)` at line 373. The r10 mechanical recipe
    would have changed both to `tryAcquireLease(attemptId, planRegistrationKey)` followed by
    `registerProducer(lease, planRegistrationKey, waiterExpiryHook, transitionFailure)` before
    waiter/accounting mutation, then stored that producer on the waiter/permit. DO NOT apply that
    recipe: r11 now assigns task 13 only a waiter-specific `AdmissionProducer`, acquired immediately
    before asynchronous waiter accounting, while synchronous `tryAcquire` must touch no completion
    service. An exact after-call shape cannot be stated until the frozen APIs below are supplied.
  - Once r11 APIs are frozen, task 13 must mechanically: remove completion-service preflight from
    synchronous `tryAcquire`; acquire one waiter-specific admission producer before each async
    waiter enters accounting; register its O(1) expiry/failure settlement; schedule its deadline
    through that producer; hop cancellation through it; and retain it through immutable outcome
    publication. Task 14 must independently preflight and consume one plan producer token before
    `OperationCompletion.register`, then use it for every terminal path. These are semantic steps,
    not an invented signature recipe.
  - Task 12 still calls obsolete `registerProducer(failure)` at
    `DefaultFoliaRegionDispatcher.java:258`, and its mock expects that overload at
    `DefaultFoliaRegionDispatcherTest.java:497`. The mechanical alignment must use the general
    keyed registration overload with a unique drain-waiter key, a bounded drain-expiry settlement
    hook, and the existing `failDrain` transition callback; construct/publish the waiter before any
    `onFlushing`, scheduling, or transition submission.
  - Amendment 3 remains for its owner: add the frozen close-admission evidence parameter and
    rejected-only/empty classification rule only after the user's signature. This task did not
    change either behavior.
- **Escalation:** NEEDS_CONTEXT. Please freeze the exact r11 core SPI signatures and ownership
  transitions for `PlanProducerToken`, `AdmissionProducer`, and `AdmissionDeliveryLease`, including
  (a) which service method acquires each token/producer, (b) which method atomically consumes the
  plan token at `OperationCompletion.register`, (c) whether r10's
  `registerProducer(ProducerLease, Object, FlushExpiryHook, Consumer)` and
  `claimRegisteredProducer` must be removed, and (d) the task-13 before/after call shape. Without
  those signatures, completing point 4 or publishing its required mechanical migration recipe
  would invent contract that r11 explicitly changed.

### Corrective 4 (2026-07-20)

- **Status:** DONE
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:126`
    — implements the exact `tryAcquirePlanProducer` contract; the atomic admission-producer and
    delivery-lease acquisition is at line 164, replacing every obsolete r10 lease/claim shape.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:911`
    — `PlanProducerToken` owns the three-field identity, single registration capability, terminal
    CAS, owner expiry trampoline, and coordinator handoff through line 1139.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:1141`
    — `AdmissionProducer` installs its internal pre-arm expiry trampoline at construction, arms
    before accounting, serializes submission/rejection, owns the sole clamped timer, and releases
    only after publication through line 1406; `AdmissionDeliveryLease` is at lines 1408–1427.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:73`
    — preserves the frozen `register(long, long)` signature; the non-SPI token-consuming overload
    validates service and operation ownership, derives the chunk/sequence key from the token, and
    consumes its single-use capability before accounting at lines 80–107.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/CompletionProtocolTestSupport.java:97`
    — the shared registration fixture acquires a plan token and consumes it through the non-SPI
    coordinator overload.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:137`
    — proves a live token remains consumable during FLUSHING and that both acquisitions reject
    after the fence without side effects at line 331.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:388`
    — observes admission-only rejection at the publication handoff, so publishing before accounting
    and plan terminalization fails; line 448 separately pins the competing plan-drain winner and
    asserts exactly one terminal accounting transition.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:487`
    — forces flush expiry before `arm`; the shared deliver/reject CAS race is at line 519, the
    lifecycle-lock hot-path probe at line 562, and deadline linearization at line 667.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletionTest.java:894`
    — corrected in Corrective 6 to reject wrong service/operation ownership and reach the
    single-use capability CAS through a second coordinator with the same operation identity.
- **Deviations:**
  - No Gradle command was run, as directed. Focused Java 21 `javac --release 21 -Xlint:all`
    compilation passed without warnings. The assertion-enabled standalone runner passed all 57
    task tests in 20 consecutive runs; `git diff --check` and the 130-column scan passed.
  - The independent Standards review passed. The Spec review's sole interim rejection was a test
    that let an independent plan rejection mask broken admission ordering; the admission-only
    publication seam and separately pinned competition above replace it, and terminal Spec
    re-review passed.
  - No frozen SPI changed. Amendment 3 remains untouched: `closeAdmission()` still has no argument,
    empty/rejected-only classification is unchanged, and both `// pending Amendment 3` markers
    remain at `DefaultOperationCompletionTest.java:888` and `:975`.
- **Attack points:**
  - **Task-13 mechanical migration:** remove both `tryAcquireLease` call paths and every
    `ProducerLease.deliverExceptionally` use. The synchronous `tryAcquire` fast path must not touch
    the completion service. For asynchronous `acquire`/`acquireContinuation`, use the token supplied
    by the internal `bind(PlanProducerToken)` adapter and perform, in order:
    `tryAcquireAdmissionProducer(admissionAttemptId, registeredPlan)`; capture
    `AdmissionDeliveryLease<Permit> delivery = producer.delivery()` and return `delivery.future()`;
    call `producer.arm(waiterFlushExpiry, transitionFailure)`; begin waiter/backpressure accounting
    only when `arm` returns true; call the sole
    `producer.schedule(delay, linearizeExpiry, expirySettlement)`; route cancellation and other
    coordinator transitions through `producer.submit(prebuiltSettlement)`; complete success with
    `delivery.deliver(immutablePermit)` and every failure with `producer.reject(cause)`. If
    acquisition returns empty, begin no accounting and call `registeredPlan.rejectAdmission(cause)`;
    if `arm` returns false, begin no accounting because the internal expiry trampoline already owns
    settlement. Do not close either collaborator; publication releases it.
  - **Task-14 mechanical migration:** immediately before plan registration and outside every tick
    fast path, call
    `tryAcquirePlanProducer(operationId, chunkKey, planSequence, ownerFlushExpiry, transitionFailure)`.
    If empty, do not register, account, or schedule; there is intentionally no aggregate
    pre-registration refusal channel before Amendment 3. Otherwise call
    `defaultOperationCompletion.register(token)`, then hand that same consumed token to task 13 via
    `DefaultFoliaBackpressure.bind(token)`. Every exact world terminal uses `token.terminal(record)`;
    pre-admission refusal uses `token.rejectAdmission(cause)`. The owner expiry hook freezes the
    truthful latest terminal record and claims the same token. Task 14 never releases the token;
    the completion coordinator does so after terminal processing and required persistence.
- **Escalation:** None. Co-signed §3.6d supplied every formerly missing collaborator signature and
  ownership transition; Corrective 4 implements that contract without assuming Amendment 3.

### Corrective 5 (2026-07-20)

- **Status:** DONE
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:1271`
    — adds `AdmissionProducer.cancel(CancellationException)` as the third closing entry on the
    shared attempt-level command-tail CAS; its immutable exceptional outcome bypasses plan
    rejection and retains the existing isolated publication/release lifecycle.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:1292`
    — a losing outcome entry rechecks the authoritative closed marker after observing released arm
    state and returns false rather than throwing; the package-private coordination probe at lines
    50 and 78–89 makes that exact interleaving deterministic in tests without changing public API.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:520`
    — races delivery, rejection, and cancellation behind a blocked control worker, asserts exactly
    one true return, and verifies the isolated future reflects the winning immutable outcome.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:577`
    — proves cancellation leaves the plan unterminated and permits a second admission attempt with
    the same registered plan token and sequence; rejection's `NOT_ACCEPTED` consequence is asserted
    independently at line 618.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:643`
    — pauses cancellation after its initial open-tail observation, lets delivery publish and fully
    release, then proves the losing cancellation returns false without throwing or disturbing the
    delivered outcome.
- **Deviations:**
  - No Gradle command was run, as directed. Focused Java 21 `javac --release 21 -Xlint:all`
    compilation passed without warnings. All 60 task tests passed with assertions enabled in 20
    consecutive final runs; `git diff --check` and the 130-column scan passed.
  - The package-private outcome-entry probe is a test-only coordination seam with a no-op production
    default. It was required to reproduce the losing-CAS release window deterministically; no
    public or frozen SPI changed. Independent terminal Standards and Spec reviews passed.
  - Amendment 3 remains untouched: `closeAdmission()` and empty/rejected-only classification are
    unchanged, and both existing `// pending Amendment 3` markers remain in place.
- **Attack points:** Task 13 calls `cancel(cause)` only for user cancellation after settling any
  waiter/permit accounting when the registered plan remains viable for retry; it calls
  `reject(cause)` for admission refusal/failure that must terminalize the plan `NOT_ACCEPTED` and
  require a new plan sequence. The r14 Q2 permit-lifecycle accounting change remains entirely with
  task 13.
- **Escalation:** None. The r14 Q1 ruling fully specifies the third outcome and its plan consequence.

### Corrective 6 (2026-07-20)

- **Status:** DONE
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:29`
    — adds the package-private admission-close dispatch probe used to observe the closed-admission
    guard without changing the public or frozen SPI; the guarded dispatch is at lines 134–143.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:329`
    — requests the completion check after releasing the coordinator monitor; lines 415–428 retain
    the publication reservation but remove the unreachable rollback to `OPEN`.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:521`
    — renames the atomic publication handoff to `reservePublication`, removing the false locked
    suffix.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:600`
    — drain expiry retains the load-bearing `OPEN`-to-`EXPIRING` CAS while relying on its one locked
    producer snapshot instead of a decorative membership guard; structural quiescence and
    unconditional producer release are at lines 663–687.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:1001`
    — removes tautological token-identity parameters while preserving the single-use registration
    capability CAS.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:1134`
    — installs an admission flush hook at acquisition, atomically publishes the deadline actions at
    line 1221, and re-clamps a scheduled admission at lines 1260–1272.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/SameChunkPlanSequencer.java:12`
    — states the retained global monitor's worker/control-plane calling contract.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletionTest.java:677`
    — replaces the enum-content assertion with real drain-expiry behavior; lines 736–776 pin the
    admission producer fence and closed-admission guard, and lines 917–945 reach the capability CAS
    after validating service and operation ownership.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:719`
    — extends the reflected lifecycle-lock hot-path probe to `cancel`; lines 878–918 prove an
    already-scheduled admission receives a timer no later than the published flush clamp.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/SameChunkPlanSequencerTest.java:69`
    — states and proves the actual early-head-terminal property: the successor lane cannot wedge.
- **Disposition:**
  - BLOCKING 2 is actually closed by the admission lifecycle producer: it remains counted while
    registration is open, so `terminateIfQuiescent` cannot terminate the service. Drain expiry
    closes registration before releasing that producer, and the `registrationOpen` guard then
    suppresses any post-termination redispatch. The line-736 regression observes all three facts
    and fails if either the producer fence or the guard is removed.
  - The rollback to `OPEN` was removed. It was unreachable under the counted-producer invariant and
    unsafe as defence-in-depth because a failed post-termination resubmission has no remaining
    monotone transition to drive it. Publication reservation remains the state-visibility handoff,
    not the mechanism which closes BLOCKING 2.
  - Admission deadlines now install their flush re-clamp hook before producer publication. The
    original and drain-clamp timers share one immutable, atomically published deadline action and
    the same outcome linearizer; either may fire, and publication cancels both.
  - Registration capability tests now reject wrong service and operation ownership before
    mutation, then use a same-operation sibling coordinator to reach and lose the consumed token's
    single-use CAS. Chunk and sequence are derived from the token rather than independently
    revalidated.
  - The misleading `reservePublicationLocked` name was corrected. The coordinator monitor no
    longer calls `requestCompletionCheck`, removing its latent monitor-to-service-lock path.
  - The sequencer monitor remains: registration is a worker-side allocator and terminal sequencing
    is control-plane work, neither a tick-thread entry point; no service-lifecycle-to-sequencer
    inverse exists in this implementation.
  - Every identified impossible guard was disposed of structurally: the TERMINATED/live-token
    guard was removed because collaborator maps and `producers` mutate together under
    `lifecycleLock`; the flush snapshot membership guard was removed because one `expireFlush` pass
    owns the locked snapshot, while the adjacent lifecycle CAS remains because lock-free normal
    completion can race expiry; and the null producer-release guard was removed because both
    callers are producer-owned paths. The scoped production audit found no remaining decorative
    `assert false` guard.
  - Test names and assertions now match behavior: early head terminal proves lane progress, drain
    expiry proves `CANCELLED_BEFORE_MUTATION`, and flush re-clamping compares the scheduled timer
    directly with the requested drain duration. The `cancel` path is included in the same reflected
    no-`lifecycleLock` probe as the other §3.6d hot paths.
- **Deviations:**
  - Amendment 3 remains untouched. `closeAdmission()` has no new parameter, classification behavior
    is unchanged, and both `// pending Amendment 3` markers remain at
    `DefaultOperationCompletionTest.java:912` and `:1061`.
  - No Gradle command was run. Focused Java 21 `javac --release 21 -Xlint:all` compilation passed;
    the assertion-enabled runner passed all 61 task tests in 20 consecutive final runs. The
    Standards and Spec reviews passed after the atomic deadline-action publication and exact clamp
    assertion were tightened.
- **Attack points:** Task 14 must preserve the admission lifecycle producer through admission
  closure and keep `SameChunkPlanSequencer` registration/terminal calls off tick threads. Task 13
  may rely on `AdmissionProducer.schedule` being re-clamped automatically when flushing begins; it
  must not add a second lifecycle-locking clamp path.
- **Escalation:** None.

### Corrective 7 (2026-07-20)

- **Status:** DONE
- **File List:**
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:306`
    — exposes unconsumed-token, abandonment, last-cause, unarmed-admission, and exact producer-release
    diagnostics; the release count is package-private test evidence.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:638`
    — orders admission-producer drain terminalizers before plan terminalizers so an unarmed admission
    can establish the required `NOT_ACCEPTED` plan consequence before the plan drain competes.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:1010`
    — implements `PlanProducerToken.abort(Throwable)` as a synchronous lifecycle-locked
    `ACQUIRED`-to-`ABORTED` ownership transfer, diagnostic, and producer deregistration; consumed
    states throw and prior abort/expiry states return false.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:1134`
    — unconsumed token drain expiry records a bounded abandonment diagnostic and releases through
    the existing producer expiry transition without invoking a plan terminalizer.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/OperationCompletionService.java:1390`
    — makes rejection before `arm` legal and atomically visible as `PreArmRejection`; drain expiry
    helps publish that existing rejection without falsely diagnosing abandonment. Genuine unarmed
    expiry is diagnosed and force-rejected at lines 1509–1544.
  - `worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletion.java:108`
    — makes token registration transactional: it inserts provisionally, transfers ownership once,
    rolls back provisional insertion on failure, aborts every still-unconsumed rejected token, and
    rethrows the original validation/insertion failure.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/CompletionProtocolTestSupport.java:125`
    — factors unconsumed plan-token acquisition for registration and abandonment tests.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/DefaultOperationCompletionTest.java:918`
    — drives wrong-service, wrong-operation, closed-admission, and duplicate-key registration
    rejection separately; each proves the token producer returns to baseline and flush terminates.
    Consumed-token abort and capability reuse are covered at line 1001.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:494`
    — pins abort and reflected drain expiry behind `lifecycleLock`, proves both contenders are queued,
    and asserts exactly one release and one diagnostic. Bounded abandoned-token expiry is at line 557.
  - `worldedit-core/src/test/java/com/fastasyncworldedit/core/util/task/OperationCompletionServiceTest.java:571`
    — proves `arm == false` yields plan `NOT_ACCEPTED`, exceptional admission publication, release,
    and one unarmed diagnostic. Lines 609–645 hold a legal pre-arm rejection publication across
    drain expiry and prove it remains registered without an abandonment diagnostic.
- **Deviations:**
  - No GC finalizer or `Cleaner` participates in correctness. Explicit abort and the drain-expiry
    terminalizer are the only unconsumed-token release mechanisms.
  - The mandated public `abort` method and diagnostic readers are the only collaborator-surface
    additions. `releasedProducerCount()` is package-private and exists solely to make duplicate
    deregistration falsifiable in the pinned race test.
  - Amendment 3 remains untouched: `closeAdmission()` and classification behavior are unchanged,
    and both `// pending Amendment 3` markers remain at
    `DefaultOperationCompletionTest.java:912` and `:1061`.
  - No Gradle command was run. Focused Java 21 `javac --release 21 -Xlint:all` compilation passed;
    all 65 assertion-enabled task tests passed in 20 consecutive terminal runs. Independent
    Standards and Spec re-reviews both passed after the pinned race and legal pre-arm rejection
    publication tests were tightened.
- **Attack points:**
  - **Task-13 migration:** after `tryAcquireAdmissionProducer(...)`, call `reject(cause)` for every
    setup failure before `arm` or accounting. If `arm(...)` returns false, start no accounting and
    make no cleanup call: the expiry trampoline owns plan `NOT_ACCEPTED`, exceptional publication,
    diagnostic, and producer release. After a successful `arm`, retain the existing accounting →
    `schedule` → `deliver`/`reject`/`cancel` order and never abandon the admission producer.
  - **Task-14 migration:** every successful `tryAcquirePlanProducer(...)` creates a linear
    obligation. Normally call `completion.register(token)` immediately; any exception from that
    call already aborts an unconsumed token transactionally, so do not abort it again. If task 14
    elects not to call `register` at all, call `token.abort(cause)`. After successful registration,
    never call `abort`; pass the token through `bind(token)` and finish through `terminal(record)`
    or `rejectAdmission(cause)`. `Optional.empty()` still creates no token and Amendment 3 remains
    the only future pre-registration refusal channel.
- **Escalation:** None.
