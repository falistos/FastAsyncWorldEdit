# Review — Task 13 `DefaultFoliaBackpressure`, fresh-implementation correctives 1 & 2

Reviewer: fresh adversarial thread (no prior context). Read: `tasks/13-backpressure.md` (full),
`architecture.md` §1b/§3.4/§3.6b/§3.6c/§3.6d/§3.7, `DefaultFoliaBackpressure.java` (1618 lines,
complete), `DefaultFoliaBackpressureTest.java` (1244 lines, complete), and the binding collaborator
`worldedit-core/.../util/task/OperationCompletionService.java` (the threading of that class decides
most of this review), plus `CompletionServiceStage`, `RegionKey`.

Nothing was edited. Gradle was not run.

---

## VERDICT

**PASS-WITH-NOTES**

All five prior BLOCKINGs are closed, and I could not break them by construction. Both r14 rulings
(three-outcome CAS; pure-accounting no-wait permit lifecycle) conform clause by clause. Accounting
conservation holds on every path I could enumerate, including bytes, and I could not construct a
cap breach, a double-release, or a leaked region counter.

The notes are not cosmetic. Four MAJORs must be dispositioned before task 14 consumes this, and
**three tests cannot fail on the property they name** — one of them (`deadlineExpiresWhileCommon
PoolIsSaturated`) is the sole named acceptance evidence for a previously-BLOCKING finding. I
verified that property independently by inspection, which is why this is not a REJECT; but the
evidence as shipped is void and must be repaired before the task is closed.

---

## Disposition of the five prior BLOCKINGs

### 1. `deliverAll` had no per-delivery exception isolation — **CLOSED** (with a relocated residue, see MAJOR-1)

`DefaultFoliaBackpressure.java:531-547`. The entire body of the loop — including the
`deliverySubmitted` CAS, the `producer.submit(...)` call and `recoverDeliveryFailure` — is inside a
per-iteration `try { ... } catch (Throwable)`. There is no statement in the loop outside the guard,
so no throw source can abandon later entries.

`recoverDeliveryFailure` (`:549-562`) is itself double-guarded: `settleLostDelivery` and
`producer.reject` each get their own `try/catch(Throwable)` with `addSuppressed`, so a settlement
failure cannot suppress the rejection publication.

I attempted to reconstruct the original failure: submit throws for waiter 1 of 3 → caught →
`settleLostDelivery` releases waiter 1's accounting and `producer.reject` publishes → loop continues
to waiters 2 and 3. Permits release, futures settle, producers deregister, flush can quiesce.
Reproduction fails. Closed.

**Can a throw escape onto a region thread through `close()`/`transfer()`'s `finally`?** No.
`deliverAll` is reachable only from `acquire` (contractually never a tick thread) and
`serviceWaiters` (completion-service control thread). The region-thread paths
(`close`→`closeClaimedPermit`→`release`, `enter`, `transfer`) reach only `signalCapacityReleased`
(`:764-785`), whose only throw-capable call is `submitWaiterService`, and that catches `Throwable`
per attempt (`:814-816`). `release`'s two throw sites — `requiredState` (`:1177-1183`) and
`removeReadySince` (`:1266-1273`) — are unreachable while the permit is live: `pruneIfIdle`
(`:1185-1187`) removes a `RegionState` only when `isIdle` (`:1199-1210`), which requires
`livePermits == 0`, and `livePermits` is decremented last in `release` (`:1008`) and incremented
first in `grant` (`:851-855`), both inside the permit's own lifetime. `readySince` bookkeeping is
gated by `permit.readyCapacityHeld` and cleared on first release (`:1013-1025`).

### 2. Continuations bypassed byte/chunk accounting — **CLOSED**

`canAdmitContinuation` (`:1061-1073`) validates regional ready chunks, regional ready **bytes**,
the continuation finalizer carve, global ready chunks, global ready **bytes** and global
finalizers. `grant` (`:845-869`) charges `state.readyChunks`, `state.readyBytes`,
`globalReadyChunks`, `globalReadyBytes` and `globalFinalizers` for **both** kinds; the kind only
selects which finalizer partition is charged (`:862-867`). `releaseReadyCapacity` (`:1013-1025`)
releases both regional and global byte/chunk reservations exactly once via
`readyCapacityHeld`/`globalReadyHeld`.

The specific old symptom — `pressure().readyBytes` reporting 0 while prepared bytes grew — is gone:
`pressure()` (`:225-234`) reads `state.readyBytes`, which is the *same* counter `canAdmit*` gates
on. The invisible-fail-open is closed.

This is bound by two tests that genuinely discriminate (see test section, #5 and #6).

### 3. `tryAcquire` blocked and allocated — **CLOSED**

`:278-301`. The whole body is: `validateDemand` (null checks + one `nanoTime`), `requireUnaccepted`,
`deadlineStatus`, `accountingLock.tryLock()`, `regions.get`, `canAdmitRegular`, `grant`.

- No completion-service call. `validateDemand` touches `registeredPlan.operationId()`, which is
  `key.operationId()` on a record — lock-free (`OperationCompletionService.java:952-954`). Verified,
  because a `lifecycleLock` acquisition here would have been the regression.
- `grep` over the class confirms: no `ForkJoinPool`, no `commonPool`, no `delayedExecutor`, no
  `Executors`, no `Thread` construction, no `ThreadFactory` anywhere in the file.
- `accountingLock.tryLock()` is a single non-blocking attempt; there is no retry loop.
- On a miss the only allocation is `Optional.empty()` (interned singleton).

The one blemish is MINOR-2 below: `deadlineStatus` (`:1137-1146`) *throws* `IllegalArgumentException`
from this tick-thread fast path when the demand's horizon exceeds `maxAdmissionWaitNanos`.

### 4. Deadline expiry on the JDK common ForkJoinPool — **CLOSED**

`scheduleDeadline` (`:382-399`) uses `waiter.producer.schedule(...)` exclusively.
`AdmissionProducer.schedule` (`OperationCompletionService.java:1222-1260`) schedules on the
service's dedicated `retryTimer` (`:102-107`, a single-thread `ScheduledThreadPoolExecutor` with
`setRemoveOnCancelPolicy(true)`), clamped to `producer.flushDeadlineNanos()` — so it is inside the
lifecycle fence. The timer body runs `linearizeExpiry.getAsBoolean()` then `submit(expirySettlement)`
and nothing else.

`Waiter.linearizeExpiry` (`:1458-1460`) is one `AtomicReference` CAS (`QUEUED → EXPIRY_LINEARIZED`).
The timer thread never touches `accountingLock` and never runs delivery or user code; the actual
`settleExpiry` (`:434-454`, which does take `accountingLock`) runs as a producer command on the
control path, which is exactly what §3.6c item 7 prescribes.

Failure to schedule is handled: `:393-398` catches and routes through
`producer.submit(settleProducerFailure)` with a direct fallback.

### 5. Cancellation cascade on the consumer's thread — **CLOSED**

`:331`:

```java
cancellationSignal.whenComplete((ignored, failure) -> producer.submit(waiter.cancellationSettlement));
```

The inline body is a single `AdmissionProducer.submit` — an O(1) lock-free append to that attempt's
command chain plus at most one `executor.execute` on an unbounded queue
(`OperationCompletionService.java:1292-1325`, `:447-455`). No accounting lock, no waiter scan, no
permit transition, no future completion, no hook, no thread. Settlement (`settleCancellation`,
`:401-432`) runs later on the control path.

If `whenComplete` attachment itself throws (a hostile caller stage), `:332-335` catches and routes
to `producer.reject`. If the *body* throws (e.g. `RejectedExecutionException` from a shut-down
executor), `CompletableFuture` captures it into the discarded derived stage rather than rethrowing
on the completing thread, so nothing escapes onto a region thread.

This is bound by a real contention test (#9) that would hang for the full 3 s latch if the hop took
`accountingLock`.

---

## Conformance to the two new rulings

### Ruling 1 — three-outcome CAS

| Clause | Verdict | Evidence |
|---|---|---|
| `deliver`/`reject`/`cancel` compete on one attempt-level CAS, exactly one wins | **CONFORMS** | Core `appendCommand` (`OperationCompletionService.java:1292-1319`): all three pass `closesAttempt=true`, and the winner is whoever CASes `commandTail → closedMarker`; every subsequent attempt returns `false` at `:1293` or `:1309`. `publishOutcome` has a second `outcomeReserved` CAS (`:1381`). |
| `reject` terminalizes the plan `NOT_ACCEPTED`; retry needs a NEW plan sequence | **CONFORMS** | `rejectOnControl` (`:1369-1378`) calls `registeredPlan.rejectAdmission(cause)` before publishing. Bound by test #13 (`lifecycleRejectionTerminalizesPlan`), which asserts a subsequent `token.terminal(...)` returns `false`. |
| `cancel` must **not** terminalize the plan | **CONFORMS** | `AdmissionProducer.cancel` (`:1271-1278`) publishes the outcome only; it never touches `PlanProducerToken`. Bound by test #12 (`cancelledAttemptCanRetryOnSamePlanSequence`) — a re-`acquire` on the same plan token would fail at `tryAcquireAdmissionProducer`'s `acceptsAdmissionLocked()` check (`:190`, `:1007-1011`) if the plan had been terminalized. |
| Permit accounting released exactly once **before** `cancel(...)` on GRANTED-but-undelivered | **CONFORMS** | `settleCancellation` `:417-421` releases under `accountingLock` and sets `REJECTED`; the lock is released at `:426`; `producer.cancel` is called at `:428`. The `REJECTED` state makes every other release path (`settleFailureLocked` `:522`, `deliverSettled` `:572`) a no-op, so the release is exactly once. |

**Call-site audit — every site classified:**

| Site | Call | Class | Correct? |
|---|---|---|---|
| `:320` empty producer acquisition | `registeredPlan.rejectAdmission` | lifecycle | ✅ |
| `:334` cancellation-stage attachment threw | `producer.reject` | setup failure | ✅ |
| `:428` caller-initiated cancellation | `producer.cancel` | cancellation | ✅ **the only `cancel`** |
| `:451` deadline expiry | `producer.reject` | deadline | ✅ |
| `:467` producer/schedule failure | `producer.reject` | lifecycle | ✅ |
| `:557` lost delivery submission | `producer.reject` | internal failure | ✅ |
| `:626` post-settlement publication | `producer.reject` | see below | ✅ (loses the CAS) |
| `flushExpired` `:471-486` | core publishes via `runFlushExpiry`→`queueRejection` | drain deadline | ✅ |

`:626` deserves the scrutiny, because it is the one place `reject` can be called carrying a
`CancellationException` (`deliverSettled` reads `waiter.failure` for a `REJECTED` waiter, `:572`).
If that `reject` could win the CAS ahead of `settleCancellation`'s `cancel`, a caller cancel would
burn the plan sequence. **It cannot.** `deliverySettlement` and `cancellationSettlement` are commands
on the *same* `AdmissionProducer` chain (`:537` and `:331`), drained strictly FIFO by `drainOne`
(`:1327-1348`), and *every* command from *every* producer additionally funnels through the service's
single-thread `executor` under `serializationLock` (`runQueued`, `:457-489`). So the cancel command
always executes and claims the CAS before the delivery command observes `REJECTED`; `:626`'s
`reject` then returns `false` and is logged at debug (`:627`). Verified rather than assumed.

The same serialization is what makes `deliverSettled`'s unlock→`deliver`→relock window (`:589-621`)
safe. I specifically checked which settlement paths are *not* on the control path — only
`settleProducerFailure`'s direct call at `:397` and `settleLostDelivery` via `deliverAll` from the
`acquire` caller thread — and neither can be in flight while a delivery command for the same waiter
is executing. The code comment at `:592-593` is accurate, and I confirmed it against the core rather
than taking it.

### Ruling 2 — pure-accounting, no-wait permit lifecycle

| Clause | Verdict | Evidence |
|---|---|---|
| `transfer`/`enter(FINALIZING)`/`close` require no producer or lease | **CONFORMS** | `BackpressurePermit` (`:1472-1527`) stores no producer and no lease. |
| They publish nothing, complete no stage, invoke no callback | **CONFORMS** | `transfer` `:871-909`, `enter` `:911-948`, `close` `:950-984`, `release` `:986-1011`: no `producer.*`, no `delivery.*`, no `rejectionHandler`. |
| **MUST NOT wait on `accountingLock`** | **CONFORMS** | Every `accountingLock.lock()` in the class is at `:266, 339, 404, 437, 458, 478, 493, 568, 600, 633, 652` — `stopAccepting`, `acquire`, and the control-path settlement commands. **None** on a permit-lifecycle path. `transfer` uses a single `accountingLock.tryLock()` (`:877`) and returns `false` on a miss; `enter` and `close` touch no lock at all. `signalCapacityReleased` uses `tryLock` (`:766`). |
| MUST NOT enter completion serialization | **CONFORMS** | The lifecycle paths reach the service only through `submitWaiterService`→`AdmissionProducer.submit`, which appends and hands off; it never runs under `serializationLock`. |
| No unbounded spin | **CONFORMS** | `claimPermitOperation` (`:957-960`) and `close` (`:952`) are single CASes, not loops. `finishPermitOperation` (`:962-976`) is straight-line. `submitWaiterService` is bounded to 2 attempts (`:809`). The only retry loops in the class are `updateEwma` (`:1304-1316`, telemetry, lock-free) and `ConcurrentSkipListMap.compute` inside `addReadySince`/`removeReadySince` — both lock-free, neither a lock. |
| Exactly-once release under concurrent close/transfer/expiry | **CONFORMS** | `closeRequested` is published *before* `close`'s owner CAS (`:951-952`); `finishPermitOperation` re-reads it both before and after the `IDLE` store (`:967`, `:972`) so no request is lost and no second owner is created; `release`'s `permit.closed` guard (`:987`) is only ever reached by the single ACTIVE owner. I walked both orderings (owner-releases-first and closer-arrives-first) and both converge on one `release`. Bound by test #21, which runs the race 100× and probes the *global* counter afterwards — a double-release would make the probe over-admit and a missed release would make it under-admit. |
| Capacity released by permit A wakes waiter B through **B's own** producer | **CONFORMS** | `signalCapacityReleased` picks the sponsor from `continuationWakeCandidate`/`regularWakeCandidate` (`:776-779`), which are queued *waiters* (`:787-790`). A permit has no producer to route through. Bound by test #22, which explicitly waits for A's producer to be released before closing A. |

**Multi-counter soundness under lock-free release.** This is the subtle part of ruling 2 and it
holds. All *capped* dimensions (`readyChunks`, `readyBytes`, `reserved*Finalizers`, `global*`) are
**incremented** only under `accountingLock` (`grant` `:845-869`, `moveRegionalAccounting`
`:1092-1126`); the lock-free paths only ever **decrement** them. An admission decision that reads a
partially-applied release therefore sees an *under*-estimate of free capacity — conservative refusal,
never over-admission. `enter(SCHEDULED)`/`enter(FINALIZING)` increment `scheduledChunks` and
`activeFinalizers` lock-free, but neither is a gated dimension. **No cap can be breached.**

---

## Findings

### BLOCKING

None.

### MAJOR

**MAJOR-1 — `serviceWaiters` collects settled, already-charged waiters and submits them outside any
guard; a throw there strands them permanently and completion flush can never quiesce.**
`DefaultFoliaBackpressure.java:648-687`.

`serviceWaiterQueue` settles waiters under the lock (`GRANTED` with `grant(...)` charged, `:745-751`;
or `REJECTED`, `:709/:718/:732`), removes them from the queue set, decrements waiter accounting, and
accumulates them into a *local* `deliveries` list. The list is only handed to `deliverAll` at `:685`,
after the lock is released — and the whole span `:652-685` has no try/finally that guarantees the
list is drained.

Interleaving: a batch settles waiters W1..W64, `accountingLock` is released, then anything between
`:677` and `:685` throws. `drainOne` catches it (`OperationCompletionService.java:1339-1341`) and
tears down the *sponsor's* producer only. W1..W64 are now: removed from the queue, charged with ready
chunks/bytes/finalizers, in state `GRANTED`, with **no delivery command ever submitted**. Their
futures never complete (operation hangs — the exact named risk), their permits are never closed
(accounting leaks forever, `isIdle` never true, region never evicted), and their producers are never
released, so `terminateIfQuiescent` (`:665-675`) can never fire and **shutdown flush hangs**.

This is BLOCKING-#1's failure shape relocated one frame up. I could not construct a reachable throw
in `:677-685` — `beforeWaiterDeliverySubmission` is guarded (`:679-683`), `refreshWakeCandidatesLocked`
and `scheduleNextWaiterServiceLocked` are total, and `initialRejection`'s `IllegalArgumentException`
branch is unreachable because `now` is captured once at `:649` and only shrinks the remaining
horizon — which is why this is MAJOR and not BLOCKING. But the isolation that was added to
`deliverAll` was not extended to its caller, and the invariant "a waiter that has been settled and
charged is always submitted for delivery" is currently held by exhaustive-case reasoning rather than
by construction. Wrap `:652-685` so that `deliverAll(deliveries)` runs in a `finally`.

**MAJOR-2 — `pruneRequests` and the `regions` map can grow without bound under sustained
`accountingLock` contention.** `:1010`, `:1189-1197`, `:764-772`, `:87-88`.

`release()` — the dominant permit path, executed on region threads — cannot prune, so it offers a
`PruneRequest(region, state)` onto an unbounded `ConcurrentLinkedQueue` (`:1010`). Draining happens
only in `drainPruneRequestsLocked`, called from `requestWaiterServiceLocked` (waiter settlement only),
`flushExpired`, and `signalCapacityReleased` — where it is **conditional on `accountingLock.tryLock()`
succeeding** (`:766`). Under sustained contention (many concurrent `acquire` calls plus control-path
settlement all take the lock blocking), the tryLock misses, no drain happens, and every close adds an
entry. Each entry pins a `RegionKey` and a whole `RegionState` including a `ConcurrentSkipListMap`.

On Folia, `RegionKey` values churn continuously with region merge/split, so the retained `regions`
map is the growth vector, not just the queue. This is the named OOM risk with no upper bound in the
code. It is likely benign in practice (the tryLock usually wins), but "usually" is not a bound.
Give `pruneRequests` a cap with a drop-oldest policy, or drain it from a path that is guaranteed to
run.

**MAJOR-3 — `transfer`'s single `tryLock` turns ordinary lock contention into a plan deferral on
every region rebind.** `:877`.

r14 correctly removed the timed wait, and `transfer` correctly returns `false` rather than blocking.
But per §3.7 the broker rebinds unowned candidates through `transfer` **at every drain**, and
`accountingLock` is taken blocking by every `acquire` and every control-path settlement command. A
`false` here is indistinguishable to the caller from "the target region cannot admit it" and forces
the plan to defer or fail before mutation. Under load this converts a transient microsecond of lock
contention into user-visible operation churn.

This is a consequence of the ruling, not a violation of it — but task 13 currently exposes no way for
task 14 to distinguish "busy, retry immediately" from "target saturated, back off", and the SPI has
only one `false`. The same conflation applies when another permit-lifecycle operation holds the
permit-local owner CAS (`claimPermitOperation` returns `false` at `:873`). Task 14 needs a documented
retry contract, or `transfer` needs a bounded lock-free retry (e.g. a fixed small number of `tryLock`
attempts, which is still non-waiting).

Test #20 currently *bakes in* the refusal (`assertFalse(transfer.get(...))`, `:782`), so this
behaviour is frozen by the suite as intended.

**MAJOR-4 — the `AdmissionRejectionHandler` hook fires indistinguishably for `cancel` and `reject`
outcomes; wiring it to terminalization in task 14 would re-open ruling 1.** `:1275-1290`.

`observeRejection` attaches to `waiter.delivery.future()` and invokes
`rejectionHandler.rejected(region, demand, reason)` on **any** exceptional completion, including a
`CancellationException` published by `producer.cancel`. The mandate for task 13 is "expose the hook,
do not wire it here", and task 14 wires it to task 11's `NOT_ACCEPTED` sink. If task 14 does the
obvious thing, a player `//cancel` will terminalize the plan `NOT_ACCEPTED` through the hook — burning
a plan sequence that ruling 1 explicitly preserves, and doing it *behind* the outcome CAS where the
CAS cannot arbitrate.

The hook signature carries no outcome kind; the only discriminator is
`reason instanceof CancellationException`, which is fragile (a caller-supplied cancellation stage may
complete with any throwable — `:1280-1283` unwraps `CompletionException` but does not normalize the
kind). Either add an explicit outcome-kind parameter, or document in the task-14 handoff that the
handler MUST ignore `CancellationException` and MUST NOT terminalize. As written, the handoff section
of the corrective-2 record says nothing about this.

Confirmed non-issue on threading: the hook runs on an isolated notification task, never the control
thread or a region thread (`CompletionServiceStage:239-241` routes `whenComplete` to
`notificationExecutor`).

### MINOR

**MINOR-1 — `acquire`/`acquireContinuation` throw synchronously from a `CompletionStage`-returning
frozen SPI method.** `:316-322` throws `RejectedExecutionException` after
`registeredPlan.rejectAdmission(refusal)`; `:1569-1576` and `validateDemand` throw
`IllegalArgumentException`. §3.4 freezes the return type as `CompletionStage<Permit>`; a caller
writing `bp.acquire(...).exceptionally(...)` will not catch these. The plan is already terminalized
so nothing is lost, but the behaviour is undocumented on the frozen SPI. Test #3 asserts the throw,
so it is intentional — it just needs to be in the §3.4 contract text.

**MINOR-2 — `tryAcquire` throws `IllegalArgumentException` from the tick-thread fast path.**
`:286` → `deadlineStatus` `:1142-1144`, when `deadlineNanos - now > maxAdmissionWaitNanos`. G-A1 uses
`tryAcquire` exclusively from a region thread; a fail-fast throw there propagates into tick execution.
Returning `Optional.empty()` for an out-of-contract horizon would be safer, with validation kept on
the async path where it can be terminalized. Test #24 (`:891-894`) pins the current behaviour.

**MINOR-3 — `Pressure.finalizerChains` reports `activeFinalizers`, not the capped
`reserved*Finalizers`.** `:228`. The per-region finalizer cap is enforced on
`reservedRegularFinalizers`/`reservedContinuationFinalizers` (`:1045`, `:1064`), but the diagnostic
reports `activeFinalizers`, which only becomes non-zero at `FINALIZING` (`:935`). §3.4's
`FAWE_QUEUE` mapping uses `finalizerChains` for `inflight=` and gates it against
`maxInFlightPerRegion` (perf-06), so the operator-visible signal does not track the resource the gate
actually enforces. Reserved-but-not-yet-finalizing work is invisible.

**MINOR-4 — production code carries a test seam.** `beforeWaiterDeliverySubmission` (`:83`, `:183-186`,
`:679-683`) exists solely so tests #10 and #16 can stall the control thread between grant settlement
and delivery submission. It is `NOOP` in the production constructors, but it means those two tests
exercise a stall point that does not exist in production, and it is one more thing that can throw
inside `serviceWaiters` (see MAJOR-1).

**MINOR-5 — unreachable-but-wrong branch would double-decrement waiter accounting.** `:714-727`.
For a waiter found in the queue set in state `REJECTED`, the code calls `decrementWaiters` at `:716`
and then adds it to `deliveries` at `:723`. Every path that sets `REJECTED` for a queued waiter
already dequeued and decremented it (`removeQueued` `:1242-1248`, `settleFailureLocked` `:510-516`,
`settleExpiry` `:439-446`), so the state is unreachable — but if it ever became reachable,
`globalWaiters` and `state.regularWaiters` would go negative and the region would never be evicted.
Either assert or handle without decrementing.

**MINOR-6 — narrow lost wakeup when both wake candidates' producers are momentarily closed.**
`:807-830`. `submitWaiterService` gives up after 2 attempts and resets `waiterServiceScheduled`, which
correctly avoids a permanent wedge — I checked that specifically, and the flag is reset on every
failure path. But the freed capacity is then only reapplied on the *next* capacity event; if none
arrives, queued waiters that could be admitted sit until their deadline and are rejected. Bounded by
`maxAdmissionWaitNanos` (30 s default) and by the wake candidates being stale volatile snapshots
(`:776-779`, refreshed under lock at `:787-790`), so it is a spurious-rejection window, not a hang.

**MINOR-7 — §3.6c item 6 wording vs. implementation.** Item 6 says drain-deadline expiry "supersedes
a granted-but-undelivered permit with **cancellation**". `flushExpired` (`:471-486`) settles with a
`TimeoutException` and core then publishes via `queueRejection` → `reject`, terminalizing the plan.
That matches r14's classification (lifecycle/deadline → `reject`) and matches this review's mandate,
so the implementation is right and the §3.6c item-6 sentence is stale. Worth correcting in the
architecture text so a future reader does not "fix" it.

**MINOR-8 — plain non-volatile permit flags rely on an undocumented fence.** `readyCapacityHeld`,
`globalReadyHeld`, `globalFinalizersHeld` (`:1481-1483`) are plain `boolean`s written and read from
different region threads across a merge/split. Correctness is real — the `permit.operation`
`AtomicReference` CAS/`set` in `claimPermitOperation`/`finishPermitOperation` provides the
release/acquire edge, and the pre-delivery writes are all under `accountingLock` — but nothing in the
code says so. One comment on `BackpressurePermit` naming the fence would keep the next editor from
breaking it.

---

## Test-quality verdict, test by test

25 tests. For each: can it actually fail on the property its name asserts, and what would have to
break for it to go red?

| # | Test | Can fail? | Notes |
|---|---|---|---|
| 1 | `exposesInitialCertificationLimits` (`:89`) | **Yes** | Pure constant assertion; red if a certification value drifts. Low value, honest. |
| 2 | `asyncAdmissionUsesBoundRegisteredProducer` (`:102`) | **NO — cannot fail on its named property** | It asserts the consumer callback runs off the test thread. But `await(stage)` completes the stage *before* `whenComplete` is attached, and `CompletionServiceStage.whenComplete` unconditionally routes to `notificationExecutor` (`CompletionServiceStage:239-241`), which always starts a fresh virtual thread. The assertion is guaranteed by core's stage wrapper regardless of anything `DefaultFoliaBackpressure` does. It would stay green against a task-13 implementation that completed futures inline everywhere. **Would pass against a deliberately broken implementation.** |
| 3 | `flushingRejectsBeforeWaiterAccounting` (`:126`) | **Yes** | Red if any accounting ran before the producer preflight — `pressure(region).waiters()` would be 1 (`:140`). Deterministic: `flush()` sets `FLUSHING` synchronously under `lifecycleLock`. Good test for §3.6c clause 2. |
| 4 | `tryAcquireNeverTouchesCompletionLifecycleLock` (`:150`) | **Yes** | Real contention: a platform thread holds core's actual `lifecycleLock` (via reflection) for 3 s. If `tryAcquire` took it, `elapsed < 200 ms` (`:182`) goes red. Also checks the admission-producer count is unchanged. **Gap:** nothing holds `accountingLock` from another thread while calling `tryAcquire`, so the `tryLock` fast-miss is not directly proven here (it is proven for the permit paths by #20). |
| 5 | `continuationsConsumeRegionalAndGlobalByteCapacity` (`:191`) | **Yes — strong** | Regional bytes 128, global bytes 160; first continuation takes 100, second asks 80 → must block on the **global byte** cap only. `assertEquals(1, pressure(second).waiters())` (`:210`) is synchronous state and goes red immediately if continuations bypass byte accounting. This is the test that binds prior BLOCKING #2. |
| 6 | `globalReadyCapsBindAcrossRegions` (`:220`) | **Yes** | Regional bytes 1024 (non-binding), global bytes 150; 100 + 60 > 150 → second `tryAcquire` must be empty. Binds the global byte cap in isolation. |
| 7 | `continuationsMayUseMoreThanOneRegionsGlobalCarve` (`:238`) | **Weak — does not bind its named property** | `maxGlobalFinalizers=6`, carve=1 → `regularGlobalFinalizerLimit=5`. Five continuations of 1 chain each fit under **both** hypotheses (`fits(4,1,5)` is true). It would stay green if continuations were wrongly subjected to the regular sub-cap. It needs a **sixth** continuation to discriminate. |
| 8 | `globalWaitersReserveContinuationSlots` (`:255`) | **Yes — strong** | Tuning `maxGlobalWaiters=4`, `maxGlobalFinalizers=3` → `regularGlobalWaiterLimit=1`. The second regular waiter must be rejected while a continuation waiter still gets a slot. Red if the global cap were checked without the kind partition — which is precisely the prior finding. Deterministic. |
| 9 | `cancellationHopNeverWaitsForAccounting` (`:312`) | **Yes — strong** | A platform thread holds the real `accountingLock` for 3 s; the cancellation stage is completed from a separate executor and must return within 200 ms (`:343`). Red if the inline hop takes the lock. This directly falsifies prior BLOCKING #5. |
| 10 | `concurrentCancelSupersedesGrantedUndeliveredPermit` (`:355`) | **Partially — does not bind cancel-vs-reject** | It genuinely proves (a) the hop does not block (`:385-386`), (b) the grant was settled and charged (`:383`, readyBytes 128), and (c) accounting returns to 0 (`:390`). But `assertThrows(CancellationException.class, ...)` (`:389`) would **also** pass if the implementation called `producer.reject(cancellationFailure)` instead of `producer.cancel` — the published cause is identical. The ruling-1 clause this test is cited for is actually bound by #12 and #14, not here. |
| 11 | `admissionProducerThreeOutcomeRaceHasOneMatchingWinner` (`:396`) | **Yes, but it tests core, not task 13** | Real 3-thread barrier race on a raw `AdmissionProducer`; asserts exactly one winner and that the published outcome matches the winner. Legitimate, but it exercises `OperationCompletionService`, so it is not task-13 coverage. Sub-nit: the `transitionFailure` guard throws `AssertionError`, which `Producer.transitionFailed` swallows into `addSuppressed` (`:898-903`) — that guard is inert. |
| 12 | `cancelledAttemptCanRetryOnSamePlanSequence` (`:455`) | **Yes — this is the ruling-1 test** | If `cancel` terminalized the plan, the retry's `tryAcquireAdmissionProducer` would throw at `acceptsAdmissionLocked()` and `await` would go red. Deterministic (the waiter is definitely QUEUED behind the holder). |
| 13 | `lifecycleRejectionTerminalizesPlan` (`:484`) | **Yes** | Asserts `terminal(plan, NO_CHANGE)` returns `false`, i.e. the plan was already terminalized by `reject`. Red if `reject` stopped terminalizing. Binds the other half of ruling 1. |
| 14 | `cancelDeliverRejectRaceProducesOneCoherentOutcome` (`:507`) | **Yes — strong** | Genuine 3-thread race (cancel / free-capacity / stopAccepting) with a per-branch plan-consequence assertion: permit or cancellation ⇒ plan still terminalizable (`assertTrue`), rejection ⇒ already terminalized (`assertFalse`), plus zero residual accounting. Red if `cancel` burned the plan. Caveat: which branch fires varies per run, so single-run coverage is partial — mitigated only by repeated runs. |
| 15 | `deadlineExpiresWhileCommonPoolIsSaturated` (`:555`) | **NO — cannot fail on its named property** | `stage.toCompletableFuture().orTimeout(1, SECONDS).join()` (`:592`) followed by `assertInstanceOf(TimeoutException.class, failure.getCause())` (`:594`). `CompletableFuture.orTimeout` completes with **`java.util.concurrent.TimeoutException`** — the exact type the assertion accepts, and the same type the implementation's `deadlineFailure` uses. If the admission deadline never fired at all, `orTimeout` would fire at 1 s and the assertion would still pass. Secondary weakness: `CompletableFuture.runAsync` × parallelism does not reliably saturate the common pool (compensation threads). **This is the sole named acceptance test for previously-BLOCKING #4 and it would pass against an implementation with no deadline mechanism whatsoever.** Fix: assert on the exception message/identity, or assert elapsed < 500 ms. |
| 16 | `flushExpirySettlesEveryGrantedUndeliveredWaiter` (`:603`) | **Yes — strong** | Three waiters granted-but-undelivered behind a stalled control thread, then flush + expiry. Asserts all three settle exceptionally, flush and completion both quiesce, `readyBytes == 0`, and `trackedRegionCount() == 0` (`:646`). The eviction assertion makes it a genuine accounting-conservation test — a single stranded waiter would keep the region alive and hang the flush. **But** it does not inject a throw, so BLOCKING-#1's per-delivery isolation is still untested (see gaps). |
| 17 | `stageMachineAndCloseAreConservative` (`:655`) | **Yes** | Ordered transitions, ready capacity released exactly at FINALIZING, idempotent double `close()`, invalid transition throws and releases. Deterministic. |
| 18 | `continuationCapacityIsSubtractive` (`:685`) | **Yes** | `maxFinalizersPerRegion=4`, carve=1 → 3 regular admissions then a mandatory miss, then a continuation succeeds. Red if the carve were additive. Chunk/byte limits are set wide so they do not shadow. |
| 19 | `transferRefusesWithoutMovingAccounting` (`:711`) | **Yes** | Refusal leaves `region()` and both regions' byte accounting untouched; after the blocker closes, transfer succeeds and accounting moves. Deterministic. Binds "never mutates under stale accounting". |
| 20 | `permitTransitionsNeverWaitForAccountingLock` (`:737`) | **Yes — this is the no-wait proof** | A platform thread holds the real `accountingLock` for 3 s while three separate threads run `transfer`, `enter(FINALIZING)` and `close`; each must return within 200 ms (`:782-784`), and the released accounting must be visible (`:785-786`). Red the moment any of the three takes `accountingLock.lock()`. **The no-wait property is proven by test, not merely asserted in prose.** |
| 21 | `concurrentCloseAndTransferReleaseExactlyOnce` (`:797`) | **Yes — strong** | 100 iterations of a latch-synchronised close/transfer race, then a *global*-cap probe: one more permit must fit and a second must not. A double-release makes the second probe succeed (red); a missed release makes the first `orElseThrow` fail (red). This is the best test in the file. |
| 22 | `releasedPermitWakesWaiterThroughWaiterProducer` (`:839`) | **Yes** | Explicitly waits for the granting producer to be released (`:848`) before queuing the waiter and closing the permit; red (3 s timeout) if the wake path needed the released producer. Binds ruling 2's "B's own producer" clause. |
| 23 | `telemetryDoesNotDropContendedSamples` (`:863`) | **NO — cannot fail on its named property** | It acquires `accountingLock` **on the test thread itself** (`:872`) and then records telemetry on that same thread. `ReentrantLock` is reentrant, so a `tryLock`-and-drop implementation — the exact prior defect — would acquire successfully and the samples would land. The test stays green against the broken version. Compare #9 and #20, which correctly park the lock on a *separate* thread. Fix: hold the lock from another thread. |
| 24 | `deadlineValidationRejectsUnboundedHorizons` (`:887`) | **Yes** | `Long.MAX_VALUE` horizon ⇒ IAE; already-expired ⇒ empty. Deterministic. (Also pins MINOR-2's throw-from-tick-path behaviour.) |
| 25 | `stopSettlesMultipleProducerBackedBatches` (`:902`) | **Yes** | Batch size 2, five waiters; red if the rotation stopped after one batch (`awaitCondition(rejections == 5)`, `:942`). Also verifies the rejection hook fires per attempt. |

**Summary: 3 of 25 cannot fail on the property they name (#2, #15, #23); 2 more do not bind the
property they are cited for (#7, #10); 1 tests the collaborator rather than the subject (#11).**
The remaining 19 are real, several of them (#5, #8, #9, #14, #16, #20, #21, #22) are genuinely
adversarial with real contention and would go red on the defect they target.

**Is the no-wait property proven by a test or only asserted in prose?** **Proven** — test #20 holds
the real `accountingLock` from a foreign thread and bounds all three lifecycle transitions at 200 ms.
This is a real improvement over the prior rounds. The exactly-once half is proven independently by
test #21.

**Coverage gaps that matter:**

1. **No test injects a throw into delivery submission.** BLOCKING #1's fix is structurally present
   (`:531-547`) but has zero test coverage — nothing exercises a failing `producer.submit`, and
   nothing at all covers MAJOR-1's collect-then-submit window. A test that makes the second of three
   deliveries throw and then asserts the first and third still settle with zero residual accounting
   would close both.
2. **No `accountingLock`-contended `tryAcquire` test.** #4 covers `lifecycleLock` only.
3. **No re-derived-`RegionKey` state-reuse test.** See dev-record accuracy below — this is now
   writable and no longer blocked.

---

## Dev-record accuracy

Broadly accurate on the technical claims; **materially stale on its headline escalation.**

**Verified true:**
- "removed the former 100 µs transfer-wait slot … any timed transfer wait is now prohibited" — `:877`
  is a bare `tryLock()`. ✓
- "`settleCancellation` is the sole caller-initiated cancellation settlement … the only call site
  moved from `reject` to `cancel`" — audited every call site; exactly one `producer.cancel` at `:428`. ✓
- "releases the permit at line 418, unlocks, then calls `producer.cancel` at line 428" — line numbers
  are exact. ✓
- "A permit stores no admission producer" — confirmed, `BackpressurePermit` `:1472-1496`. ✓
- "`enter(FINALIZING)` … with atomic operations and no `accountingLock`" — confirmed by the exhaustive
  `.lock()` site list. ✓
- "the outcome-publication gap deliberately relies on the frozen completion service's serialized
  producer/control commands" — I verified this against `runQueued`/`serializationLock`/`drainOne`
  rather than accepting it, and it holds. This is the record's most load-bearing claim and it is true.
- "`tryAcquire` calls no completion-service method, allocates no admission stage, starts no thread"
  (fresh-impl record, item 3) — confirmed by inspection and grep. ✓

**Stale / wrong:**
- **The corrective-2 Escalation states the only remaining dependency is "the Task-14-owned `RegionKey`
  value identity", and the Attack points say "The test suite still cannot discharge `RegionKey`
  re-derivation until Task 14 supplies value identity. Treat any identity-equality implementation as a
  hard integration failure."** `RegionKey.java` in the current tree (and `git status` shows it as
  modified) is already an immutable value key with `equals`/`hashCode` over `(worldId,
  observedRegionId)` (`RegionKey.java:44-54`). The precondition is **satisfied**. The escalation as
  written would send a reader looking for work that is already done, and — more importantly — the
  mandatory re-derived-key state-reuse test that the record defers is **now writable today**: the
  package-private `RegionKey()` synthetic constructor is still what every test uses (25 call sites),
  so the property remains untested purely by omission. Per the review brief I am not faulting task 13
  for `RegionKey` itself; I am recording that the record's status is out of date and the deferred test
  is no longer blocked.
- Corrective-2 does not mention the `AdmissionRejectionHandler`/`cancel` interaction anywhere in its
  Task-14 handoff, despite that handoff being the mechanism by which MAJOR-4 would fire.
- The record's citation of test line 350 ("the three-thread test at line 350 races cancellation after
  grant settlement but before delivery-command submission") maps to test #10, which as shown above
  does not actually discriminate `cancel` from `reject`. The claim is over-stated; the property is
  really carried by tests #12 and #14, which the record also cites.

---

## What I verified and could NOT fault

- **All five prior BLOCKINGs.** I reconstructed each failure and each reproduction fails. In
  particular I confirmed `deliverAll`'s isolation covers every throw source in the loop, and that no
  throw can reach a region thread via `close()`/`transfer()`'s `finally`.
- **Caps cannot be breached.** Every gated dimension is incremented only under `accountingLock`; the
  lock-free region-thread paths only decrement. A concurrent partial release is read as an
  *under*-estimate of free capacity, so admission is conservative in the safe direction.
- **Accounting conservation.** I traced grant → SCHEDULED → COMMITTING → FINALIZING → close; grant →
  close from every stage; transfer at READY and at FINALIZING; invalid-transition release; cancellation
  of a granted-undelivered permit; expiry; flush expiry; lost delivery; `stopAccepting`. Every path
  balances, including bytes and including the `readySince` multiset. I could not construct a leak, a
  double-release, or a region left with non-zero counters and nothing outstanding.
- **`close`/`transfer`/`enter` exactly-once under race.** Both orderings of the
  `closeRequested`/owner-CAS handshake converge on exactly one `release`, and the post-`IDLE` recheck
  at `:972-975` genuinely closes the window the record claims it does.
- **The `deliverSettled` publication gap.** I verified against the core — not the comment — that
  every waiter-state settlement path except `settleProducerFailure`'s direct call and
  `settleLostDelivery` runs on the single control thread under `serializationLock`, and that neither
  exception can be in flight during the gap. `deliver`-then-`cancel` and `cancel`-then-`deliver` both
  produce a coherent single outcome with accounting released exactly once.
- **Lock ordering.** `accountingLock` is never held across a completion-service call; the control
  thread takes `serializationLock` → `accountingLock` and nothing takes the reverse. No deadlock.
- **Waiter-service scheduling flag.** I specifically hunted for a permanent wedge of
  `waiterServiceScheduled` (a stranded `serviceWaiters` command leaving the flag set forever) and
  could not construct one: FIFO within a producer's command chain guarantees a submitted
  `waiterServiceCommand` drains before that producer's terminal command, and every failure path in
  `submitWaiterService` resets the flag. Only the narrow MINOR-6 window survives.
- **`tryAcquire`'s allocation and blocking claims**, including on misses, verified by reading every
  callee down to `PlanProducerToken.operationId()`.
- **Deadline scheduling** goes through the lifecycle-fenced `retryTimer` only; the timer thread does
  one CAS and one lock-free append. Verified by grep (no `commonPool`, no `delayedExecutor`, no
  `Executors`, no `Thread`) and by reading `AdmissionProducer.schedule`.
- **Continuation carve-out is subtractive** in all four dimensions (regional finalizers, regional
  waiters, global finalizers, global waiters), and the global waiter cap is now partitioned so regular
  work provably cannot exclude continuations.
- **Every `AdmissionProducer` reaches exactly one published outcome** on every path I enumerated,
  including `arm`-returned-false and the early returns in `acquire` — so completion flush can always
  quiesce, *except* under MAJOR-1's hypothetical throw.

### What breaks if task 14 gets `RegionKey` wrong

If task 14 ships identity equality, or a hash that is not stable across merge/split re-derivation:
`regions` is a `ConcurrentHashMap` keyed by `RegionKey` (`:87`). A re-derived key for the same
physical region misses the existing `RegionState`, so (a) per-region caps stop binding — the new state
starts at zero while the old charges are still outstanding, silently doubling the effective per-region
budget on every rebind; (b) `requiredState` (`:1177-1183`) throws `IllegalStateException` from
`release`/`enter`/`transfer` — i.e. **on a region thread**, from `close()`'s `finally`, leaving
`permit.operation` stuck `ACTIVE` and the accounting permanently leaked; (c) `pruneIfIdle` never
matches, so the map grows one entry per rebind. Global caps would still hold, so this degrades into
gradual global saturation rather than immediate corruption — which makes it hard to diagnose. The
requirement is correctly documented at `DefaultFoliaBackpressure.java:58-59`. The current in-tree
`RegionKey` satisfies it; the missing piece is only the test.
