# Task 13 — FoliaBackpressure: full Demand admission model (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** none structurally (uses folia-module-internal `RegionKey`). Consumed by
  tasks 14, 16, 17.
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: queue pipeline /
  scheduler SPI. Double-reviewer (concurrency & thread-ownership) — admission cycles and
  caller-runs are the liveness failure modes (spec §1b).

## Mandate
Implement the per-region bounded admission model frozen in architecture §3.4 (F4): the full
Proposal-B SPI restored, with the three r1 corrections — owner-thread nonblocking `tryAcquire`,
reserved continuation capacity (`acquireContinuation`), and permit transfer on region
merge/split (`transfer`). This is the sole gate that prevents unbounded prepared work and the
liveness violations (owner-bound caller-runs, finalizer continuations queuing behind the commits
they unblock).

## In scope
- `FoliaBackpressure` implementation (folia module; the interface + `Demand`/`Limits`/`Pressure`/
  `Permit`/`Priority`/`Stage` records are already frozen in tree — create the impl class):
  - `acquire(region, demand, cancellationSignal)`: completes on a FAWE executor; **never called
    from a tick thread**; cancellation propagates.
  - `tryAcquire(region, demand)`: owner-thread admission — immediate, nonblocking, returns
    `Optional.empty()` instead of ever waiting; the ONLY admission entry legal from a tick
    thread (G-A1 uses it exclusively).
  - `acquireContinuation(region, demand, cancellationSignal)`: draws from a **reserved
    per-region continuation budget carved out of (not added to) the finalizer capacity**; never
    queues behind the commits it unblocks.
  - `transfer(permit, actualRegion)`: move accounting to the actual current region before any
    mutation under a stale-region permit; returns false when the target cannot admit now (plan
    defers/fails before mutation — never mutates under stale accounting).
  - `Permit` lifecycle: READY → SCHEDULED → COMMITTING → FINALIZING → close(); stages advance
    only in declared order (invalid transition terminates that chunk plan exceptionally);
    `close()` idempotent, releases all remaining accounting.
  - `pressure(region)` snapshot; `recordScheduleDelay` / `recordSlice` EWMA feeds; `limits()`;
    `stopAccepting(reason)`.
- Initial certification values (config-backed, `[NEEDS-RUNTIME]`, architecture §3.4): 256 ready
  chunks/region · 64 MiB prepared SET/region · 64 finalizer chains/region · 256 waiters/region ·
  65,536 global ready chunks · `min(1 GiB, maxHeap/4)` global prepared bytes · 4,096 global
  finalizer chains. Per-region limits do NOT replace the global byte cap; global caps bound
  process resources only, never a per-region health signal.
- **Saturation behavior** (architecture §3.4 / Proposal B §5.3): no owner-bound task on the
  submitting thread; worker pipeline stops producing for a saturated region; bounded async
  waiters; legacy sync A/W callers await with bounded deadline and no locks; R/E/G callers that
  would need to wait are rejected before accepting mutations; full waiter queue or expired
  deadline → rejection before acceptance (terminalized `NOT_ACCEPTED` per §3.6, via the task-11
  completion sink — expose the hook, do not wire it here). **No caller-runs for owner-bound
  work, ever.**

## Out of scope
- The `DiagnosticSnapshot` producer (§3.4): it lives on `FoliaCommitBroker` and aggregates
  `pressure()` + dispatcher counters + broker accounting — that is task 14. Here, just make
  `pressure()` return the frozen `Pressure` fields it needs.
- Lane drain / DRR / slice controller (task 14). Backpressure only accounts; the broker drains.
- Wiring `NOT_ACCEPTED` terminalization into `OperationCompletion` (task 14 does the wiring;
  task 11 owns the sink).

## Binding references
- architecture.md §3.4 (the entire frozen SPI + corrections + certification values + saturation
  + the frozen `DiagnosticSnapshot` note), §1b (liveness invariant: no caller-runs for
  owner-bound work; acyclic bounded cancellable worker→owner waits only), §3.7 (`recordSlice`/
  `recordScheduleDelay` feed the adaptive slice controller), C6 (per-region signals replace
  global TPS).
- spec §8 (bounded resources + ownership-relevant signals, not fabricated global TPS),
  §4b (correctness > performance).
- In-tree frozen: `FoliaBackpressure.java` (interface + all records), `RegionKey.java`.

## Signature / structural flags
- `Permit.region()` returns `RegionKey` (folia-internal, F2-legal). `RegionKey` shape is not
  frozen — task 14 (broker) is its primary author; coordinate so both tasks agree on its
  identity/equality contract. If you need `RegionKey` identity semantics before task 14 exists,
  define the minimal contract and flag it for task-14 reconciliation.
- The reserved continuation budget has **no separate `Limits` field** by design (carved out of
  `maxFinalizersPerRegion`); do not add one — implement the carve-out internally.

## Files to touch
- `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/` — CREATE the
  `FoliaBackpressure` impl. May touch `RegionKey.java` for the identity contract (coordinate
  with task 14 — single-writer per architecture §5; propose diff, orchestrator sequences).

## Context & decisions
- Correctness > performance (spec §4b): the admission gate must never admit work it cannot
  bound; a rejected-before-acceptance plan must terminalize cleanly (no silent loss).
- Async-first: `acquire`/`acquireContinuation` return `CompletionStage`; only FAWE workers ever
  await, never tick threads (spec §1b).
- Fail-fast internal on invalid stage transitions.

## Executor constraints
- Folia module compiles at Java 25. No git commits. No FQN inline. English. Gradle always
  `--no-configure-on-demand`.

## Escape hatch
If `RegionKey` identity cannot be settled without task 14, deliver the accounting logic against
a minimal locally-specified `RegionKey` contract and flag the reconciliation (NEEDS_CONTEXT)
rather than guessing the broker's lane-grouping semantics.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- `tryAcquire` provably never blocks (returns `Optional`, no await path).
- Stage machine rejects out-of-order transitions; `close()` idempotent.
- Continuation budget is carved from finalizer capacity, not additive (assert in a test).
- No caller-runs path for owner-bound work exists (grep + review).

## Test / verification strategy
Targeted deterministic tests (spec §6): admission saturation → `NOT_ACCEPTED`; `tryAcquire`
non-blocking; continuation reservation; `transfer` refusal path (no mutation under stale
accounting); stage-order enforcement. Runtime pressure signals confirmed by harness perf-probe
post-wiring:
`JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
harness/scenario.sh perf-probe --without-plugin --version 26.1.2`. Numeric budget values remain
`[NEEDS-RUNTIME]` until the W1-exit numeric freeze (Amendment A2.3) — record which slots you
left config-driven.

---
## Dev record (worker fills this in on completion)

- **Status:** DONE_WITH_CONCERNS
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java` — bounded regional/global admission, reserved continuation partitions, permit lifecycle/transfer, pressure/EWMA signals, cancellation/deadline/stop handling, rejection hook, and fail-closed completion fallback (`DefaultFoliaBackpressure.java:89`, `DefaultFoliaBackpressure.java:153`, `DefaultFoliaBackpressure.java:529`, `DefaultFoliaBackpressure.java:900`).
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java` — nine deterministic tests covering certification limits, asynchronous and rejected completion execution, deadlines, stages, continuation carve-out, transfer, cancellation, and waiter saturation (`DefaultFoliaBackpressureTest.java:58`, `DefaultFoliaBackpressureTest.java:71`, `DefaultFoliaBackpressureTest.java:235`).
  - `.orchestrate/folia-port/tasks/13-backpressure.md` — this Dev record (`13-backpressure.md:121`).
- **Deviations:**
  - The Gradle graph compile and runtime perf probe were not run: the dispatcher explicitly prohibited Gradle, and the probe is post-wiring. Java 25 `javac --release 25 -Xlint:all -Werror`, a standalone runner for all nine deterministic tests, `git diff --check`, and the 120-column check passed.
  - `RegionKey.java` was deliberately not changed because task 14 remains its primary author. Tests use the current skeleton and require reconciliation when identity/equality lands (`DefaultFoliaBackpressureTest.java:76`, `DefaultFoliaBackpressureTest.java:191`).
  - Public `Limits` and the internal continuation carve remain constructor-injected because the binding design freezes no separate continuation field or runtime carve value; exact initial certification values are exposed by `initialLimits` (`DefaultFoliaBackpressure.java:89`, `DefaultFoliaBackpressure.java:117`, `DefaultFoliaBackpressure.java:132`).
- **Attack points:**
  - Recheck the regional/global regular-versus-continuation carve and continuation-first, rotating bounded waiter service (`DefaultFoliaBackpressure.java:529`, `DefaultFoliaBackpressure.java:630`, `DefaultFoliaBackpressure.java:644`).
  - Recheck that READY accounting releases only on FINALIZING, invalid transitions release and fail fast, and refused transfers leave the permit region unchanged (`DefaultFoliaBackpressure.java:210`, `DefaultFoliaBackpressure.java:709`, `DefaultFoliaBackpressure.java:749`).
  - Recheck that completion-executor failure closes new admission and drains already-capped completions through one FAWE-marked emergency worker; task 14 must wire the rejection hook exactly once into task 11's completion sink (`DefaultFoliaBackpressure.java:900`, `DefaultFoliaBackpressure.java:910`, `DefaultFoliaBackpressure.java:922`, `DefaultFoliaBackpressure.java:957`).
- **Escalation:** NEEDS_CONTEXT — task 14 must define/reconcile `RegionKey` identity/equality and integration must supply the FAWE completion executor, task-11 rejection hook, configured `Limits`, and numeric continuation carve. No co-signed signature fixes those integration values, so the implementation keeps them injected (`DefaultFoliaBackpressure.java:89`, `DefaultFoliaBackpressure.java:98`).

### Corrective 1

- **Status:** DONE_WITH_CONCERNS — this subsection supersedes the original record's completion-fallback description, test count, and line references.
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java` — corrected completion serialization, idle-region eviction, nonblocking telemetry, bounded transfer contention, waiter fairness documentation, and independent global waiter cap (`DefaultFoliaBackpressure.java:51`, `DefaultFoliaBackpressure.java:197`, `DefaultFoliaBackpressure.java:237`, `DefaultFoliaBackpressure.java:814`, `DefaultFoliaBackpressure.java:929`).
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java` — twelve deterministic tests, including transient rejection/marker, serialized inline fallback, eviction/recreation, and owner-telemetry contention coverage (`DefaultFoliaBackpressureTest.java:93`, `DefaultFoliaBackpressureTest.java:128`, `DefaultFoliaBackpressureTest.java:276`, `DefaultFoliaBackpressureTest.java:303`).
  - `.orchestrate/folia-port/tasks/13-backpressure.md` — appended this corrective record; no other task file was touched.
- **Corrections:**
  - **B1:** Removed `FaweBasicThreadFactory` and the emergency worker entirely. Rejected completion work executes on the submitting thread; task 13 creates no dedicated fallback thread and adds no `FaweThread` marker or extent slot (`DefaultFoliaBackpressure.java:929`, `DefaultFoliaBackpressure.java:940`; `DefaultFoliaBackpressureTest.java:93`).
  - **M1:** Added explicit live-permit accounting and identity-safe pruning only after every permit, reservation, waiter, counter, and ready timestamp reaches zero. Transfer prunes the old region only after all accounting moves; tests prove eviction, retained target accounting, and later recreation (`DefaultFoliaBackpressure.java:227`, `DefaultFoliaBackpressure.java:497`, `DefaultFoliaBackpressure.java:704`, `DefaultFoliaBackpressure.java:785`, `DefaultFoliaBackpressure.java:814`; `DefaultFoliaBackpressureTest.java:276`).
  - **M2:** Chose option (a): rejection is per-submission and never latched. Accepted and rejected commands both cross `completionLock`; rejection runs that command inline through the same serialized queue, including after `stopAccepting`, with no concurrent fallback drain or abandoned daemon work (`DefaultFoliaBackpressure.java:65`, `DefaultFoliaBackpressure.java:929`, `DefaultFoliaBackpressure.java:940`; `DefaultFoliaBackpressureTest.java:93`, `DefaultFoliaBackpressureTest.java:128`).
  - **N1:** `pressure()` is lock-free over concurrently readable state, while both EWMA writers use `tryLock` and drop samples on contention; absent-region telemetry no longer creates retained states (`DefaultFoliaBackpressure.java:237`, `DefaultFoliaBackpressure.java:260`, `DefaultFoliaBackpressure.java:279`; `DefaultFoliaBackpressureTest.java:303`).
  - **N2:** `transfer()` now waits for the accounting lock for at most 100 microseconds before returning false (`DefaultFoliaBackpressure.java:52`, `DefaultFoliaBackpressure.java:204`, `DefaultFoliaBackpressure.java:871`).
  - **N3:** Documented the work-conserving rotation tradeoff: feasible large demands may be bypassed, but their mandatory finite deadline bounds starvation (`DefaultFoliaBackpressure.java:585`).
  - **N4:** Added the independent internal `MAX_GLOBAL_WAITERS` ceiling; waiter admission no longer overloads `maxGlobalReadyChunks` (`DefaultFoliaBackpressure.java:51`, `DefaultFoliaBackpressure.java:613`).
- **Verification:** Java 25 `javac --release 25 -Xlint:all -Werror` passed for production and tests; the standalone twelve-test runner passed five consecutive runs; `git diff --check`, 120-column checks, and parallel Standards/Spec corrective reviews passed. Gradle and the post-wiring perf probe were not run because the dispatcher prohibited Gradle and runtime wiring is not present.
- **Attack points:**
  - Re-review completion ordering around the shared `completionLock` and recursive direct executors (`DefaultFoliaBackpressure.java:929`, `DefaultFoliaBackpressure.java:940`; `DefaultFoliaBackpressureTest.java:128`).
  - Re-review the eviction predicate whenever task 14 adds region-scoped state; live permits and waiters currently prevent premature removal (`DefaultFoliaBackpressure.java:814`; `DefaultFoliaBackpressureTest.java:276`).
  - Runtime-check the 100-microsecond transfer lock bound and deadline-bounded large-demand fairness during the post-wiring perf probe (`DefaultFoliaBackpressure.java:52`, `DefaultFoliaBackpressure.java:585`, `DefaultFoliaBackpressure.java:871`).
- **Escalation:** NEEDS_CONTEXT — unchanged: task 14 must define/reconcile `RegionKey` identity/equality and integration must supply the completion executor, task-11 rejection hook, configured `Limits`, and numeric continuation carve (`DefaultFoliaBackpressure.java:85`, `DefaultFoliaBackpressure.java:94`).

### Corrective 2

- **Status:** BLOCKED — terminal corrective review is red; per dispatcher instruction, implementation stops here for orchestrator re-planning rather than another iteration.
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java` — added tick-context detection, a plain lazy fallback thread, lock-free rejected-tick enqueue, bounded completion batches outside locks, fallback stop/join handling, and the two telemetry caveat comments (`DefaultFoliaBackpressure.java:72`, `DefaultFoliaBackpressure.java:275`, `DefaultFoliaBackpressure.java:858`, `DefaultFoliaBackpressure.java:974`, `DefaultFoliaBackpressure.java:1038`, `DefaultFoliaBackpressure.java:1078`).
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java` — replaced the corrective-1 blocking-serialization assertion with stalled-continuation, deadline-expiry, rejected-tick, unmarked-fallback, and shutdown-join coverage (`DefaultFoliaBackpressureTest.java:129`, `DefaultFoliaBackpressureTest.java:173`, `DefaultFoliaBackpressureTest.java:222`).
  - `.orchestrate/folia-port/tasks/13-backpressure.md` — appended this terminal corrective record; no other task file was touched.
- **Implemented:**
  - Rejected tick submissions use injected production detection backed by `FaweThreadContext.current().isTickThread()`; unregistered resolver exceptions fall back to non-tick. Tick rejection enqueues without taking `completionLock` and starts a plain platform fallback thread instead of draining inline (`DefaultFoliaBackpressure.java:974`, `DefaultFoliaBackpressure.java:996`, `DefaultFoliaBackpressure.java:1038`, `DefaultFoliaBackpressure.java:1103`).
  - Completion commands are claimed in batches of one under `completionLock` and callbacks execute after the lock is released (`DefaultFoliaBackpressure.java:57`, `DefaultFoliaBackpressure.java:1011`, `DefaultFoliaBackpressure.java:1028`).
  - The fallback factory creates an unmarked non-daemon platform thread, and `stopAccepting` requests shutdown, interrupts, and joins the observed fallback (`DefaultFoliaBackpressure.java:341`, `DefaultFoliaBackpressure.java:1078`, `DefaultFoliaBackpressure.java:1111`).
  - Documented that idle-region eviction resets EWMA state and that lock-free pressure snapshots may mix adjacent updates (`DefaultFoliaBackpressure.java:286`, `DefaultFoliaBackpressure.java:871`).
- **Terminal review findings:**
  - **BLOCKING:** There is no single-drainer guard across batch execution. Accepted executor tasks, the fallback, and rejected non-tick callers can claim distinct batches and execute completion transitions concurrently; the stalled-continuation tests pass because of that overlap, contrary to the prescribed coordinator serialization (`DefaultFoliaBackpressure.java:978`, `DefaultFoliaBackpressure.java:987`, `DefaultFoliaBackpressure.java:1004`, `DefaultFoliaBackpressure.java:1059`; `DefaultFoliaBackpressureTest.java:129`).
  - **BLOCKING:** The deadline test forces executor rejection before the stall. With the binding accepting single-thread executor, the expiry wrapper remains behind the task stalled in `future.complete`, so the requirement that deadline expiry never be blocked is not proved or met (`DefaultFoliaBackpressure.java:974`, `DefaultFoliaBackpressure.java:1028`; `DefaultFoliaBackpressureTest.java:194`, `DefaultFoliaBackpressureTest.java:200`).
  - **MAJOR:** Fallback start and stop are not one atomic lifecycle transition. Stop can set shutdown and observe no thread while a concurrent starter that passed the earlier check subsequently CASes and starts; rejected tick work after shutdown can also remain queued with no permitted drainer (`DefaultFoliaBackpressure.java:1038`, `DefaultFoliaBackpressure.java:1046`, `DefaultFoliaBackpressure.java:1078`).
- **Verification:** Java 25 `javac --release 25 -Xlint:all -Werror` passed for production and tests; the standalone fourteen-test runner passed 25 consecutive runs; `git diff --check` and 120-column checks passed. Gradle was not run as prohibited. The terminal Standards and Spec reviews both rejected the design on the findings above, so green local tests are not acceptance evidence.
- **Attack points for re-plan:**
  - Define how coordinator state transitions remain serialized while synchronous `CompletableFuture` continuations and rejection hooks cannot head-of-line-block deadline/state work. The current single accepting executor plus synchronous `future.complete` surface does not satisfy both properties without another notification/continuation seam.
  - Make fallback `STARTING/RUNNING/STOPPING/TERMINATED` transitions atomic with enqueue/start/stop, and define behavior for tick-origin submissions racing or following shutdown.
  - Replace the corrective-2 liveness tests so they prove the re-planned serialization model rather than concurrent competing drainers.
- **Escalation:** BLOCKED — corrective round 2 is terminal and both review axes remain red. Orchestrator must re-plan the completion-serialization and fallback-lifecycle seam before further task-13 implementation; the earlier `RegionKey` integration `NEEDS_CONTEXT` also remains unresolved.

### Fresh implementation (§3.6c re-plan)

- **Status:** DONE_WITH_CONCERNS — the task-13 implementation and deterministic verification are
  complete. The frozen SPI cannot express the required lease-preflight →
  `OperationCompletion.register` → accounting order end to end; that task-14 integration seam is
  escalated below rather than hidden or implemented by changing core.
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:84`
    — injects the backend-owned `OperationCompletionService`; admission preflights producer leases
    and returns their isolated stages at lines 365–434, while monotonic opaque attempt identities
    and single-outcome delivery live at lines 991–1057.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:245`
    — retains the proven bounded accounting, continuation carve, transfer, stage/release, eviction,
    pressure, and EWMA machinery (notably lines 245–346, 529–768, and 775–975).
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java:58`
    — standalone 15-test runner; §3.6c coverage includes preflight refusal at line 128, non-terminal
    `tryAcquire` misses at line 144, blocked-consumer deadline settlement at line 166, notification
    isolation at line 213, and cancel-before-grant-delivery supersession at line 248.
  - `.orchestrate/folia-port/tasks/13-backpressure.md` — appends this fresh record without altering
    the retired worker history.
- **Salvage and §3.6c discharge:**
  - **Kept:** regional/global multi-resource accounting, continuation capacity carved from regular
    finalizer capacity, permit lifecycle, bounded transfer, waiter rotation/eviction, and
    pressure/EWMA state. The continuation carve remains asserted at
    `DefaultFoliaBackpressureTest.java:304`; transfer and ordered stages remain asserted at lines
    333 and 278.
  - **Rebuilt:** removed the private completion executor, completion lock/queue, tick detector,
    fallback drainer/thread factory, inline drain, and shutdown join. No notification thread,
    executor, or ordering lock remains in the class; only `accountingLock` serializes settlement.
  - **Clause 1:** grant/reject/expire/cancel/removal and permit accounting settle under
    `accountingLock` (`DefaultFoliaBackpressure.java:365`, `:456`, `:482`, `:775`, `:815`). They
    collect immutable `AdmissionDelivery` values and never invoke hooks or complete futures while
    locked. Deadline removal is visible before delivery (`DefaultFoliaBackpressure.java:482`).
  - **Clause 2:** the injected service is preflighted before accounting; all post-settlement
    notifications use `ProducerLease.deliver`/`deliverExceptionally` after unlock, and the
    rejection hook is installed on the service-isolated lease stage
    (`DefaultFoliaBackpressure.java:372`, `:999`, `:1049`).
  - **Clause 3:** each request receives an opaque monotonic `AdmissionAttemptId`; waiter settlement
    carries the identity and the lease provides per-attempt idempotence. `DELIVERY_CLAIMED` is the
    lock-serialized grant-delivery linearization point: cancellation before it releases the permit
    and wins exceptional delivery; cancellation after it follows normal permit lifecycle
    (`DefaultFoliaBackpressure.java:498`, `:1074`, `:1084`).
  - **Clause 4:** transfer, FINALIZING entry, and close settle synchronously under the bounded
    accounting critical section, unlock, then enqueue any waiter deliveries under those waiters'
    pre-acquired leases (`DefaultFoliaBackpressure.java:245`, `:775`, `:815`).
  - **Clause 5:** async acquire returns the completion service's isolated `lease.future()`; tests
    assert notification context and prove a forever-stalled earlier consumer does not block expiry
    settlement or later notification (`DefaultFoliaBackpressureTest.java:108`, `:166`).
- **Deviations:**
  - Gradle and the post-wiring perf probe were not run, as explicitly prohibited/not yet applicable.
    Java 25 targeted production+test compilation passed with `--release 25 -Xlint:all -Werror`; the
    standalone 15-test suite passed five consecutive runs. Full Folia-module
    `javac --release 25 -Xlint:all` passed with two existing warnings outside task 13
    (`FoliaRegionDispatcher` overload ambiguity and `FoliaQueueHandler` serial UID).
  - `RegionKey.java` remains untouched because task 14 is its primary author. Current accounting
    relies only on stable `equals`/`hashCode` map-key behavior and does not guess lane grouping.
  - All seven public `Limits` slots and the internal `continuationFinalizerLimit` carve remain
    constructor/config injected and `[NEEDS-RUNTIME]`; `initialLimits` exposes the certification
    starting values only (`DefaultFoliaBackpressure.java:84`, `:125`).
  - A `tryAcquire` `Optional.empty()` is treated as a non-terminal G-A1 eligibility miss, so its
    private lease is settled for flush fencing without invoking the terminal rejection hook
    (`DefaultFoliaBackpressure.java:207`; `DefaultFoliaBackpressureTest.java:144`).
- **Attack points:**
  - Re-review the `DELIVERY_CLAIMED` linearization definition against §3.6c item 3. No future or user
    code runs at the claim; it only reserves the lease's one delivery command before the immediate
    post-unlock `deliver` call (`DefaultFoliaBackpressure.java:498`).
  - Reconcile the producer-lease preflight with `OperationCompletion.register`: the frozen
    `FoliaBackpressure` call has no coordinator, chunk key, plan sequence, callback, or token return,
    so task 14 cannot currently insert registration between lines 372 and 386.
  - Recheck common-pool continuation of capped waiter-settlement scans. It performs accounting only,
    never notification/user work; outstanding producer leases keep completion flush fenced until
    every batch settles (`DefaultFoliaBackpressure.java:578`; batch test at
    `DefaultFoliaBackpressureTest.java:487`).
- **Escalation:** NEEDS_CONTEXT — task 14 must define/reconcile `RegionKey` identity/equality and
  the preflight-registration seam. The latter needs a co-signed package-private preflight token/
  registration callback or a frozen-SPI amendment; task 13 cannot manufacture
  `OperationCompletion.register(chunkKey, planSequence)` from `Demand`. Task 14/task 17 must also
  pass the shared backend completion service, rejection hook, configured `Limits`, and numeric
  continuation carve. No `worldedit-core` change was made or is requested without authorization.

### Fresh-implementation corrective 1 (2026-07-20)

- **Status:** BLOCKED — the r11/r13 producer migration and the bounded accounting fixes below are
  implemented and locally green, but final Spec review found two remaining §1b/§3.6c contract
  blockers. The bounded-loop rule stops this corrective here instead of adding another private
  serialization or deferred-settlement design.
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:204`
    — adds the per-plan `bind(PlanProducerToken)` adapter. Asynchronous acquisition follows the
    r13 collaborator order at lines 296–374; producer-backed cancellation, expiry, flush settlement,
    delivery, and waiter service are at lines 378–748.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:898`
    — accounts ready chunks/bytes and global finalizers for both regular and continuation permits,
    enforces the subtractive regional/global partitions, removes duplicate ready/reserved counters,
    and uses atomic EWMA feeds at lines 230–253 and 898–974.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java:53`
    — standalone/JUnit 18-test suite. The tests that can fail on the named race/resource property
    are at lines 120, 144, 185, 214, 232, 249, 307, 350, 391, 439, 573, and 612.
  - `.orchestrate/folia-port/tasks/13-backpressure.md` — appends this corrective record without
    modifying the retired or fresh-implementation history above.
- **Scope dispositions (1–9):**
  1. **Implemented:** every delivery submission is isolated per waiter; a failed/closed producer is
     recovered without aborting the remaining batch (`DefaultFoliaBackpressure.java:523`). Actual
     delivery runs as a separate producer command, so tick settlement never drains notifications.
     The three-waiter flush race asserts all already-granted attempts settle and all accounting
     returns to zero (`DefaultFoliaBackpressureTest.java:439`).
  2. **Implemented:** continuations now validate and charge ready chunks, prepared bytes, global
     chunks/bytes, and global finalizers exactly like regular READY permits. FINALIZING and close
     release those reservations once (`DefaultFoliaBackpressure.java:898`, `:955`). Regional and
     global byte-cap behavior is asserted at test lines 185 and 214.
  3. **Implemented:** `tryAcquire` calls no completion-service method, allocates no admission stage,
     starts no thread, and uses only `accountingLock.tryLock()` (`DefaultFoliaBackpressure.java:271`).
     The hot saturation miss returns while the service lifecycle lock is held and leaves the
     admission-producer count unchanged (`DefaultFoliaBackpressureTest.java:144`).
  4. **Implemented with a remaining supersession blocker:** the caller-supplied stage's inline body
     only calls `AdmissionProducer.submit` with the prebuilt cancellation command; it takes no lock
     and performs no settlement or delivery (`DefaultFoliaBackpressure.java:323`, `:397`). The
     accounting-lock-held test at line 307 proves the completing thread returns, and the three-thread
     test at line 350 races cancellation after grant settlement but before delivery-command
     submission. See Escalation for the narrower command-start → `delivery.deliver` gap.
  5. **Implemented:** the only deadline path is `AdmissionProducer.schedule`; the timer's
     `linearizeExpiry` is one state CAS and settlement is a prebuilt producer command
     (`DefaultFoliaBackpressure.java:378`, `:1335`). No common-pool or unfenced submission remains.
     Test line 391 saturates every common-pool worker and still observes deadline rejection.
  6. **Implemented:** `arm(waiter::flushExpired, waiter::transitionFailed)` executes before
     accounting. The idempotent flush hook settles only its waiter, removes it or releases its
     undelivered permit, performs no waiter scan/callback/completion, and lets the same producer
     publish lifecycle rejection (`DefaultFoliaBackpressure.java:318`, `:465`).
  7. **Implemented except the tick-lock blocker below:** continuations may consume the full remaining
     global finalizer capacity while regular work cannot consume the global reserve; global waiter
     slots are subtractively partitioned. Tests at lines 232 and 249 bind both. Telemetry uses atomic
     EWMAs without contention drops and retains the chunk-count EWMA (`DefaultFoliaBackpressure.java:230`).
     Producer-backed waiter batches replace common-pool scheduling; deadline horizon, batch size,
     global waiters, transfer timeout, and EWMA weight are injected through `Tuning` at line 1230.
  8. **NEEDS_CONTEXT:** production states the hard requirement that `RegionKey` be an immutable value
     key with stable equality/hash. The required re-derived-key test cannot be made green while the
     Task-14-owned `RegionKey` remains an empty identity-equality class and this corrective is
     forbidden from editing it. No canonicalization or lane grouping was invented.
  9. **Implemented/qualified:** the old isolation-only tests were removed. New tests create actual
     contention with platform threads, the completion control thread, all common-pool workers, and
     flush expiry; byte/global caps are binding rather than shadowed by regional caps. The grant/
     cancellation test does not cover the unresolved command-start gap and is not claimed to do so.
- **New §3.6c clauses and §3.6d order:**
  - **Item 6:** each asynchronous attempt acquires and arms exactly one waiter producer before any
    accounting; flush expiry settles only that attempt and core publishes/release-fences it.
  - **Item 7:** only producer `schedule` is used. Its timer performs `QUEUED → EXPIRY_LINEARIZED` by
    CAS, then submits the immutable expiry settlement; producer publication cancels the retained
    timer. Backpressure owns no timer or executor.
  - **Item 8:** the untrusted cancellation completion performs only the O(1) producer submission.
    All accounting, permit release, plan rejection, future completion, and hook work occurs later
    on completion-service paths.
  - **Call order:** `tryAcquireAdmissionProducer(attemptId, registeredPlan)` at line 311 → capture
    `delivery()` → `arm(...)` at line 318 → accounting at line 331 → `schedule(...)` for queued
    attempts at line 378 → `delivery.deliver` or `producer.reject` from producer commands. Empty
    acquisition calls `registeredPlan.rejectAdmission` before throwing and performs no accounting.
    No `deliverExceptionally` path exists.
- **Deviations:**
  - Gradle was not run, as prohibited. Target production/tests compile on Java 25 with
    `--release 25 -Xlint:all -Werror`; the standalone 18-test runner passed ten runs before the final
    two additions and five runs with all 18 tests. The full Folia-source `javac` attempt is blocked
    by separately owned `DefaultFoliaRegionDispatcher.java:258`, which still calls the rescinded
    one-argument `registerProducer`; the two existing non-task-13 lint warnings were also observed.
  - The final Standards review passed documented rules and reported maintainability-only smells in
    the intentionally centralized accounting engine/test runner. Final Spec review rejected on the
    two blockers below plus the Task-14 RegionKey precondition.
  - Seven public `Limits` fields, `continuationFinalizerLimit`, and the five internal `Tuning` slots
    remain injected/configurable and `[NEEDS-RUNTIME]`. `Tuning.initial()` supplies only W1 starting
    values: batch 64, global waiters 65,536, transfer wait 100 µs, EWMA shift 3, and maximum admission
    wait 30 s. The old fixed global-continuation ceiling was removed.
- **Attack points:**
  - Prove or amend the exact cancellation linearization between a producer delivery command starting
    and `AdmissionDeliveryLease.deliver` claiming core's outcome CAS. A private backpressure claim or
    lock is explicitly forbidden and would reproduce the retired failure.
  - Resolve how owner-thread `close()` and `enter(FINALIZING)` settle synchronously without waiting
    when `accountingLock` is contended. Their critical sections are local and no longer scan/deliver,
    but `lock()` itself is not a §1b non-wait proof (`DefaultFoliaBackpressure.java:825`, `:865`).
  - Re-run the module compile after the separately owned dispatcher migrates to the current r13 core
    API, and add the re-derived-key state-reuse test immediately after Task 14 supplies value keys.
- **Task-14 handoff:** construct one shared `DefaultFoliaBackpressure`. For each plan, outside every
  tick fast path, call `tryAcquirePlanProducer(operationId, chunkKey, planSequence, ownerFlushExpiry,
  transitionFailure)`, consume the returned token with `DefaultOperationCompletion.register(token)`,
  then call `defaultBackpressure.bind(token)` exactly once and retain that frozen-SPI view with the
  plan. The token must be consumed, live, from the same completion service, and its `operationId`
  must match every `Demand`. If plan-producer acquisition is empty, do not bind or call admission.
  G-A1 may call `tryAcquire` only through this pre-bound view; async fallback on the same plan view
  acquires its waiter producer lazily. Task 14 must also supply immutable value-equal `RegionKey`s
  whose hash remains stable across re-derivation/rebind.
- **Escalation:** BLOCKED — §3.6d needs a co-signed way for the producer-submitted cancellation
  transition to compete atomically with grant delivery without doing settlement/publication on the
  caller thread, and §3.6c/§1b needs a legal non-wait settlement path for synchronous-permit
  `close`/FINALIZING when the accounting lock is busy. NEEDS_CONTEXT additionally remains for the
  Task-14-owned value `RegionKey` implementation and its mandatory re-derived-key test. No core,
  dispatcher, queue-handler, or task-manager file was edited.

### Fresh-implementation corrective 2 (2026-07-20)

- **Status:** AUTHORIZED — both r14 blockers are discharged. Caller cancellation now uses the
  producer's third outcome, and all post-publication permit transitions are zero-wait accounting
  operations with exactly-once ownership. The only remaining context dependency is the unchanged
  Task-14 `RegionKey` precondition described under Escalation.
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:303`
    — retains the r13 producer acquisition/arm/accounting/schedule order; caller cancellation settles
    accounting then calls `AdmissionProducer.cancel` at lines 401–431, while lifecycle failures keep
    `reject`/`rejectAdmission`.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:564`
    — publishes the grant outcome outside `accountingLock`, then reacquires only to reconcile the
    winning CAS. All attempt settlement commands and flush expiry share the core control
    serialization, so no waiter-state command crosses that publication/reconciliation gap.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:823`
    — uses atomic regional/global counters and permit-local lifecycle ownership. Transfer,
    FINALIZING, close, bounded idle-prune handoff, and waiter-owned wake-up are at lines 869–1023.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java:397`
    — adds the exact three-outcome race, same-plan cancellation retry, lifecycle terminalization,
    integrated cancel/deliver/reject race, accounting-lock contention, 100 close/transfer races,
    and waiter-producer wake tests at lines 397–552 and 738–860.
  - `.orchestrate/folia-port/tasks/13-backpressure.md` — appends this record without changing any
    previous-worker or corrective history.
- **r14 Q1 disposition and call-site audit:**
  - `settleCancellation` is the sole caller-initiated cancellation settlement. For a GRANTED
    attempt it releases the permit at line 418, unlocks, then calls `producer.cancel` at line 428.
    This is the only call site moved from `reject` to `cancel`.
  - Empty admission-producer acquisition stays on `registeredPlan.rejectAdmission` (line 320).
    Attachment/setup failure (line 333), deadline expiry (line 451), producer transition failure
    (line 467), lost delivery (line 557), and settled lifecycle/admission failures (line 626) stay on
    `producer.reject`. Flush expiry still lets the core producer publish lifecycle rejection after
    the bounded hook returns. No `deliverExceptionally` path exists.
  - `delivery.deliver`, `producer.reject`, and `producer.cancel` use the core attempt CAS. The
    three-thread test at line 397 asserts exactly one returns true and the isolated future matches
    that winner. Lines 456 and 485 prove cancellation leaves the same registered plan sequence
    reusable, while lifecycle rejection terminalizes it. The integrated race at line 508 also
    checks plan consequence and zero accounting for whichever outcome wins.
- **r14 Q2 disposition — no-wait and exactly-once per transition:**
  - **Transfer:** `claimPermitOperation` CAS grants one permit-local owner, then line 877 makes one
    zero-time `accountingLock.tryLock()` attempt. A miss returns false without mutation. A successful
    move remains atomic under accounting ownership; `finishPermitOperation` consumes any concurrent
    close request and releases from the final region exactly once.
  - **enter(FINALIZING):** the same permit-local CAS excludes close/transfer; lines 933–938 update
    scheduled/finalizer counters and release ready chunks/bytes with atomic operations and no
    `accountingLock`. The ordered stage write occurs before the owner is released. An invalid stage
    conservatively closes and releases once.
  - **close:** line 951 publishes `closeRequested` before attempting the owner CAS. If another permit
    operation is active, close returns immediately and that owner consumes the request. The
    post-IDLE recheck at lines 970–973 closes the race where the request arrives during owner
    release. `closed` plus the single ACTIVE owner makes release idempotent and exactly once.
  - Atomic counter decrements make capacity visible synchronously without the accounting lock.
    `signalCapacityReleased` uses only `tryLock` for a bounded prune batch and submits waiter work
    through the current queued waiter's live producer. A permit stores no admission producer. Test
    line 840 first proves permit A's producer is released, then proves closing A still admits B
    through B's outstanding producer.
- **Deviations:**
  - Gradle was not run, as prohibited. Current core `util/task` sources were compiled into a
    temporary output because prebuilt classes are stale; their pre-existing unchecked/raw warnings
    were not promoted. The task-owned production and test sources compile with Java 25
    `--release 25 -Xlint:all -Werror`; the standalone 25-test runner passed five consecutive final
    runs. A full Folia-source `javac --release 25 -Xlint:all` also passed with the two existing
    warnings in `FoliaRegionDispatcher` (overload ambiguity) and `FoliaQueueHandler` (serial UID).
    The terminal Standards re-review passed after moving grant publication outside the lock.
  - r14 removes the former 100 µs transfer-wait slot rather than freezing it: any timed transfer
    wait is now prohibited. The remaining injected `[NEEDS-RUNTIME]` slots are all seven public
    `Limits`, `continuationFinalizerLimit`, waiter-service batch (64 initial), global waiters
    (65,536), EWMA weight shift (3), and maximum admission wait (30 s).
  - Idle-region removal uses a bounded lock-free prune handoff from tick paths. Pressure counters
    are released before return; physical map eviction may complete on the next successful no-wait
    accounting pass when the lock is concurrently owned.
- **Attack points:**
  - Re-audit the multi-counter proof: admission/transfer increments remain serialized by
    `accountingLock`; concurrent permit operations only decrement capacity dimensions, so an
    admission may observe a conservative mixed release but cannot exceed a cap.
  - Re-audit the close/transfer handshake specifically around ACTIVE → IDLE: `closeRequested` is set
    before close's CAS, the active owner checks it before IDLE, and the owner checks it again after
    IDLE so no request is lost and no second release owner is created.
  - The outcome-publication gap deliberately relies on the frozen completion service's serialized
    producer/control commands; backpressure adds no private outcome lock or second attempt CAS.
  - The test suite still cannot discharge `RegionKey` re-derivation until Task 14 supplies value
    identity. Treat any identity-equality implementation as a hard integration failure.
- **Task-14 handoff:** outside every tick fast path, acquire the registered plan token from the same
  backend-owned completion service, consume it with `DefaultOperationCompletion.register(token)`,
  then call `DefaultFoliaBackpressure.bind(PlanProducerToken)` and retain that bound view for the
  plan sequence before any G-A1 `tryAcquire` or async fallback. The token must remain live and its
  operation id must match every demand. Task 14 must supply an immutable, value-equal `RegionKey`
  with stable hashing across merge/split re-derivation, then add the test proving a newly derived key
  for the same region reaches the existing `RegionState` and cap accounting.
- **Escalation:** NEEDS_CONTEXT — only the Task-14-owned `RegionKey` value identity and its mandatory
  re-derivation/state-reuse test remain unavailable. The r14 cancellation and permit-lifecycle
  contracts are implemented without a core, dispatcher, task-manager, or queue-handler edit.

### Fresh-implementation corrective 3 (2026-07-20)

- **Status:** AUTHORIZED — all Corrective-3 MAJORs are discharged. Settled waiter batches cannot be
  separated from publication, idle-region cleanup is bounded and cannot be starved by accounting
  contention, transfer exposes retryable busy separately from saturation, and failure notification
  carries the actual winning cancel/reject outcome after core publication. Both terminal re-reviews
  passed with no remaining findings.
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:539`
    — isolates every per-attempt submission failure; lines 657–706 settle a waiter batch under the
    accounting lock and guarantee `deliverAll` plus next-sponsor submission through nested `finally`
    guards after unlock.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:886`
    — returns the internal `TRANSFERRED` / `RETRY_BUSY` / `TARGET_SATURATED` / `PERMIT_CLOSED`
    result while preserving the frozen boolean SPI as a compatibility projection at line 1706.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:1210`
    — caps deduplicated prune requests with the injected `maxPendingPrunes`, requests a bounded map
    sweep on overflow, and advances queue/sweep eviction on every accounting unlock at line 1255.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressure.java:1339`
    — derives hook kind from the core `reject`/`cancel` winner, then attaches the mandatory four-field
    hook to the real delivery future so rejection follows plan terminalization and publication.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java:108`
    — proves a re-derived value-equal `RegionKey` reaches the same state and regional cap.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java:623`
    — repaired deadline evidence, injected batch/per-attempt submission failures at line 682, detailed
    transfer outcomes at line 873, prune overflow/sweep at line 1048, and foreign-lock telemetry at
    line 1097.
  - `.orchestrate/folia-port/tasks/13-backpressure.md` — appends this record without rewriting the
    retired or earlier fresh-worker history.
- **Item-by-item disposition:**
  1. **Implemented by construction, not case analysis:** `serviceWaiters` owns the local settled list
     inside an outer `finally`; after accounting unlock, `publishSettledBatch` executes `deliverAll`
     in the `finally` of the injected preparation step and submits the next sponsor in another
     `finally`. No throw between charging and publication can strand the list. The line-682 test
     throws both before batch publication and on attempt 2 of 3; attempts 1 and 3 still settle, the
     failed attempt rejects, accounting reaches zero, producers release, and the region evicts.
  2. **Implemented:** one `RegionState` can hold at most one queued prune request, the queue has the
     independent injected `maxPendingPrunes` ceiling, and overflow becomes `pruneSweepRequested`
     rather than another allocation. Every successful accounting owner advances a bounded queue or
     weakly-consistent map sweep before unlocking; an overflow during a sweep requests another pass.
     Thus the contention path that admits/creates states is also the drain path and cannot starve
     stale-region eviction. Tick release remains a bounded CAS/increment/offer-or-sweep handoff.
  3. **Implemented by construction, not case analysis:** the package-private `BoundAdmission`
     adapter extends the frozen SPI with `transferResult`. A lock miss can only produce `RETRY_BUSY`;
     a passed lock plus failed target-cap check can only produce `TARGET_SATURATED`; neither mutates
     accounting. The frozen boolean method maps only `TRANSFERRED` to true, so no public SPI changed.
     Task 14 must retain `BoundAdmission` through its plan wrapper: retry `RETRY_BUSY` against the
     same `(permit, actualRegion)` without rediscovery, terminalization, mutation, or permit close;
     defer and retry `TARGET_SATURATED` under its capacity/deadline policy; treat `PERMIT_CLOSED` as
     permanent pre-mutation failure; proceed only on `TRANSFERRED`.
  4. **Implemented:** `AdmissionRejectionHandler.failed(region, demand, outcome, reason)` makes the
     outcome mandatory. `rejectAttempt` and `cancelAttempt` attach a hook only when that exact core
     outcome entry returns true; armed flush/transition hooks attach only after their guarded
     settlement, and `arm(false)` attaches the core-owned lifecycle rejection. The hook executes from
     the real delivery future, uses its published cause, and therefore runs after reject has
     terminalized the plan. **Task-14 wiring contract:** route only `REJECTED` to the `NOT_ACCEPTED`
     observer/sink. `CANCELLED` must never call `PlanProducerToken.rejectAdmission`,
     `PlanState.admissionRejected`, or any `NOT_ACCEPTED` terminalizer; it may feed cancellation UX or
     be ignored. Operation-level abandonment remains Task 14's explicit
     `CANCELLED_BEFORE_MUTATION` decision.
  5. **Implemented with discriminating tests:** the deadline test removes its waiter while all common
     pool workers remain parked, releases them only afterward, then asserts the implementation's
     exact timeout message; no harness timeout can satisfy it. Telemetry holds the real lock on a
     foreign thread. Bound-producer coverage rejects a mismatched operation id. The global-carve
     test now admits the sixth continuation that a wrong regular sub-cap rejects. The concurrent
     granted-undelivered cancellation test asserts `CANCELLED` and successfully retries admission on
     the same registered plan sequence. Submission-failure injection is described in item 1.
  6. **Implemented:** the current Task-14-owned `RegionKey` is immutable and value-equal over its
     world/observed-region fields. The line-108 test re-derives an equal key, observes the occupied
     regional cap, and confirms only one tracked `RegionState`. The previous NEEDS_CONTEXT escalation
     is closed; Task 13 did not edit `RegionKey.java`.
- **Deviations:**
  - Gradle was not run. Current core completion sources were compiled to a temporary output, then
    Task-13 production/tests compiled with Java 25 `--release 25 -Xlint:all -Werror`. The standalone
    29-test runner passed five consecutive final runs.
  - A full Folia-source Java 25 compile passed. Its only warnings are the pre-existing ambiguous
    overload in `FoliaRegionDispatcher` and missing serial UID in `FoliaQueueHandler`; neither file is
    Task-13-owned. Both independent terminal review axes passed after their findings were corrected.
  - No certification value was frozen. Injected `[NEEDS-RUNTIME]` slots remain the seven public
    `Limits`, `continuationFinalizerLimit`, waiter batch (64 initial), global waiters (65,536), the new
    independent pending-prune ceiling (4,096), EWMA weight shift (3), and maximum admission wait
    (30 s).
- **Attack points:**
  - Re-audit prune overflow under churn: the physical queue never exceeds `maxPendingPrunes`, one
    state cannot duplicate entries, every accounting unlock advances cleanup, and overflow arriving
    during an active weak iterator forces a subsequent sweep.
  - Preserve the nested batch-publication `finally`; moving `deliverAll` to an ordinary following
    statement recreates the reviewed charge-without-publication leak and now fails the injection test.
  - Preserve `BoundAdmission` through Task-14 wrappers. Calling only frozen boolean `transfer` throws
    away the retry contract and again conflates transient lock contention with target saturation.
  - Preserve hook attachment to the real attempt future. A synthetic already-complete stage can run
    the rejection observer before plan terminalization; Throwable-type inference can mislabel
    `reject(cancellationFailure)` as cancellation.
- **Escalation:** AUTHORIZED — no Task-13 blocker remains. `RegionKey` value identity is present and
  tested. The exact Task-14 transfer and rejection-hook contracts above are represented by mandatory
  package-private types and are consumed by the concurrently owned broker in the current tree; no
  core, broker, dispatcher, task-manager, or queue-handler file was edited by Task 13.
