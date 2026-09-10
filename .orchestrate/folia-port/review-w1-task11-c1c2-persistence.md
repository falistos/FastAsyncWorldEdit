# Review — Task 11 correctives 1+2, persistence / exactly-once / result-correctness lens

Reviewer: fresh independent thread (Opus family). Perimeter: data persistence, exactly-once
completion semantics, result/history correctness. Concurrency/thread-ownership/liveness is a
parallel reviewer's perimeter; defects crossing into it are reported but not budgeted.

Everything cited below was read on disk. Authoritative texts read: `architecture.md` §1b, §3.6,
§3.6b, §3.6c; `spec.md` §4d; `tasks/11-completion-protocol.md` (mandate + 3 dev records);
`OperationCompletionService.java`, `DefaultOperationCompletion.java`, `CompletionServiceStage.java`,
`CompletionServiceFuture.java`, `HistoryPersistencePolicy.java`, `HistorySettlement.java`,
`AppliedReceipt.java`, `OperationResult.java`, `PacketPhaseResult.java`, `EntityAction.java`,
`ChunkTerminalRecord.java`, `TerminalStatus.java`, `OperationCompletion.java`,
`SameChunkPlanSequencer.java`, and all four test files.

---

## VERDICT

**REJECT**

One BLOCKING defect (a constructible, silent, permanent loss of the actor's terminal result — the
exact failure class §3.6/spec §4d exists to prevent), plus six MAJOR findings, two of which are
straight deviations from the §3.6b/§3.6c clauses corrective 2 was written to satisfy, and one of
which is an existing test that *asserts* the successful-empty completion r2 amendment 2 forbids.

The two corrective-1 MAJORs are genuinely closed. Corrective 2 is largely conformant on the
surfaces it touched, but its central claim ("full isolation of the CompletionStage/Future surface")
is false for one delivered surface, and both of the "invariant failure" guards the dev record cites
as the lease safety net are unreachable dead code.

---

## Disposition of the 2 corrective-1 MAJORs

### MAJOR 1 — Timer-handle retention (uncancelled `ScheduledFuture` retaining the coordinator)

**CLOSED.** Verified on the code, not the record:

- `OperationCompletionService.java:74` — `retryTimer.setRemoveOnCancelPolicy(true)`, so a cancelled
  handle is removed from the `DelayQueue` rather than lingering to its nominal delay.
- `DefaultOperationCompletion.java:434-438` — the shared `cancel(ScheduledFuture)` helper.
- `SettlementDeadline.reschedule` (`:532-542`) cancels the previous handle before replacing it and
  nulls via `cancel()` (`:556-561`); `deadlineReached` (`:544-554`) cancels before firing.
- `PersistenceSettlement.abort` (`:719-727`) cancels the deadline, `attemptTimeoutTask` and
  `retryTask` and nulls all three; `attemptFinished` (`:684-686`) cancels the attempt timeout.
- `FinalizerSettlement.abort` (`:608-611`) cancels its deadline.
- `terminate()` (`:428`) calls `retryTimer.shutdownNow()`.

I walked every path that can create a handle and found no path that leaves one live: `retryTask` is
assigned only at `:702` and consumed at `:649` (it has already fired at that point, so the
non-cancelling null there is correct), and every `producer.schedule` result is stored in a field
that `abort()`/`cancel()` reaches.

Test evidence is real, not nominal: `OperationCompletionServiceTest.java:362-398`
(`earlySettlementCancelsAndRemovesSharedTimerTasks`) uses a 30 s attempt timeout, 30 s backoff, 30 s
settlement deadline and 30 s finalizer timeout, settles immediately, and asserts
`service.scheduledTimerTaskCount() == 0`. Without both the explicit cancels and the remove-on-cancel
policy the queue would still hold four entries. `DefaultOperationCompletionTest.java:518` re-asserts
the same count on the exceptional-transition path.

### MAJOR 2 — Finalizer boundary lacked the drain clamp persistence had

**CLOSED for the finalizer specifically.**

- `DefaultOperationCompletion.java:565-613` — `FinalizerSettlement` now owns a `SettlementDeadline`
  built with `finalizerTimeout` (`:578-582`), and `finalizerTimeout` is validated positive/finite at
  `:58` via `requirePositiveDuration` (`:425-432`).
- `SettlementDeadline.start` (`:521-525`) registers `producer.onFlushing(this::reschedule)` and
  `remainingNanos` (`:527-530`) reads `completionService.effectiveDeadlineNanos(...)`
  (`OperationCompletionService.java:238-245`), which is `min(requested, flushDeadlineNanos)` and
  `flushDeadlineNanos` is set from the drain remainder at `:158`.
- `Producer.onFlushing` (`:490-505`) also handles the case where the settlement starts *after* flush
  began (`state == FLUSHING` → immediate enqueue), so late finalizers are clamped too.

Test evidence is real: `OperationCompletionServiceTest.java:327-359`
(`flushClampsNeverSettlingFinalizerAndPreservesWorldStatus`) uses a finalizer boundary that never
settles (`() -> new CompletableFuture<>()`) with a 5 s finalizer timeout and a 40 ms drain, and
awaits within a 3 s fixture budget. Without the clamp this test cannot pass. It also asserts the
§3.6b r4 am. 4 status separation (`COMMITTED` preserved, `PARTIAL` classification,
`TimeoutException` in `failures`), which is the correct property, not a weaker one.

**But the same defect class survives one level up and neither corrective addressed it** — a plain
registered plan (no settlement object, no deadline, no hook) fences flush with no clamp at all. See
finding **F3**. Corrective 1 fixed the instance, not the class.

---

## §3.6b / §3.6c conformance (corrective 2), clause by clause, in-lens

| Clause | Verdict | Evidence |
|---|---|---|
| §3.6b — result publication delegated to lifecycle-owned isolated notification tasks, never completed on the control executor (r8 am. 1) | **Conforms** for `OperationCompletion.future()` | `DefaultOperationCompletion.java:61` (`resultView = isolateStage(result)`), `:301` (`publishOutcome`), `OperationCompletionService.java:247-258` → `enqueuePublication` (`:353-371`) → virtual-thread `notificationExecutor` (`:53-62`). `result` is private and never escapes. Because every dependent of `result` is attached via `*Async(fn, notificationExecutor)`, `complete()` only *submits* dependents — it never runs one inline on the publishing thread. |
| §3.6b — "Synchronous consumer continuations never run on the control executor" | **Deviates** | `SameChunkPlanSequencer.java:84` returns a raw `ready.minimalCompletionStage()`; `:76` completes it on the control executor. See **F5**. |
| §3.6b — completion flush waits for internal transitions and outcome publication, not for consumer continuations | **Conforms** | `terminateIfQuiescent` (`:408-418`) gates on `producers`, `queuedTransitions`, `pendingOutcomePublications`, `producerLeases` only. Consumer continuations attached through `isolatedContinuationExecutor` (`:264-276`) get a *fresh* notification thread and are not counted. Proven by `OperationCompletionServiceTest.java:174-210`, which holds a `Runnable::run` consumer blocked forever and still reaches `TERMINATED`. |
| §3.6b — every persistence attempt has a finite timeout; the retry sequence has a finite settlement deadline; no config may select unbounded values | **Partially conforms** | Bounds are structurally finite and validated (`HistoryPersistencePolicy.java:21-34`, tests at `OperationCompletionServiceTest.java:400-426`). But the *synchronous prefix* of an attempt is unbounded and unmonitored — see **F4**. |
| §3.6b — a terminal persistence failure never decrements or bypasses the gate; it settles it with a recorded outcome | **Conforms** | `PersistenceSettlement.settle` (`:710-717`) always ends in `producer.complete(() -> persistenceSettled(...))`; `persistenceSettled` (`:224-237`) is the only place `pendingPersistence` is decremented on that path, and it always writes the settled record first. `unavailable` (`:705-708`) preserves the cause. |
| §3.6b r4 am. 4 — status separation; `SUCCEEDED` only when all required settlements are `DURABLE`; `FAILED` when no mutation applied; `PARTIAL` otherwise | **Conforms** | `isFailed` (`:321-331`) makes `COMMITTED` + `UNAVAILABLE` a failure while leaving `TerminalStatus` untouched; `buildResult` (`:304-319`) classifies on `committed` × `failed`. |
| §3.6b — no receipt exposed through `OperationResult` may remain pending | **Conforms** | The `pendingPersistence != 0` term in `maybeComplete` (`:290`) plus `markPersistencePending` (`:239-244`) on every path that starts or aborts an attempt, including the `requiresPersistence`-throws path (`:187-195`). |
| §3.6b — history scope: an `UNAVAILABLE` required settlement makes the operation-scoped entry unusable | **Conforms** | `OperationResult.java:33-43`. |
| §3.6c — `tryAcquireLease` is a synchronous, ACCEPTING-only, non-state-changing preflight | **Conforms** | `OperationCompletionService.java:101-118`: returns `Optional.empty()` on non-`ACCEPTING` with no side effect; duplicate identity throws without a second lease. |
| §3.6c — the lease remains registered through execution of its delivery command; delivery accepted during `FLUSHING` | **Conforms** | Lease removed only in `publicationFinished` (`:390-406`), i.e. after the publication task ran; `queueDelivery` (`:597-624`) has no `FLUSHING` guard. Proven by `OperationCompletionServiceTest.java:213-238`. |
| §3.6c — delivery idempotent per attempt, cannot affect another attempt | **Conforms** | `deliveryQueued` latch (`:600-602`, `:613`); per-attempt `future`. `OperationCompletionServiceTest.java:241-261`. |
| §3.6c — "Once a plan is registered, its lease is guaranteed"; the preflight fences the whole path | **Deviates** | The lease survives `FLUSHING` but `registerProducer` (`:79-92`) is `ACCEPTING`-only, so `OperationCompletion.register` can fail *after* a successful preflight. See **F6**. |
| §3.6c — reaching `TERMINATED` with an outstanding lease is an invariant failure, guarded | **Conforms in effect, but the guards are dead code** | `terminateIfQuiescent:413-415` already returns false, so `terminate():421-426` is unreachable; `queueDelivery:603-606` already throws, so `:607-612` is unreachable. The fence is real; the diagnostics the dev record advertises can never fire, and there is no bound on how long an abandoned lease stalls flush. See **F3** and Dev-record accuracy. |
| §3.6b/§3.6c — full isolation of the `CompletionStage`/`CompletableFuture` surface, including post-termination | **Deviates** | `CompletionServiceStage`/`CompletionServiceFuture` are complete and correct (I checked all 40-odd overrides plus `defaultExecutor`/`newIncompleteFuture`/`copy`/`completeAsync`/`minimalCompletionStage`), but `SameChunkPlanSequencer.Registration.ready()` bypasses them entirely. See **F5**. |

---

## Findings

### F1 — BLOCKING. `closeAdmission()` after the completion service has TERMINATED loses the operation result permanently and silently

**Where:** `DefaultOperationCompletion.java:86-95` (`closeAdmission` → `completionService.execute`),
`:289-302` (`maybeComplete`), `OperationCompletionService.java:121-139` (`execute`/`submit`),
`:325-339` (`runInline`), `:247-258` (`publishOutcome`).

**What is wrong.** `maybeComplete` mutates `state` past `OPEN` at `:294-300` and *then* calls
`completionService.publishOutcome(result, operationResult)` at `:301`. `publishOutcome` throws
`IllegalStateException` when the service is `TERMINATED` (`:251-253`). The only coordinator path
that can reach `maybeComplete` after termination is `closeAdmission()`, because it routes through
`completionService.execute(...)`, which for a `TERMINATED` service runs the command **inline** on the
caller's thread (`:131`, `:138`, `:325-339`). `runInline` catches the `Throwable` into
`outcomeFailure` and publishes it onto a `submitted` future that `Executor.execute` discards — so
the exception reaches nobody.

**Concrete interleaving** (uses only public API; it is `OperationCompletionServiceTest.java:94-124`
with two lines swapped):

```
service = new OperationCompletionService("...");
completion = new DefaultOperationCompletion(op, service, guard,
        FinalizerBoundary.none(), timeout, policy, PersistenceBoundary.notRequired());
completion.register(1, 1);                              // plan producer registered
completion.terminal(<op,1,1,NO_CHANGE,emptyReceipt>);   // -> producer.complete(NO_TRANSITION) -> deregistered
await(service.flush(Duration.ofSeconds(2)));            // producers empty, leases empty -> TERMINATED
completion.closeAdmission();                            // inline -> closeAdmissionOnService -> maybeComplete
                                                        //   admissionClosed=true, finalizersSettled=true,
                                                        //   pendingPersistence=0, outstandingTerminals=0
                                                        //   state := SUCCEEDED, then publishOutcome THROWS
```

**Outcome.** `completion.future()` never completes — the actor waits forever with no error.
`completion.state()` reports `SUCCEEDED`. The `IllegalStateException` is swallowed. `state != OPEN`
means no later `maybeComplete` can recover it. This breaches spec §4d sentence 2 ("Every
asynchronous failure must reach the actor/API completion exactly once") and §3.6's
`future()` "completes exactly once".

**Why this is a plausible ordering, not a contrived one.** §3.6 states that terminal success is
surfaced at `close()`/`flushQueue()` (APIC-057), while the shutdown order is
`stopAccepting → drain → completion flush → executor shutdown`. If drain terminalizes every plan
(which is exactly what §3.6's drain-terminalization wording *requires*) and the finalizers have
settled, the service reaches `TERMINATED` during flush. An `EditSession.close()` running after that
point calls `closeAdmission()` on a coordinator whose admission was never closed — and the actor's
result is silently lost. There is no assertion, no log, and no diagnostic anywhere on this path.

Note the asymmetry that makes this uniquely bad: `recordDuplicateTerminal` on the same inline path is
harmless, and the base dev record explicitly relies on inline post-termination submission
(`DefaultOperationCompletionTest.java:69`). So the inline path is *intended* to work — it just does
not work for the one command that publishes the result.

---

### F2 — MAJOR. r2 amendment 2's "aggregate rejection cause" is not implemented; an operation whose every registration failed resolves `SUCCEEDED`, and a test asserts it

**Where:** `DefaultOperationCompletion.java:86-95` (`closeAdmission` takes no cause),
`:304-319` (`buildResult`), `OperationCompletion.java:12-14`;
test `DefaultOperationCompletionTest.java:670-703`.

**What is wrong.** Architecture §3.6 r2 amendment 2 (required wording): *"Admission closure preserves
the aggregate rejection cause so an operation with no committed mutation resolves `FAILED`, not
successful empty completion."* The coordinator has no channel for an aggregate rejection cause:
`closeAdmission()` is a no-arg method, there is no field for it, and the *only* way a rejection cause
enters the coordinator is a `NOT_ACCEPTED` `ChunkTerminalRecord` — which requires a **successful**
`register()` first. When `register()` itself fails, or is never reached, the cause is unrepresentable.

`buildResult` then classifies an empty registration map as `SUCCEEDED`: `terminalRecords` is empty →
`committed == false` → `failed == false` (empty `failures`) → `Classification.SUCCEEDED` (`:308-317`).

**Concrete input.** `DefaultOperationCompletionTest.java:670-703` is precisely this case and asserts
the wrong outcome. The coordinator-context guard rejects `register(14, 140)` from the wrong thread
(line 690, exception captured at 692), then line 700-701 does:

```java
completion.closeAdmission();
assertEquals(OperationResult.Classification.SUCCEEDED, await(completion.future()).classification());
```

An operation whose sole plan registration was **rejected** is asserted to report `SUCCEEDED` to the
actor. That is the successful-empty completion r2 amendment 2 forbids, locked in by a green test.

The same shape is reachable without a guard: see **F6** (registration rejected because the service
turned `FLUSHING` between the lease preflight and `register`). It is also reachable whenever an
operation's chunk plans are all rejected before `register` succeeds.

**Why it matters in-lens.** `SUCCEEDED` is the one classification that tells the actor no undo is
needed and nothing failed. Producing it for a wholly-rejected operation is a false success — the
"no silent loss" property, inverted.

Note: I am *not* claiming a genuinely empty selection must be `FAILED`. The defect is that the
coordinator cannot distinguish "zero plans because there was nothing to do" from "zero plans because
every registration attempt failed", and the architecture's required wording exists exactly to force
that distinction.

---

### F3 — MAJOR. A registered plan that never receives a terminal fences `flush()` forever; the coordinator has no drain-terminalization mechanism and no test covers it

**Where:** `DefaultOperationCompletion.java:78-82` (`register` → `registerProducer`),
`OperationCompletionService.java:147-171` (`flush`), `:408-418` (`terminateIfQuiescent`),
`:238-245` (`effectiveDeadlineNanos`).

**What is wrong.** `flush(drainRemaining)` stores `flushDeadlineNanos` (`:158`) — and that value is
consumed by **exactly one** reader in the whole tree, `SettlementDeadline.remainingNanos`
(`DefaultOperationCompletion.java:528`). I verified this by grep: `effectiveDeadlineNanos` has one
call site. Flush itself is therefore **unbounded**: `terminateIfQuiescent` requires
`producers.isEmpty()` (`:409`), each `register()` adds a producer, and a plan producer that never
gets a terminal has *no settlement object, no deadline, and no `onFlushing` hook*. Nothing clamps it,
nothing times it out, nothing reports it.

**Concrete input:**

```java
service = new OperationCompletionService("...");
completion.register(1, 1);                      // never terminalized
await(service.flush(Duration.ofMillis(40)));    // hangs forever
```

Same for an abandoned `ProducerLease` (`:413-415`).

**Why it is a real contract breach, not a wiring problem.** §3.6's required drain wording says
*"Expiry of the shutdown drain deadline does not create an `INCOMPLETE` terminal state. Every
registered plan that has not mutated terminates as `CANCELLED_BEFORE_MUTATION` or
`FAILED_BEFORE_MUTATION`."* Task 11 delivers the component that *owns* the registration set and
already takes the drain remainder as a `flush` argument — and it has no API (`OperationCompletion`
exposes only `register`/`closeAdmission`/`terminal`/`future`) by which anything can force that
terminalization, nor any self-clamp. Corrective 1 recognised exactly this defect class for the
finalizer and fixed the instance; the class was left open one level up.

**The test that claims to cover this does not.** `DefaultOperationCompletionTest.java:617-637`
(`drainTerminalizationHasNoIncompleteState`) never calls `flush` while a plan is outstanding. It
hand-builds a `CANCELLED_BEFORE_MUTATION` record, feeds it in, asserts `FAILED`, and then asserts
`TerminalStatus.valueOf("INCOMPLETE")` throws — an assertion about an enum's declared constants, not
about drain behaviour. It would pass identically against a coordinator with no drain handling at all,
which is in fact what is on disk.

---

### F4 — MAJOR. Injected persistence/finalizer boundaries execute synchronously on the single control thread; the attempt timeout is armed only *after* the boundary call returns

**Where:** `DefaultOperationCompletion.java:186` (`requiresPersistence`), `:645-678` (`startAttempt`;
boundary invoked at `:659-662`, timeout armed at `:671-674`), `:585-598` (`FinalizerSettlement.start`,
`begin()` at `:589`); `OperationCompletionService.java:294-323` (`runQueued` holds
`serializationLock` for the whole command).

**What is wrong.** §3.6b: *"Every persistence attempt has a finite timeout."* In `startAttempt` the
boundary is called first and the timeout handle is created five lines later. The synchronous prefix
of `attempt(record, n)` is therefore untimed. Worse, it runs on the **single backend-owned control
thread** under `serializationLock`, so no armed deadline can preempt it: `SettlementDeadline`'s
expiry action is delivered through `producer.schedule(...)` → `producer.execute(...)` → the same
blocked executor (`:508-524`, `:460-471`). The same is true of `requiresPersistence` (`:186`) and
`FinalizerBoundary.begin()` (`:589`) — arming the finalizer deadline *before* `begin()` (`:586`)
does not help for the same reason.

**Concrete scenario, specific to this codebase.** FAWE's persistent history is
`worldedit-core/src/main/java/com/fastasyncworldedit/core/history/DiskStorageHistory.java` — file
I/O. Task 14 is directed to wire `PersistenceBoundary` to the real history subsystem. A boundary that
performs its write synchronously before returning a completed stage (the obvious implementation, and
the one every existing test uses: `(record, attempt) -> CompletableFuture.completedFuture(null)`)
puts per-chunk disk I/O on the control thread. On a stalled filesystem (full disk, NFS, hung device)
the call never returns: no attempt timeout was armed, the settlement deadline cannot fire, **every**
operation's coordinator transitions stop, and `flush` never completes. That is an unbounded,
never-settling persistence wait — the precise property §3.6b bounds.

**Aggravating:** `PersistenceBoundary` (`:459-482`) and `FinalizerBoundary` (`:448-457`) carry **no
javadoc at all**. The contract that an implementation must return promptly and must not block is
neither documented nor enforced, and the base dev record's "Attack points" for task 14 do not mention
it. Minimum fix: arm the timeout before invoking, and document/enforce the non-blocking contract (or
hop the boundary invocation off the control thread the way notifications already are).

---

### F5 — MAJOR. `SameChunkPlanSequencer.Registration.ready()` is an un-isolated stage completed on the control thread — corrective 2's "full isolation" claim is false for this surface

**Where:** `SameChunkPlanSequencer.java:84` (`readyView = ready.minimalCompletionStage()`),
`:75-77` (`activate` → `completionService.execute(() -> registration.ready.complete(null))`),
`:102-104` (`ready()` returns `readyView`).

**What is wrong.** This is the only externally observable stage in the delivered task-11 surface that
is not wrapped by `completionService.isolateStage(...)`. `ready` is a plain `CompletableFuture`
completed on the control executor. A raw JDK stage runs non-`Async` dependents **on the completing
thread**. So `registration.ready().thenRun(action)` runs `action` synchronously on the single
control thread.

§3.6b (r8 am. 1): *"Synchronous consumer continuations never run on the control executor."*
§3.6c (r7 am. 2): *"the completion service's single control executor never directly invokes an
externally observable future completion that can run arbitrary synchronous continuations on that
control thread."* This is a direct deviation, in a file corrective 2's file list does not mention,
while its dev record asserts *"every implicit, explicit, late, and `toCompletableFuture()`
continuation is routed through an isolated notification task"*.

**Concrete scenario.** The natural consumer (task 12) is
`registration.ready().thenRun(() -> dispatchPlan(...))`. Plan dispatch then executes on the control
thread, serialized ahead of every coordinator transition, every persistence settlement and flush. If
`dispatchPlan` blocks on a region-thread handoff or a saturated backpressure gate, the completion
sink for the entire backend wedges: no operation result publishes, no settlement advances, flush
never completes. `CompletionServiceStage` exists precisely to make that impossible; this stage does
not use it.

**Second-order:** `activate` (`:76`) uses `completionService.execute`, which post-`TERMINATED` runs
**inline on the caller's thread** (`OperationCompletionService.java:131`, `:138`). `register()` is
called from the dispatch path, i.e. potentially a region tick thread. A late `sequencer.register(k)`
after flush therefore completes `ready` on the tick thread and runs the consumer's continuation
there — §1b ("tick threads never run unbounded work").

Cost to fix is one line (`isolateStage`), which is why leaving it is hard to justify given the entire
point of corrective 2.

---

### F6 — MAJOR. §3.6c's producer-fence guarantee is broken between preflight and registration: `registerProducer` is ACCEPTING-only while the lease deliberately survives FLUSHING

**Where:** `OperationCompletionService.java:79-92` (`registerProducer`, `state != ACCEPTING` →
`RejectedExecutionException`) vs `:101-118` (`tryAcquireLease`) and `:597-624`
(`queueDelivery`, no `FLUSHING` guard); `DefaultOperationCompletion.java:78-80`.

**What is wrong.** §3.6c r7 am. 1 / r8 am. 2 requires: lease acquisition is the preflight, it happens
*before* `OperationCompletion.register` and before accounting, and — the load-bearing sentence —
*"Once a plan is registered, its lease is guaranteed and every settlement, including lifecycle
rejection, has a fenced delivery path to exactly one terminal notification."* The intent is that a
successful preflight makes the rest of the path total.

It is not. The lease is intentionally durable through `FLUSHING` (correct, and tested), but
`register()` → `registerProducer()` throws `RejectedExecutionException` the moment the state leaves
`ACCEPTING`. There is no atomicity between the two.

**Concrete interleaving:**

```
T1 (task 13):  tryAcquireLease(id)   -> ACCEPTING, lease granted
T2 (shutdown): flush(remaining)      -> state := FLUSHING
T1:            completion.register(chunkKey, planSequence)
                 -> registerProducer -> RejectedExecutionException
```

The plan is now unregisterable although its preflight succeeded. Task 13 must `deliverExceptionally`
to release the lease (the dev record's attack point says so), which avoids the flush hang of **F3** —
but the coordinator never learns the chunk existed. Compose with **F2**: if this was the operation's
only plan, `closeAdmission()` yields `Classification.SUCCEEDED` with an empty `terminalRecords` list.
An operation rejected wholesale at shutdown reports success to the actor.

The fix belongs in this task, not task 13: either `registerProducer` accepts during `FLUSHING` for
leases already held (mirroring `queueDelivery`), or the lease carries the producer registration.

---

### F7 — MAJOR. `SameChunkPlanSequencer.terminal()` on the queue *head* throws before its own async `ready()` fires, permanently wedging that chunk's lane

**Where:** `SameChunkPlanSequencer.java:52-73` (guard at `:59`: `!registration.ready.isDone()`),
`:46-48` + `:75-77` (`ready` completed asynchronously through the completion service).

**What is wrong.** `register()` returns as soon as it has *submitted* `ready.complete(null)` to the
completion service. For the first registration on a chunk there is no predecessor, so the logical
precondition is already satisfied — but `ready.isDone()` is `false` until the control thread runs the
submitted task. Calling `terminal()` in that window throws
`IllegalStateException("A same-chunk plan cannot terminate before its predecessor")` — a message that
is actively misleading for the head case, since there is no predecessor.

**Concrete input.** This is the mainline rejection path of r2 amendment 2: *"Every chunk ticket
entering admission is registered before its admission attempt. Rejection before acceptance terminates
that registration exactly once with `NOT_ACCEPTED`."* A synchronous backpressure rejection
immediately after `sequencer.register(k)` hits the window essentially every time:

```java
var reg = sequencer.register(chunkKey);   // submits ready.complete asynchronously
// admission rejected synchronously
reg.terminal();                            // IllegalStateException, ~always
```

**Outcome.** The registration is never removed from the chunk's `ArrayDeque` (`:62-63` never runs), so
every later plan for that chunk waits on a `ready` that will never complete. Their terminals never
arrive → their coordinator producers never deregister → **F3**'s flush hang → the operation's
`future()` never completes. One mistimed rejection poisons a chunk lane for the process lifetime.

**The test does not cover it.** `SameChunkPlanSequencerTest.java` always does `await(firstReady)`
before `first.terminal()` (`:35`, `:39`). Line 33 asserts the throw for the *non-head* case, which is
correct behaviour, and thereby gives the impression the guard is safe. Nothing documents that
`terminal()` is illegal until `ready()` has fired, and `ready()`'s own javadoc (`:101`) states the
opposite intuition ("Completes only after every earlier registration for this chunk has terminated" —
vacuously true for the head).

---

### F8 — MINOR. A *contradictory* terminal for an already-terminal key is silently counted as a benign duplicate

**Where:** `DefaultOperationCompletion.java:109-115` and `:165-168`.

Dedup compares only the key. A second `terminal(...)` for the same
`(operationId, chunkKey, planSequence)` bearing a **different** status or a different
`AppliedReceipt` increments `duplicateTerminals` and is discarded, indistinguishable from a benign
retry. Scenario: an upstream bug delivers `NOT_ACCEPTED` first and `COMMITTED` + a non-empty receipt
second; the operation reports `FAILED`, `historyUsable()` is meaningless, and the applied subset —
the undo source of truth — is silently dropped for a chunk that did mutate the world. Given the named
project risk, an equality check on the discarded record with a distinct diagnostic counter is cheap
and high-value. Classified MINOR only because it requires an upstream protocol violation to trigger.

### F9 — MINOR. `Producer.execute` silently drops a transition when the producer is deregistered or completing

**Where:** `OperationCompletionService.java:460-471` — returns an already-completed stage and
discards the transition.

I traced every caller and could **not** currently construct a lost terminal through it: the only
paths that set `completionRequested` on a plan producer (`complete` at `:474-487`, `transitionFailed`
at `:535-548`) all run after `plan.record` is set, so `terminal()`'s dedup check
(`DefaultOperationCompletion.java:109`) catches the follow-up. But a silently-dropping submit is a
sharp primitive for an exactly-once sink, and the drop does **not** invoke the producer's
`transitionFailure` settlement — so corrective 1's stated invariant ("no producer API remains that
can omit that settlement callback") holds for *thrown* transitions but not for *dropped* ones.
Returning a failed stage, or asserting, would make a future regression loud.

### F10 — MINOR. `leasePreflightRacingFlushCannotArriveUncounted` can pass vacuously

**Where:** `OperationCompletionServiceTest.java:263-284`, guard `if (lease != null)` at `:275`.

If `flush()` wins the race, `tryAcquireLease` returns empty and the interesting branch (deliver a
lease acquired concurrently with flush) is never exercised; the remaining assertions hold trivially.
The test is not wrong, but it does not deterministically prove what its name claims. A deterministic
version would use `blockCompletionService` (already in the fixture) to pin the interleaving, as
`concurrentDuplicateTerminalIsCountedExactlyOnce` correctly does.

### F11 — MINOR. `ChunkTerminalRecord.applied` may be null; `OperationResult` accessors NPE on it

`ChunkTerminalRecord.java:7-16` has no compact constructor. `DefaultOperationCompletion.validateRecord`
(`:333-351`) rejects null `applied` on the `terminal()` path, but `OperationResult`'s own compact
constructor (`OperationResult.java:17-25`) does not, and `unavailableHistoryChunks()` (`:38-43`)
dereferences `record.applied()` unconditionally. Also, `failures` is copied defensively but holds
mutable `Throwable`s (a consumer can `addSuppressed`/`initCause`/`setStackTrace` on a published
result) — standard for Java, noted only for completeness since the mandate asked about published-result
mutability.

---

## Dev-record accuracy

Claims the artifacts do **not** support:

1. **Corrective 2:** *"every implicit, explicit, late, and `toCompletableFuture()` continuation is
   routed through an isolated notification task"* and *"full isolation of the CompletionStage/Future
   surface including post-termination"*. False for `SameChunkPlanSequencer.java:84`, an externally
   observable stage completed on the control thread (**F5**). `SameChunkPlanSequencer` appears in the
   corrective-1 file list but not corrective 2's, and its `readyView` was evidently not re-audited
   against the rewritten §3.6b/§3.6c.

2. **Corrective 2:** *"Termination is fenced by outstanding leases and guarded by an
   assert-plus-diagnostic invariant at lines 408–430."* The fence is real; both cited guards are
   **unreachable dead code**. `terminate()` (`:420`) has exactly one call site (`:416`), inside
   `terminateIfQuiescent`, which already returned `false` at `:413-415` when leases remain — so
   `:421-426` can never execute. Symmetrically, `queueDelivery`'s `TERMINATED` diagnostic (`:607-612`)
   is preceded by the lease-registration check at `:603-606`, which throws first in every reachable
   ordering. The advertised safety net cannot fire; what actually happens to an abandoned lease is an
   unbounded, undiagnosed flush hang (**F3**).

3. **Base record / test strategy:** the mandate's *"drain terminalization (no INCOMPLETE state)"* is
   listed as covered, and the test is named `drainTerminalizationHasNoIncompleteState`
   (`DefaultOperationCompletionTest.java:617`). It exercises no drain: it feeds a hand-built
   `CANCELLED_BEFORE_MUTATION` record and asserts an enum constant does not exist. The §3.6 property
   ("every registered plan that has not mutated terminates as ...") is neither implemented nor tested
   (**F3**).

4. **Base record:** *"the injectable `HistoryPersistencePolicy` exposes only finite
   attempt/timeout/backoff/deadline knobs"* — true of the record, but the derived guarantee
   ("every persistence attempt has a finite timeout") does not hold, because the timeout is armed only
   after the injected boundary call returns and the boundary runs on the blocked control thread
   (**F4**).

5. **Corrective 1:** *"no producer API remains that can omit that settlement callback"* — holds for
   thrown transitions; does not hold for transitions silently dropped by `Producer.execute`
   (`:460-471`, **F9**).

Line references in the base dev record have drifted from the on-disk file (e.g.
`DefaultOperationCompletion.java` "line 235"/"line 422" no longer point at the cited constructs);
cosmetic, but it means the record cannot be used to navigate the current tree.

---

## What I verified and could NOT fault

Specific properties I attacked and could not break:

- **Exactly-once terminal, normal lifecycle.** Every coordinator state mutation is either inside a
  `synchronized (this)` entry point (`register` `:66`, `closeAdmission` `:88`, `terminal` `:103`) or
  runs as a queued transition on the single control executor under `serializationLock`
  (`OperationCompletionService.java:302-317`). `outstandingTerminals` is decremented in exactly two
  places (`:170`, `:267`), both of which assign `plan.record` in the same critical section, so
  `buildResult`'s `plan.record` can never be null when `maybeComplete`'s gate opens. I could not
  construct a double-decrement or a terminal counted twice.
- **Duplicate suppression under a genuine race.** Both `terminal()` calls can pass the
  `plan.record == null` check concurrently, but the second queued `terminalOnService` sees the record
  and returns at `:165-168` without re-running persistence or touching `outstandingTerminals`.
  `concurrentDuplicateTerminalIsCountedExactlyOnce` (`DefaultOperationCompletionTest.java:75-103`)
  forces exactly that interleaving with `blockCompletionService` and asserts `persistenceCalls == 1`
  — a genuine, non-vacuous test.
- **Duplicate after publication and after service termination** does not flip the aggregate:
  `DefaultOperationCompletionTest.java:67-71` runs a duplicate post-`flush` via the inline path and
  asserts the count and the unchanged persistence call count.
- **No-commit → `FAILED` on the code path** (not just the test name): `validateRecord:344-346`
  forces `NOT_ACCEPTED` to carry a cause; `isFailed:322` short-circuits on the present failure and
  the `NOT_ACCEPTED` enum arm at `:329` returns `true` independently; `committed` is `false` →
  `Classification.FAILED` at `:315`. Holds whenever at least one plan registered. (The zero-registered
  case is **F2**.)
- **Settlement bounds are finite and every settlement path is terminal** — for the asynchronous
  portion. I enumerated `PersistenceSettlement`'s exits: success (`:687-689`), attempts exhausted
  (`:692-695`), deadline exhausted before start (`:650-654`), remaining ≤ backoff (`:696-701`),
  deadline expiry (`:636`), boundary throws (`:663-665`), and coordinator-transition failure
  (`planTransitionFailed:254-257` → `abort`). All converge on `settle(...)` → `producer.complete(...)`
  → `persistenceSettled`, and `settled`/`attemptGeneration` make the winner unique. Attempt timeout
  and cancellation each consume an attempt as §3.6b requires — proven non-vacuously by
  `attemptTimeoutConsumesAttemptAndRetries` (`:413-450`, asserts `attempts == 2` then `DURABLE`) and
  `cancelledPersistenceConsumesAttemptAndRetries` (`:453-486`).
- **`HistorySettlement` is finalized before publication.** The `pendingPersistence != 0` term of
  `maybeComplete:290` is set by `markPersistencePending` on every path that starts an attempt,
  including the `requiresPersistence`-throws path (`:187-195`), and cleared only in
  `persistenceSettled:229-232` / `planTransitionFailed:273-276`. I could not find a window where a
  `NOT_REQUIRED`-but-pending receipt reaches `buildResult`.
- **Result immutability.** `AppliedReceipt`'s compact constructor `List.copyOf`s all three lists
  (`AppliedReceipt.java:17-22`); `OperationResult` `List.copyOf`s both of its lists
  (`OperationResult.java:20-21`); `buildResult` builds a fresh `terminalRecords` list. The coordinator's
  `result` future is private and only `resultView` escapes, so no consumer can complete or obtrude it.
  `appliedReceiptDefensivelyCopiesInnerLists` (`DefaultOperationCompletionTest.java:591-615`) tests
  both post-construction mutation and `UnsupportedOperationException` on the exposed list — a real test.
  No torn read is possible: every record is immutable and published through a `volatile`-equivalent
  happens-before (control-executor submission, then `CompletableFuture.complete`).
- **Lease double-delivery.** `deliveryQueued` under `lifecycleLock` (`:600`, `:613`) with the
  `enqueuePublication` failure path resetting it (`:616-619`); cross-attempt isolation via a
  per-attempt `future`. Verified against `producerLeaseDeliveryIsIsolatedPerAttempt` (`:241-261`).
  Lease release happens only after the publication task ran (`publicationFinished:390-406`), and the
  `remove(key, value)` form prevents releasing another attempt's lease.
- **The `CompletionStage`/`CompletableFuture` adapters are complete.** I checked every override in
  `CompletionServiceStage` (all 3-arity families plus `exceptionallyCompose*` and
  `toCompletableFuture`) and `CompletionServiceFuture` (including `defaultExecutor`,
  `newIncompleteFuture`, `copy`, `completeAsync`, `minimalCompletionStage`). Non-`Async` forms are
  redirected to the notification executor; explicit-executor forms are hopped through a notification
  virtual thread first (`isolatedContinuationExecutor:264-276`), so a direct/inline executor cannot
  reach the control thread. The `requested == this` special case correctly maps an explicit
  "run on the completion service" request onto a notification task.
- **Flush does not wait on consumer continuations.** `terminateIfQuiescent:408-418` counts only
  producers, queued transitions, publications and leases; a consumer blocked forever on a
  `Runnable::run` executor still permits `TERMINATED`
  (`OperationCompletionServiceTest.java:174-210`) — a strong, non-vacuous test.
- **`HistoryPersistencePolicy` rejects every unbounded/non-positive configuration**
  (`HistoryPersistencePolicy.java:21-34`, all four rejection cases tested at
  `OperationCompletionServiceTest.java:400-426`), and `Duration.toNanos()` is called eagerly so an
  overflowing `Duration` fails at construction rather than at scheduling time. `saturatedAdd`
  (`:436-441`, `DefaultOperationCompletion.java:418-423`) correctly prevents deadline wraparound.
- **Lock ordering.** I traced `serializationLock` → coordinator monitor → `lifecycleLock` as the only
  nesting order (`runQueued` → `terminalOnService` → `maybeComplete` → `publishOutcome`); external
  entry points take the monitor and release it before touching `lifecycleLock`;
  `Producer.transitionFailed` releases `lifecycleLock` before invoking the coordinator callback
  (`:535-548`); `submit` releases `lifecycleLock` before `runInline`. I found no inversion. (Flagged
  for the concurrency reviewer's cross-check, not claimed as cleared.)
- **`SameChunkPlanSequencer` sequence allocation** is globally monotonic, unique across chunks, and
  rejects before overflow (`:38-40`); the invariance requirement of r2 amendment 1 holds because
  `planSequence` is a `final` field allocated once at `register` and never re-derived.
