# Cross-review — Task 12 `DefaultFoliaRegionDispatcher`, alignment passes 2 (r13) + 3 (r15)

Reviewer: fresh independent adversarial thread. Scope: the two never-reviewed alignment passes
("Alignment pass 2 (r13 producer API)", "Alignment pass 3 (r15 drain snapshot)") and the resulting
working-tree state of
`worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaRegionDispatcher.java`
(untracked; the file I read IS the post-alignment-3 state).

Everything below was read, not taken on faith: the dispatcher, the consumed producer/expiry SPI in
`worldedit-core/.../OperationCompletionService.java`, the isolation types
`CompletionServiceStage.java` / `CompletionServiceFuture.java`, the frozen interface
`FoliaRegionDispatcher.java`, architecture §3.3 / §3.6b / §3.6d / §1b, and the test
`DefaultFoliaRegionDispatcherTest.java`.

---

## VERDICT: PASS-WITH-NOTES

The expiry hook is genuinely bounded, the publication barrier is correct and safe, and the snapshot
is coherent and untearable. r15 resolves the alignment-2 `NEEDS_CONTEXT` boundedness gap. The
instrumented probe is real, covers the production expiry path, and can fail. No BLOCKING or MAJOR
finding. Three MINOR notes below.

---

## Is the flush-expiry hook genuinely bounded? YES — exact expiry-path trace

The completion service fires the drain producer's hook once at the flush deadline:
`OperationCompletionService.expireFlush()` → `requestFlushExpiryLocked(producer)`
(`OperationCompletionService.java:663-681`) → enqueues `runFlushExpiry(producer, hooks)` on the
single completion executor thread → sets `flushExpiryContext = producer` → `hook.expire(deadline)`
(`:683-710`). The hook registered by the dispatcher is `ignored -> expireDrain(waiterReference)`
(`DefaultFoliaRegionDispatcher.java:283`).

Exact steps executed on the deadline (completion-executor thread, NOT a tick thread):

1. `expireDrain` (`:548-552`): acquires `synchronized (waiterReference)`, `waiterReference.get()`
   (non-null — see barrier), `requestFinishDrain(waiter)`.
2. `requestFinishDrain` (`:536-546`): `waiter.finished.get()` (read), `finishRequested.compareAndSet`
   (the idempotent claim), `diagnosticSnapshot.get().report` (**one** atomic reference read + one
   final-field read — no build, no traversal), `waiter.producer.complete(() -> finishDrain(waiter, report))`.
3. Because `flushExpiryContext.get() == waiter.producer`, `Producer.complete`
   (`OperationCompletionService.java:835-850`) runs the transition **inline** (`transition.run()`),
   not through the executor — no lifecycleLock, no scan.
4. `finishDrain` (`:554-563`): `drainWaiters.remove(key, waiter)` (O(1) CHM), `deadlineTask.cancel()`
   (bounded, non-blocking Folia call), `finished.compareAndSet`, `waiter.future.complete(report)`.

Nothing here builds a snapshot, iterates/copies/sorts labels, or allocates beyond the two capturing
lambdas. The report is prebuilt: `newSnapshot` constructs `new DrainReport(...)` wrapping the
`PersistentLabelList` **by reference** at register/settle/mint time (`:602-620`), never at expiry.
`liveTickets`/`unresolvedTasks`/labels all come from the one immutable `DrainSnapshot`.

Matches architecture §3.3 "Drain-report snapshot at expiry" and §3.6b "Drain-expiry terminalizers"
(bounded, no user code, no live-state, cannot leave producer registered — `completeOnFlushExpiry`
deregisters it via `transitionFinished`, confirmed by the test's `registeredProducerCount()==0`).

### The probe is real and can fail (not a decoy)

`snapshotWorkProbe` runs at the top of every `newSnapshot` (`:607`) AND is threaded into
`PersistentLabelList` as `traversalProbe`, run on every indexed `get(int)` (`:720`). Same instance
throughout (constructor `:134-138`; preserved across `addLabel`/`removeLabel`). So the probe fires on
snapshot *construction* and on any label *traversal* (`get`, iterator, `toArray`, copy, sort, list
`.equals`/`.toString`) — i.e. exactly the unbounded operations the hook must avoid. `size()` is O(1)
(node field), correctly not probed.

Failure path is genuine: if the hook did snapshot work while armed, the `AssertionError` propagates
`expireDrain` → `hook.expire` → caught in `runFlushExpiry` → rethrown → caught in `runQueued` →
`producer.transitionFailed` → the dispatcher's `transitionFailure` → `failDrain` → completes the
drain future **exceptionally**, so `drain...get()` (test line 406) throws and the test goes red. The
test (`:375-419`) arms the probe around a REAL `completionService.flush(20ms)` that fires the REAL
expiry hook through the REAL service; the expiry path shares `requestFinishDrain` with production.
Not a decoy.

---

## Publication-barrier verdict: CORRECT and bounded

`synchronized (waiterReference)` on a local `AtomicReference` (`:280-289`, `:549`, plus
`failDrain` capture `:284`) is unusual but sound. The chicken-and-egg is real: §3.6b requires the
expiry hook be registered *atomically* with the producer, but the hook must reference the `DrainWaiter`,
which needs the `Producer` that `registerProducer` returns. The forward reference via `waiterReference`
is the only way; without a barrier, expiry firing in the window between `registerProducer` returning
and `waiterReference.set(waiter)` would read null → NPE at `Objects.requireNonNull(...,"drain waiter")`.

The monitor is the SAME shared instance captured by both the drain() thread and the expiry lambda, so
`synchronized` establishes real mutual exclusion + happens-before: `waiterReference.set(waiter)` inside
the monitor (drain) happens-before `waiterReference.get()` inside the monitor (expiry). Expiry can only
observe a fully published, non-null waiter.

Held-across analysis (the mandate's core concern):
- The monitor IS held across `waiter.future.complete(report)`. Verified safe: the raw future is
  private; the only caller-visible view is `completionService.isolateStage(waiter.future)`
  (`:319`, `:584-586`). `CompletionServiceStage` attaches only `...Async(..., notificationExecutor)`
  dependents, and `toCompletableFuture()` bridges via `whenCompleteAsync(..., notificationExecutor)`
  (`CompletionServiceStage.java:297-307`); `CompletionServiceFuture` overrides every continuation onto
  the notification executor. So completing the future runs, inline, only `notificationExecutor.execute`
  (a bounded virtual-thread start) — never user code, never a blocking call.
- Not reachable from a tick thread: the hook runs on the completion executor thread. `drain()`'s own
  hold of the monitor spans only `registerProducer` + `new DrainWaiter` + `set` + `put`, all bounded
  and non-blocking (no future completion, no user code), so even if drain() were called from a tick
  thread its critical section is bounded.
- No lock inversion / deadlock: expiry path is serializationLock (runQueued) → waiterReference monitor →
  [inline finishDrain, no further dispatcher lock]; drain() path is waiterReference monitor → service
  lifecycleLock. No path holds serviceLifecycleLock→serializationLock or serializationLock→
  serviceLifecycleLock across the monitor, so no cycle. Confirmed against every lock acquisition in
  `OperationCompletionService`.

See MINOR-1 for the one caveat (the hook *can* briefly wait on the monitor).

---

## Snapshot-coherence verdict: COHERENT — a torn read is impossible

The whole `DrainSnapshot` (exact `unresolvedTasks`, exact `liveTickets`, the immutable
`PersistentLabelList`, and the prebuilt `DrainReport` over that same list) is built by one `newSnapshot`
call and published with a single `diagnosticSnapshot.set` (`:602-624`). All fields final; the AVL nodes
are immutable (`LabelNode` all-final `:838-856`); safe publication via the `AtomicReference` volatile
write. Every reader (`requestFinishDrain`, `liveTickets`, `outstandingFutures`) does a single
`diagnosticSnapshot.get()`, so it observes one coherent epoch. Counts and the label view can never come
from different epochs.

All three mutators — `register` (`:339-357`), `settle` (`:515-534`), `updateLiveTickets` (`:480-492`)
— read-modify-write the reference under `lifecycleLock`, so no lost update. `newSnapshot` enforces the
invariant `unresolvedLabels.size() == unresolvedTasks` and rejects negatives (`:608-613`), and
`removeLabel` fails fast if the id is absent (`:710-716`). I constructed the concurrent register/retire-
during-drain interleaving requested: because the report is an immutable value captured at a single
`get()`, later settlement mutates a *new* snapshot and cannot rewrite the captured report (matches §3.3
"linearization point defines the report's deadline state"). This closes the pre-alignment incoherent
label/count finding.

I also checked the implicit invariant `liveTickets <= unresolvedTasks`: mint (`ticketMinted`) always
follows `register` and retire always precedes `settle` within `runRegion`/`runEntity`
(`:396-408`, `:438-449`), all under `lifecycleLock`, so an accepted-but-settled task can never carry a
live ticket. Hence `unresolvedTasks==0 ⇒ liveTickets==0`, and G6's two counts stay mutually consistent.
(This one is NOT asserted in `newSnapshot`; it holds by construction and is stress-tested — see notes.)

---

## Findings (most severe first)

### MINOR-1 — expiry hook does bounded work beyond the literal r15 "(1)(2)(3)", incl. a bounded monitor wait
`DefaultFoliaRegionDispatcher.java:548-563`. r15 (§3.3) says the hook performs "only" (1) claim,
(2) one atomic read, (3) submit. The hook additionally: acquires `synchronized (waiterReference)`,
does `drainWaiters.remove` (O(1)), and `deadlineTask.cancel()` (bounded Folia call), and the monitor
wait can briefly block if the deadline fires during drain()'s publication window. All of it is bounded
and consistent with §3.6b ("bounded coordinator state work", "cannot leave its producer registered"),
none of it is a scan/copy/sort or user code, and the wait is bounded by drain()'s microsecond critical
section on a non-tick thread. So it does not violate §1b. It is a literal-vs-spec gap, not a risk.
Interleaving: flush-expiry fires in the window after `registerProducer` returns but before
`waiterReference.set` completes → `expireDrain` blocks on the monitor until drain() exits its
synchronized block, then proceeds. Bounded, correct, but it is "a wait inside a bounded hook", which is
worth recording. No action required unless the boundedness budget (spike w08) counts hook-internal
monitor contention.

### MINOR-2 — `drain()` can throw synchronously instead of returning a failed stage
`DefaultFoliaRegionDispatcher.java:280-285`. `registerProducer` throws `RejectedExecutionException`
when the completion service is already `FLUSHING`/`TERMINATED`
(`OperationCompletionService.java:133-135`). That throw escapes drain()'s `synchronized` block
uncaught, so a caller invoking `drain(deadline)` out of the documented order
(`stopAccepting → drain → flush`, architecture ~line 956) — e.g. a second `drain`, or drain after flush
started — gets a synchronous exception rather than a failed `CompletionStage<DrainReport>`. The happy
shutdown path avoids this (service is `ACCEPTING` at drain time; the tests exercise that order), so it
is latent. Consider wrapping the registration failure into an isolated failed stage for contract
uniformity.

### MINOR-3 — parity/coherence tests pin weaker properties than their names imply (see test section)
Both properties are actually *structurally* guaranteed by shared code / construction-time invariants;
the tests confirm them but could not distinguish a subtly different-but-still-coherent implementation.
Detailed below. Not a correctness defect.

---

## Test-quality verdict — test by test

- `completionFlushExpiryTerminalizesDrainExactlyOnceAndReleasesProducer` (`:375-419`) — **PINS the
  O(1)-at-expiry property and CAN FAIL.** Real service, real flush, real expiry hook; probe wired to
  `newSnapshot` + label `get`; arming window brackets only the flush; a scan/rebuild in the hook →
  exceptional drain → red. Also asserts exactly-once (single `deadlineTask.cancel`) and producer
  release (`registeredProducerCount()==0`). Strongest test in the file. Valid.

- `naturalCompletionAndExpiryPublishTheSameSnapshotReport` (`:421-440`) — **PARITY: real but only the
  empty state.** Both paths literally read `diagnosticSnapshot.get().report` via the shared
  `requestFinishDrain`, so parity is structural; the test only exercises `(0,0,[])`. It would catch a
  regression that made expiry build its own report, but not a divergence at a non-empty state (which
  natural completion structurally cannot reach anyway). Adequate, not exhaustive. Uses a mock service
  whose `producer.complete` runs inline unconditionally — fine for this assertion.

- `concurrentRegistrationAndRetirementNeverPublishATornReport` (`:442-500`) — **coherence: the named
  assertion is near-tautological, but the test still guards coherence.** `report.unresolvedTasks() ==
  report.unresolvedLabels().size()` can never be false because `newSnapshot` throws on mismatch before
  publishing; a real coherence break would surface as a worker-future exception at `registrations.get`
  (line 492), still failing the test. It genuinely stresses the concurrent register/settle/mint paths
  and the `0 <= liveTickets <= unresolvedTasks` bound. Real, if indirect.

- `unresolvedLabelOrderIsDeterministicAcrossDispatchers` (`:502-510`) — **PINS deterministic
  submission-id order and CAN FAIL** (asserts exact `["PACKET… #1","COMMIT… #2","ASYNC… #3"]`). Would
  catch an unstable/rebalanced iteration order. Valid.

- `drainProducerTransitionFailureCompletesTheIsolatedStageExceptionally` (`:512-526`) and
  `drainFailsClosedWhenProducerNoLongerAcceptsTerminalTransition` (`:528-541`) — **PIN the
  fail-closed CAS paths and CAN FAIL.** First drives the `transitionFailure` callback → `failDrain`;
  second stubs `producer.complete` to throw → catch → `failDrain`. Both assert the isolated stage
  completes with the exact cause. Valid.

- `postTerminationTickCallbackEnqueuesBeforeCompleting` (`:543-580`) — **PINS the r12 Q4 tick-thread
  rule and CAN FAIL.** With `CONTEXT.own` making the test thread a tick thread and the service already
  `TERMINATED`, a missing `submitCompletion` hop would make `completionService.execute` throw
  `RejectedExecutionException` inline at `regionCallback.run()` (line 573) → red; the continuation-
  thread assertions confirm it ran notification-side. Valid.

- Routing/lifecycle tests (`:97-297`, `:582-600`) — inline-vs-scheduled, ticket lifecycle, retire-on-
  failure, entity-retired, global-only, stop-accepting. Standard, real. Valid.

Overall: the load-bearing r15 property (O(1) at expiry) is pinned by a test that can genuinely fail;
AVL-balance and natural/expiry parity are asserted structurally/indirectly rather than by a dedicated
adversarial test (see below).

---

## AVL index — balanced, O(log n) task update / O(1) ticket update

Rotations verified correct (`rotateLeft :816-820`, `rotateRight :822-826`) and `balance`
(`:797-814`) handles LL/LR/RR/RL with the standard `height(child.left) < height(child.right)` pre-
rotation test. `insert`/`remove` rebalance at every reconstructed ancestor (`:743-787`), heights/sizes
recomputed in the `LabelNode` constructor (`:847-854`). Submission ids are monotonic (right-spine
inserts) — precisely the case that degrades an unbalanced BST to O(n) — but the RR single-left rotation
keeps height O(log n); arbitrary-order `remove` rebalances likewise. So `addLabel`/`removeLabel` are
genuinely O(log n), `size()` is O(1), and `updateLiveTickets` reuses the label root untouched → O(1)
(`:483-488`). No tick-thread pays O(n): the only O(n) work is iterating `unresolvedLabels`, which per
§3.3 happens on the diagnostic consumer, never in the hook or on a region thread. `metricsSnapshot`
sorts, but it is a diagnostic accessor off the hot/drain path. **NOT pinned by a dedicated test** — no
test asserts tree height/balance under a large monotonic-insert + random-delete load; balance is
argued in prose + code only. Low risk (the code is textbook-correct), but a height-bound property test
would be the honest way to lock it.

---

## Sealed first-alignment invariants — not regressed

- `failDrain` (`:565-582`): single terminal CAS (`finished.compareAndSet`), removes waiter, cancels
  deadline task (cancellation failure suppressed onto the terminal throwable), completes ONLY the
  private raw future exceptionally. Intact.
- `finishDrain` (`:554-563`): remove waiter, cancel deadline, terminal CAS, complete private future.
  Two-level idempotence (`finishRequested` in `requestFinishDrain` + `finished` here) plus the
  producer lifecycle CAS (`OPEN→COMPLETING` vs `OPEN→EXPIRING`) serialize timer/settle/expiry races to
  exactly one terminalization. Intact.
- Deadline-task cancellation race closed: drain() re-checks `waiter.finished.get()` after assigning
  `deadlineTask` and cancels if already finished (`:306-309`); the async-scheduler failure falls back
  to the completion-service timer (`:299-312`). Preserved.
- `submitCompletion` r12 Q4 (`:588-596`): a tick-thread caller hops through
  `isolateStage(completedFuture).thenRun(...)` onto the notification executor before ever calling
  `completionService.execute`, so the tick thread never touches the serialization primitive nor runs
  the inline fallback; the notification thread (non-tick) legitimately runs it post-`TERMINATED`.
  Matches §3.6b Post-termination fallback. Verified end-to-end by the post-termination test.
- Isolation: both public stages go through `completionService.isolateStage` (`:319`, `:585`);
  `submission.future` and `waiter.future` are private and never returned raw. No leak.
- No-missed-wakeup on drain: the `producer.execute(outstandingFutures()==0 → requestFinishDrain)`
  initial check (`:313-317`) composes with `settle`'s zero-remaining fan-out over `drainWaiters`
  (`:529-533`); I walked the register/settle/put interleavings — at least one path always fires
  `requestFinishDrain`, and it is idempotent. Robust.

---

## Dev-record accuracy

- Alignment 2 record — accurate and commendably self-incriminating: it correctly reported
  `NEEDS_CONTEXT` because "finishDrain … copies and sorts every outstanding label", i.e. it did NOT
  claim boundedness it hadn't achieved. That gap is real in the alignment-2 shape and is what r15 fixes.
- Alignment 3 record — accurate. The persistent-AVL structural sharing (O(log n) task / O(1) ticket),
  the single atomic epoch with prebuilt `DrainReport`, the expiry doing only claim + one
  `diagnosticSnapshot.get()` + submit, the `DrainProducerKey`-keyed concurrent map replacing the linear
  queue removal, and the probe-as-test-seam are all present exactly as described (`:75-78`, `:112-138`,
  `:276-288`, `:536-559`, `:602-620`, `:692-855`). The "identical natural/expiry reports" and "no
  expiry-time snapshot build or label traversal" claims are backed by tests that exist and (for the
  probe test) can fail.
- One nuance the record understates: it says the expiry hook does "only" claim/read/submit, but the
  submitted transition also does bounded map-remove + timer-cancel + inline future-complete-through-
  the-producer (MINOR-1). Honest characterization: bounded, compliant with §3.6b, slightly more than
  the literal r15 enumeration.

## What I verified and could NOT fault

The expiry hook is bounded (traced end-to-end; probe genuinely covers it and can fail). The
`synchronized (waiterReference)` barrier correctly closes the null-waiter publication race with real
happens-before, holds across only a bounded async hand-off, never runs on a tick thread, and has no
lock inversion. The diagnostic snapshot is immutable, atomically published, and untearable; counts and
labels cannot disagree; `liveTickets <= unresolvedTasks` holds by construction. Natural completion and
expiry share `requestFinishDrain` and therefore produce structurally identical reports for one epoch.
The AVL is correctly balanced (rotations checked), keeping updates O(log n)/O(1) with no tick-thread
O(n). The sealed drain terminalization (single CAS, waiter removal, deadline cancel, private-future-
only completion), the r12 Q4 tick-thread hop, and stage isolation are all intact. G6's `DrainReport`
is accurate and coherent at expiry.
