# Independent adversarial review — Task 11, correctives 3 / 4 / 5

Reviewer: fresh, no prior context on this task. Read-only on source; no Gradle run.
Scope: `worldedit-core/.../util/task/{OperationCompletionService,DefaultOperationCompletion,
SameChunkPlanSequencer,CompletionServiceStage,CompletionServiceFuture}.java` + tests, against
`architecture.md` §1b, §3.6, §3.6b, §3.6c(1-8), §3.6d (incl. the r14 Q1 three-outcome CAS block).

Note on method: the task-11 production and test files are **untracked** in git (`?? …`), so no
diff against a pre-corrective-3 baseline was possible. Every claim below is derived from reading
the delivered state and constructing the interleavings by hand.

---

## VERDICT

**PASS-WITH-NOTES**

Both corrective-3 BLOCKINGs are closed in the delivered state. §3.6d conforms clause by clause,
including the lock discipline — which I verified literally, and which the suite pins with a real
reflection-driven probe. The three-outcome CAS is genuinely mutually exclusive and cannot lose
all three. No BLOCKING found.

The notes that keep this off a clean PASS are, in order of weight: one named regression test
(`closeAdmissionAfterCompletedFlushDoesNotStrandOrRepublishResult`) **cannot fail on its named
property**; the §3.6d "TERMINATED with a live token is an invariant failure" guard is again
**unreachable dead code** (the pattern the brief warned about); and the `maybeComplete` rollback
that corrective 3 delivered is defensive rather than load-bearing — the defect is actually closed
by a *different* mechanism than the dev record describes, and if the rollback path were ever
reached through `requestCompletionCheck` it would strand silently exactly as before.

---

## Disposition of the two corrective-3 BLOCKINGs

### BLOCKING 1 — `SameChunkPlanSequencer.Registration.ready()` escaping isolation

**CLOSED.** Verified structurally and by test.

- `SameChunkPlanSequencer.java:154` — `this.readyView = completionService.isolateStage(ready);`
- `SameChunkPlanSequencer.java:167-169` — `ready()` returns `readyView`, not the raw future and
  not `minimalCompletionStage()`.
- `SameChunkPlanSequencer.java:92` — publication goes through
  `producer.execute(() -> completionService.publishOutcome(registration.ready, null))`, i.e. the
  raw `ready` is completed on an isolated notification task
  (`OperationCompletionService.java:354-373` → `startPublication` → `notificationExecutor`), never
  synchronously on the control thread.

I checked `CompletionServiceStage`/`CompletionServiceFuture` for holes rather than trusting the
wrapper: every `thenX`/`whenComplete`/`handle`/`exceptionally`/`compose` overload routes to the
`…Async` form with either `notificationExecutor` or `isolatedContinuationExecutor(requested)`;
`toCompletableFuture()` (`CompletionServiceStage.java:296-307`) hands back a
`CompletionServiceFuture` whose `defaultExecutor()`/`newIncompleteFuture()` keep the isolation
inductive; `minimalCompletionStage()` re-wraps. **No new escaping stage was introduced by
correctives 4/5** — I checked every public accessor added since:
`AdmissionDeliveryLease.future()` (`OperationCompletionService.java:1441,1447`) returns
`isolateStage(future)`; `PlanProducerToken` exposes no stage at all; `flushView`
(`:108`) and `DefaultOperationCompletion.resultView` (`:63`) are both isolated; `enqueue`
(`:444`) and `runInline` (`:504`) return `isolateStage(submitted)`.

Both halves of the original defect are covered by tests that genuinely fail against the pre-fix
shape — see the test section.

**Regression check:** `AdmissionProducer.publishOutcome` (`:1384`) publishes to
`delivery.future` — the *raw* `CompletableFuture`, not the view. That is correct: consumers only
ever hold `futureView`, so completing the raw future merely schedules another notification hop and
cannot run a consumer continuation inline on the control thread. No regression.

### BLOCKING 2 — `closeAdmission()` after service TERMINATED strands the coordinator

**CLOSED — but not by the mechanism the dev record describes.**

The delivered `maybeComplete` (`DefaultOperationCompletion.java:390-408`) does pass the terminal
transition as a callback and does roll back:

```java
state = State.COMPLETING;
OperationResult operationResult = buildResult();
State terminalState = switch (…) { … };
try {
    completionService.publishOutcome(result, operationResult, () -> state = terminalState);
} catch (Throwable failure) {
    state = State.OPEN;
    throw failure;
}
```

and `publishOutcome` (`OperationCompletionService.java:354-373`) checks `TERMINATED` **before**
reserving the publication and before running `publicationReserved`, so the state transition never
happens when publication is impossible.

**The rollback is safe under concurrency.** `state` is guarded by the coordinator monitor: every
`maybeComplete` caller holds `synchronized(this)` (`:193, :211, :241, :253, :268, :367, :384`),
and the rollback executes inside the same critical section. `state != State.OPEN` guards
re-entry, so no double-publish. `buildResult()` is a pure read. Verified.

**But the rollback is not what closes the defect, and it is unreachable in the current wiring.**
`closeAdmission()` cannot reach `maybeComplete` post-TERMINATED at all:

- `DefaultOperationCompletion.java:111-118` — `closeAdmission()` returns early when
  `!registrationOpen`, before touching `admissionProducer.tryComplete`.
- `DefaultOperationCompletion.java:340-346` — `admissionDrainExpired` (the admission producer's
  flush-expiry hook) sets `registrationOpen = false; admissionClosed = true;`.
- `OperationCompletionService.java:665-675` — `terminateIfQuiescent` requires
  `producers.isEmpty()`. The admission lifecycle producer (registered at construction,
  `DefaultOperationCompletion.java:64-68`) is released **only** by its own `tryComplete`
  (i.e. `closeAdmission` already ran) or by flush expiry (which sets `registrationOpen = false`).

Therefore, at the instant the service reaches `TERMINATED`, `registrationOpen` is already `false`
on every path, and `closeAdmission()` is a no-op. The defect is closed by the **admission
lifecycle producer + `registrationOpen` guard** (scope point 2), not by the callback/rollback.
I traced the same for the other `maybeComplete` drivers: all reach it through a live producer on
the control executor, pre-`TERMINATED`. So the `catch` branch at `:404-407` is currently
unreachable. This is defence in depth, which is fine — but the dev record and the review brief
both describe the rollback as *the* fix, and it is not.

**Residual (MINOR-3 below):** if the rollback path ever does become reachable, the strand returns
unchanged, because `requestCompletionCheck` discards the stage and `runInline` swallows into it.

**No other path strands or double-publishes.** I specifically checked:
- `outstandingTerminals` accounting: every decrement site is guarded by `plan.record == null`
  (`:214-220`, `:280-289`, `:316-325`), so no double-decrement and `outstandingTerminals == 0`
  ⟹ every registration has a non-null record ⟹ `buildResult()` cannot NPE.
- Drain expiry vs. persistence settlement racing to terminalize the same plan: mutually exclusive
  on the **same** `Producer.lifecycle` CAS — `requestFlushExpiryLocked` does `OPEN→EXPIRING`
  (`:616`), `tryComplete` does `OPEN→COMPLETING` (`:822`). Exactly one wins, so
  `persistenceSettled` can never overwrite a drain-frozen `UNAVAILABLE` record with a stale
  `DURABLE` one. Confirmed the persistence settlement is constructed with `plan.producer`
  (`:227`), i.e. the same Producer instance.
- `result` is a `CompletableFuture`; a second `complete` is a no-op, and the `state` guard
  prevents a second `publishOutcome` anyway.

---

## §3.6d conformance, clause by clause

| Clause | Verdict | Evidence |
|---|---|---|
| Two service acquisitions only; no independent `AdmissionDeliveryLease` acquisition | **CONFORMS** | Only `tryAcquirePlanProducer` (`:139`) and `tryAcquireAdmissionProducer` (`:177`) are public acquisitions. `AdmissionDeliveryLease` is a private field initializer on `AdmissionProducer` (`:1167`), reachable only via `delivery()` (`:1188`). No `deliverExceptionally`. |
| `PlanProducerToken` acquisition MAY take `lifecycleLock` | **CONFORMS** | `:153` |
| `ACCEPTING` registers producer + `ownerFlushExpiry` atomically | **CONFORMS** | `:155-168` — state check, key check, construction, `planProducers.put`, `producers.add` all inside one `lifecycleLock` section. The hook is installed in the `Producer` constructor (`:942-949`), so registration and terminalizer are inseparable. |
| `FLUSHING`/`TERMINATED` → empty **without side effects** | **CONFORMS** | `:155-157` returns before any allocation or map mutation. Pinned by `planAndAdmissionAcquisitionRejectAfterFlushWithoutSideEffects` (`OperationCompletionServiceTest.java:332`), which asserts `registeredProducerCount()` is unchanged and `outstandingAdmissionProducerCount() == 0`. |
| `register(token)` consumes exactly once; live token stays consumable during `FLUSHING` unless expiry won | **CONFORMS** | `consumeRegistration` (`:1031`) CASes `acquired → PlanBinding`; no service-state check, so `FLUSHING` is fine. `reserveFlushExpiry` (`:1046`) CASes `acquired → expired` first if expiry won. Pinned by `livePlanTokenRemainsConsumableDuringFlushing` (`…ServiceTest.java:138`). |
| `terminal` / `rejectAdmission` are O(1), nonblocking, take **no** `lifecycleLock` | **CONFORMS** | `:965-988` / `:991-1001` — `AtomicReference` CAS + `enqueueCollaborator` (`:447`: counter increment + `executor.execute`). No `lifecycleLock` anywhere on the path. Verified literally, and pinned by `collaboratorHotPathsDoNotAcquireLifecycleLock`. |
| Coordinator owns release after terminal + persistence settlement; task 14 never closes it | **CONFORMS** | `PlanProducerToken` exposes no close/release. Release happens via `plan.producer.tryComplete` at `DefaultOperationCompletion.java:235` (no settlement needed) or `:884` (after settlement). |
| `TERMINATED` with a live token/producer is an invariant failure, **enforced** | **CONFORMS in effect, guard is DEAD** | See MINOR-2. The invariant genuinely holds (a live token keeps a `Producer` in `producers`, which blocks `terminateIfQuiescent`), but the explicit guard at `:678-683` is unreachable. |
| `AdmissionProducer` requires an already-consumed, still-live token | **CONFORMS** | `:190` → `acceptsAdmissionLocked()` (`:1007-1011`) requires `planState instanceof PlanBinding` (consumed, not yet claimed/expired) **and** `producer.lifecycle == OPEN` **and** `producers.contains(producer)`. |
| Acquisition may take `lifecycleLock`, allocates the isolated stage, installs the trampoline, starts **NO** thread | **CONFORMS** | `:185-203`. `AdmissionDeliveryLease` field init calls `isolateStage` (`:1441`) — pure wrapping. The `Producer` is constructed with `this::reserveFlushExpiry` / `this::runFlushExpiry` (`:1178-1185`). No `Thread`/virtual-thread start. |
| On `FLUSHING`/`TERMINATED` returns empty; task 13 calls `registeredPlan.rejectAdmission` | **CONFORMS** | `:187-189`. |
| `arm` precedes accounting; trampoline guarantees no producer ever lacks a flush terminalizer | **CONFORMS — by construction, not convention** | This is the clause I attacked hardest. The `Producer` (with `runFlushExpiry`) is registered **at acquisition**, before `arm` is even callable. An unarmed producer at drain expiry: `reserveFlushExpiry` (`:1350`) CASes `null → expiredBeforeArm`; `runFlushExpiry` (`:1354-1367`) skips the absent waiter hook and calls `queueRejection(expiry, allowExpiredBeforeArm=true)`, which `appendCommand` honours at `:1300`. The attempt settles, the outcome publishes, the producer releases. `arm` then returns `false` (`:1203-1205`) so accounting never begins. **I could not find a window.** Pinned by `armReturnsFalseWhenFlushExpiryAlreadyWon` (`…ServiceTest.java:488`). |
| `schedule` legal only after `arm`; clamped to the published drain deadline; timer invokes only O(1) `linearizeExpiry` then submits the prebuilt settlement | **CONFORMS with a deviation** | `requireArmed()` (`:1230`), `timerScheduled` CAS (`:1234`), timer body `:1242-1250` is exactly `linearizeExpiry` → `submit`. Clamp at `:1239` — see MINOR-4: it is a one-shot clamp at schedule time, with no `onFlushing` re-clamp. |
| `submit`, `reject`, `cancel` and timer firing take **no** `lifecycleLock` | **CONFORMS** | `submit` (`:1216`) / `reject` (`:1263`) / `cancel` (`:1271`) all funnel to `appendCommand` (`:1292`) — atomics + `scheduleDrain` → `enqueueCollaborator`. Timer body likewise. Verified literally against the sibling-task failure mode; no global lock is taken. |
| Release only after one immutable outcome publishes; cancels retained timer and hook | **CONFORMS** | `releaseAfterPublicationLocked` (`:1418-1433`) is invoked only from `publicationFinished` (`:589`) / `publicationStartFailed` (`:555`), both under `lifecycleLock`, after `outcome.publishTo(target)`. Cancels `deadlineTask`, sets `armState = releasedArm`, removes from `admissionProducers`, releases the `Producer`. Idempotence enforced by the `released` CAS (`:1419`). |
| Producers usable through `FLUSHING`; none survive `TERMINATED` | **CONFORMS** | Nothing in `appendCommand` consults service state. Survival is blocked structurally (`producers.isEmpty()`). |
| `AdmissionDeliveryLease.future()` isolated, starts no thread; delivery O(1), nonblocking, valid during `FLUSHING`; release follows publication, never consumer continuation | **CONFORMS** | `:1441,1447`; `deliver` (`:1452`) → `producer.deliver` → `appendCommand`. `publicationFinished` runs in the publication command's `finally` around `outcome.publishTo(target)`, which completes the **raw** future — consumer continuations are separate notification tasks and are not awaited. |
| Non-SPI `register(PlanProducerToken)` overload; frozen `register(long,long)` unchanged | **CONFORMS** | `DefaultOperationCompletion.java:73-77` (frozen signature retained, now throws) and `:80-106`. See MINOR-5 on the identity validation being partly tautological. |

### Three-outcome CAS (r14 Q1)

**Can two of `deliver`/`reject`/`cancel` both win? No.** All three reach
`appendCommand(…, closesAttempt=true, …)`. The single linearization point is
`commandTail.compareAndSet(tail, closedMarker)` (`:1312-1313`). Once a closing entry wins, `tail`
is `closedMarker` permanently (`:1309` and the fast-path `:1293` both bail), and no later closing
entry can CAS from it. Exactly one `true`.

**Can all three lose, leaving the attempt unpublished and the producer never released? No.** The
only way to reach `commandTail == closedMarker` without a published outcome is `closeCommands()`
(`:1401-1407`), which is called **only** from `transitionFailed` (`:1389`), which immediately
proceeds to `rejectOnControl(failure)` → `publishOutcome` (`:1398`). So every close is paired with
a publication, and every publication releases the producer. I also confirmed no publication path
bypasses a closed tail: `publishOutcome` is reached from `deliver`/`cancel`'s commands, from
`rejectOnControl` (via `queueRejection`, closing), and from `transitionFailed` (which closes
first). Flush stalling is therefore not reachable through a lost CAS.

**Does `cancel` truly leave the plan usable for retry?** Yes. `cancel` (`:1271-1278`) submits
`publishOutcome(new StageOutcome<>(null, cause))` and **never touches `registeredPlan`** —
contrast `reject` → `queueRejection` → `rejectOnControl` (`:1369-1378`) which calls
`registeredPlan.rejectAdmission(cause)` before publishing. So after `cancel`, `planState` is still
`PlanBinding`, `acceptsAdmissionLocked()` still returns true, and a fresh
`tryAcquireAdmissionProducer(newAttemptId, token)` succeeds. Directly asserted by
`cancellationLeavesPlanSequenceAvailableForAnotherAdmissionAttempt`.

**Does `reject` truly terminalize?** Yes — `rejectOnControl` sets the `deferredPlanClaim`
ThreadLocal so `terminal()` may claim through `PlanExpiring` (`:972-974`), calls
`rejectAdmission` (→ `NOT_ACCEPTED` + empty receipt + cause), then `processCurrentClaim()`, and
**only then** publishes the exceptional outcome — matching the §3.6d ordering requirement
"(2) plan rejection, (3) the same serialized command publishes the exceptional outcome". Pinned
by `waiterRejectionTerminalizesPlanBeforeExceptionalPublication`.

**Plan/waiter dedup stays distinct** (plan on `(operationId, chunkKey, planSequence)`, delivery on
`admissionAttemptId`), and a concurrent plan-level drain terminalizer competing on the plan key
resolves to one winner with the other a non-decrementing duplicate — pinned by
`planDrainAndWaiterRejectionCompeteForOneTerminalTransition` (`…ServiceTest.java:449`), which
asserts both calls return `true` (each claimed *its own* CAS) while `outstandingTerminalCount()`
transitions exactly once and the plan record carries the plan-drain cause.

### Exactly-once / no-silent-loss / §1b

- **`SUCCEEDED` under failed persistence: not reachable.** `buildResult` (`:410-425`) sets
  `failed` if `failures` is non-empty **or** any record `isFailed`. `isFailed` (`:427-437`) treats
  `COMMITTED` with `historySettlement == UNAVAILABLE` as failed, and both
  `recordUnavailableSettlement` (`:459-464`) and `persistenceSettled` (`:262-264`) push a
  `HistoryUnavailableException` into `failures`. Two independent gates. Matches §3.6b r4
  amendment 4 (status separation: `TerminalStatus` untouched by persistence failure,
  `OperationResult` PARTIAL/FAILED).
- **No receipt exposed while pending:** `maybeComplete` gates on `pendingPersistence != 0`.
- **§1b tick-thread rule** on the §3.6d surface: `terminal`, `rejectAdmission`, `submit`,
  `reject`, `cancel`, `deliver` are all lock-free w.r.t. `lifecycleLock`. The frozen
  `OperationCompletion.terminal(record)` does take the coordinator monitor
  (`DefaultOperationCompletion.java:125`), which is transitively behind `lifecycleLock` via
  `maybeComplete → publishOutcome`; but §3.6d directs task 14 to use `token.terminal(record)` for
  every world terminal, and that path is clean. See MINOR-7 for the one place I think §1b is
  genuinely brushed.

---

## Findings

### MAJOR-1 — `closeAdmissionAfterCompletedFlushDoesNotStrandOrRepublishResult` cannot fail on its named property

`DefaultOperationCompletionTest.java:737`

The test flushes to `TERMINATED`, awaits the result, then calls `completion.closeAdmission()` and
asserts the result is unchanged and published once.

By the time `flush` completes, `admissionDrainExpired` (`DefaultOperationCompletion.java:340-346`)
has already set `registrationOpen = false`. `closeAdmission()` (`:111-116`) therefore returns at
`if (!registrationOpen) return;` — it never reaches `admissionProducer.tryComplete`, never reaches
`closeAdmissionOnService`, never reaches `maybeComplete`, and never reaches `publishOutcome`.
**The entire mechanism the test is named after is skipped.**

Concretely: replace `maybeComplete`'s `catch { state = State.OPEN; throw failure; }` with a bare
`catch { /* swallow */ }`, or delete the `publicationReserved` callback and set
`state = terminalState` unconditionally before publishing — this test stays green. It is currently
an idempotency test for `closeAdmission` after drain expiry, which is worth having, but it is not
the corrective-3 BLOCKING-2 regression guard the dev record claims at scope point 2.

**What a real test would need:** drive `maybeComplete` post-`TERMINATED` on a path where
`registrationOpen` is still true — which, as analysed above, the production wiring makes
unreachable. That is the honest finding: **the invariant is structural, so the test should assert
the structural property** (e.g. that the service cannot reach `TERMINATED` while any coordinator
has `registrationOpen == true`), not simulate a reachable strand.

### MINOR-2 — §3.6d "TERMINATED with a live token is an invariant failure" guard is unreachable dead code

`OperationCompletionService.java:677-683`

```java
private void terminate() {
    if (!planProducers.isEmpty() || !admissionProducers.isEmpty()) {
        String diagnostic = "…outstanding producer collaborators…";
        assert false : diagnostic;
        throw new IllegalStateException(diagnostic);
    }
```

`terminate()` has exactly one caller, `terminateIfQuiescent` (`:665-675`), which already returns
early on `!producers.isEmpty()` and again on `!admissionProducers.isEmpty()`. And `producers`,
`planProducers` and `admissionProducers` are maintained in lockstep: the only additions are
`:167/:199` (both add to `producers` *and* the keyed map under `lifecycleLock`), and the only
removal is `releaseProducerLocked` (`:692-700`), which removes from all three together. Hence
`planProducers` non-empty ⟹ `producers` non-empty, and the guard's condition is false whenever it
is evaluated.

This is precisely the pattern flagged in the brief ("the advertised guards were unreachable dead
code — check for that pattern again"). To be fair: **the invariant itself genuinely holds** — a
live token keeps a `Producer` registered, which blocks termination — so this is a documentation /
false-assurance defect, not a correctness one. But the codebase now advertises an enforcement that
does nothing, and no test can distinguish it from a correct one.

### MINOR-3 — the `maybeComplete` rollback, if ever reached, still strands silently

`DefaultOperationCompletion.java:382-388, :303-310` and `OperationCompletionService.java:491-505`

```java
private void requestCompletionCheck() {
    completionService.submit(() -> {
        synchronized (DefaultOperationCompletion.this) { maybeComplete(); }
    });                      // ← returned CompletionStage discarded
}
```

`submit` at `TERMINATED` takes the inline path (`OperationCompletionService.java:232` →
`runInline`), which catches the throwable into `submitted` (`:497-503`) — a stage nobody consumes.
So a `maybeComplete` that throws here would roll `state` back to `OPEN`, with every gate
(`admissionClosed`, `finalizersSettled`, `pendingPersistence == 0`, `outstandingTerminals == 0`)
already satisfied and monotone, so nothing will ever re-drive it — the operation's `future()` never
completes, silently, forever. That is byte-for-byte the shape of the original BLOCKING 2.

**Currently unreachable**, because the three `requestCompletionCheck` callers (`planDrainExpired`,
`admissionDrainExpired`, `finalizerDrainExpired`) are all flush-expiry hooks running on the control
executor with `queuedTransitions >= 1`, so `terminateIfQuiescent` cannot fire underneath them. It
is reported because it is a live tripwire for future edits: the safety of the rollback depends
entirely on an invariant asserted nowhere. The narrow reachable variant is a `startPublication`
failure (notification thread creation fails, i.e. OOM-class), which would also land here.

Suggest either logging/escalating the discarded failure in `requestCompletionCheck`, or dropping
the rollback in favour of an explicit precondition assertion that documents why publication cannot
fail at that point.

### MINOR-4 — `AdmissionProducer.schedule` clamp is one-shot, not "clamped to the published drain deadline"

`OperationCompletionService.java:1237-1240`

```java
long effectiveDeadline = Math.min(requestedDeadline, producer.flushDeadlineNanos());
```

`producer.flushDeadlineNanos()` is `Long.MAX_VALUE` until `flush()` publishes it (`:908-910`).
Interleaving: task 13 schedules a 30 s admission deadline while `ACCEPTING`; `flush(2s)` runs a
second later. The already-scheduled timer is **not** re-clamped — contrast `SettlementDeadline`
(`DefaultOperationCompletion.java:638`), which registers `producer.onFlushing(this::reschedule)`
precisely to re-clamp on flush.

Impact is bounded: at drain expiry the flush-expiry hook fires `runFlushExpiry` →
`queueRejection(expiry, true)` and settles the attempt anyway, and `releaseAfterPublicationLocked`
cancels the stale `deadlineTask`. So no liveness or exactly-once consequence — only a literal
deviation from the §3.6d wording. Worth an `onFlushing` re-clamp for symmetry, or a comment
recording that the flush hook is the authoritative clamp.

### MINOR-5 — `consumeRegistration`'s identity validation is tautological from `register(token)`; the capability CAS is untested

`DefaultOperationCompletion.java:92-103` and `OperationCompletionService.java:1013-1035`

`register(token)` builds `key` **from the token** (`registrationKey(token.chunkKey(),
token.planSequence())`) and then passes `key.chunkKey()` / `key.planSequence()` into
`consumeRegistration`, which compares them to the token's own fields. Two of the three comparisons
at `:1021-1023` can never fail on this path; only the `operationId` check is real, and that is
already done at `DefaultOperationCompletion.java:86-88`.

Correspondingly, `planTokenRegistrationValidatesIdentityAndConsumesCapabilityOnce`
(`DefaultOperationCompletionTest.java:894`) does not test what its name says:
- `assertThrows(IllegalArgumentException, () -> wrongOperation.register(token))` hits the
  `operationId` check at `:86`, not `consumeRegistration`.
- `assertThrows(IllegalStateException, () -> completion.register(token))` hits
  `registrations.containsKey(key)` at `:93`, so the single-use CAS at
  `OperationCompletionService.java:1031` is **never exercised**. Break that CAS (make it a plain
  `planState.set(binding)`) and the test still passes.

The CAS *is* reachable in principle (two coordinator instances sharing an `operationId`), so it
is not dead — just uncovered.

### MINOR-6 — `AdmissionProducer.publishOutcome` calls a `…Locked` method without the lock

`OperationCompletionService.java:1380-1386` calls `reservePublicationLocked(...)` (`:521`) without
holding `lifecycleLock`, unlike every other caller (`:367`, `:384`, `:514`).

It is currently correct — the call always happens on the control thread inside
`runQueued`/`drainOne`, where `queuedTransitions >= 1`, so the `pendingOutcomePublications`
increment lands before the `queuedTransitions` decrement and `terminateIfQuiescent` can never
observe both at zero. But the correctness argument is non-local and the naming actively misleads.
Either take the lock or rename to reflect that the counter handoff, not the lock, is what makes it
safe.

### MINOR-7 — latent lock-order inversions around the coordinator monitor and the sequencer monitor

Two related observations, both currently unreachable but worth recording since §1b is binding:

1. **`serializationLock` → monitor vs. monitor → `serializationLock`.** `runQueued` (`:465`) holds
   `serializationLock` and runs commands that take the coordinator monitor. Conversely
   `planDrainExpired` calls `requestCompletionCheck()` **inside** `synchronized (this)`
   (`DefaultOperationCompletion.java:308`), and `submit` at `TERMINATED` takes
   `serializationLock` (`:494`). Reachable only if a coordinator monitor holder calls
   `requestCompletionCheck` post-`TERMINATED` from a thread other than the control thread — which
   the current wiring prevents (the caller is always the control thread, where `ReentrantLock` is
   reentrant). The global order today is `serializationLock < coordinator monitor <
   lifecycleLock`; nothing violates it. Flagged so a future edit does not.

2. **§1b and the sequencer's global monitor.** `SameChunkPlanSequencer.register` (`:38-52`) holds
   a single sequencer-wide monitor across **all** chunks while calling
   `completionService.registerProducer`, which takes `lifecycleLock`. `flush()`
   (`OperationCompletionService.java:247-270`) holds `lifecycleLock` for an O(producers) loop over
   every registered producer. A caller of `Registration.terminal()` — plausibly an owner/region
   thread — contends on that same sequencer monitor and can therefore wait transitively behind
   `flush()`'s O(n) section. Bounded and shutdown-only, and `terminal()` itself takes no
   `lifecycleLock` (`:78` → `tryComplete` → `enqueueCollaborator`), so this is not a §3.6d
   violation. But §1b says tick threads never block and never run unbounded work; on a large
   operation `n` is the plan count. Worth a disposition: either state that the sequencer is
   worker-only (architecture §1 step 2 implies allocation happens during prepare, on FAWE
   workers), or move the `registerProducer` call outside the sequencer monitor.

### MINOR-8 — `cancel` is absent from the lock-discipline probe

`collaboratorHotPathsDoNotAcquireLifecycleLock` (`OperationCompletionServiceTest.java:717`)
exercises `token.terminal`, `admission.submit`, `admission.reject`, `delivery.deliver` and
`admission.schedule` + timer firing under a held `lifecycleLock`, but not corrective 5's
`cancel(...)`. It shares `appendCommand` with `submit`/`reject`, so it is covered by construction —
but the probe is the only mechanical guard against the sibling-task failure mode, and the newest
entry point is the one not in it. One extra line.

---

## Test-quality verdict, test by test

Named in the dev records for correctives 3-5. For each: can it fail on its named property, and
what would have to break for it to go red?

| Test | Can it fail on its named property? | What turns it red |
|---|---|---|
| `closeAdmissionAfterCompletedFlushDoesNotStrandOrRepublishResult` (`DefaultOperationCompletionTest.java:737`) | **NO** — see MAJOR-1 | Nothing in the `maybeComplete`/`publishOutcome`/rollback machinery. `closeAdmission()` returns at the `registrationOpen` guard. Would go red only if drain expiry stopped closing admission, or if the result were republished — neither is the named property. **Passes against a deliberately broken rollback.** |
| `readyContinuationAttachedAfterTerminationIsStillIsolated` (`SameChunkPlanSequencerTest.java:121`) | **YES** | `assertNotEquals(attachingThread, callbackThread.get())`. If `ready()` returned the raw future or a plain `minimalCompletionStage()`, `thenRun` on an already-completed stage runs **inline on the attaching thread** and this fails deterministically. Exactly the pre-corrective-3 shape. The companion `assertFalse(onControl.get())` is weak (the executor is already shut down, so it could never be the control thread) but the load-bearing assertion is sound. |
| `blockedReadyConsumerCannotHoldCompletionSerialization` (`SameChunkPlanSequencerTest.java:91`) — the *other* half of BLOCKING 1, and the stronger of the pair | **YES** | Attaches a consumer that blocks forever, then asserts `service.submit(() -> {})` still completes. If the continuation ran on the control thread inside the serialization lock, the submit never completes and the 3 s `await` throws. Deterministic, no start-barrier hand-waving. Best test in the set. |
| `headCanTerminalBeforeAsynchronousReadyPublication` (`SameChunkPlanSequencerTest.java:69`) | **YES, on the primary property** | If `terminal()` rejected a head whose `ready` had not yet published, line 78 throws `IllegalStateException`. If the successor were not activated, `await(successor.ready())` times out. Both real. **Caveat:** the two `assertFalse(...isDone())` lines are weak — `toCompletableFuture()` interposes an async notification hop, so they can pass even against a synchronously-publishing implementation. The "asynchronous" half of the name is not actually pinned. |
| `armedPersistenceTimeoutCanExpireWhileBoundaryPrefixBlocks` (`DefaultOperationCompletionTest.java:800`) | **YES** | The persistence boundary blocks on `release.join()` forever. The test asserts the operation still resolves `PARTIAL` with `historySettlement == UNAVAILABLE`, flush completes, **and** `assertFalse(release.isDone())` — i.e. the result was reached with the prefix still blocked. If the settlement deadline were armed *after* the boundary hop (`PersistenceSettlement.start()` at `:779-782` does `deadline.start()` then `executeBoundary`), the deadline would never arm and both `await`s would time out. Genuinely falsifiable; the ordering it pins is the real one. Same structure as its sibling `blockingFinalizerPrefixCannotDelayDrainExpiry` (`:757`), which is equally sound. |
| `deliverRejectAndCancelRaceOnOneAttemptCas` (`OperationCompletionServiceTest.java:520`) | **YES** | The assertion is `assertEquals(1, delivered+rejected+cancelled)` — **not** union-safe; two winners fail it. Backed by three deterministic follow-ups (`assertFalse` on a duplicate `deliver`/`reject`/`cancel`) that catch a missing close regardless of how the race lands. The race itself is start-barrier + blocked control thread, so the interleaving is probabilistic, but unlike the failure modes this effort has hit before, **the assertion is genuinely falsifiable and the deterministic tail carries the test.** |
| `cancellationLeavesPlanSequenceAvailableForAnotherAdmissionAttempt` (`:577`) | **YES, deterministically** | Two independent red paths: `assertEquals(1, completion.outstandingTerminalCount())` fails if `cancel` terminalized the plan; and `tryAcquireAdmissionProducer(9, token)` throws `IllegalStateException` from `acceptsAdmissionLocked()` if `planState` is no longer a `PlanBinding`. This is the r14 Q1 plan-consequence clause, tested properly. |
| `rejectionTerminalizesAssociatedPlanNotAccepted` (`:618`) | **YES, deterministically** | `assertEquals(0, outstandingTerminalCount())`, then `NOT_ACCEPTED` status and `assertSame` on the cause in the published result. Fails if `reject` behaved like `cancel`. The correct mirror of the previous test. |
| `cancellationAfterDeliveryCannotDisturbGrantedOutcome` (`:643`) | **YES, deterministically** | The `outcomeEntryProbe` pauses the cancelling thread inside `appendCommand`'s closing branch; delivery then wins and **fully releases** (asserted via `outstandingAdmissionProducerCount() == 0`), so the resumed cancel observes `armState == releasedArm`. Without corrective 5's recheck at `:1301`, it throws `IllegalStateException("must be armed")` and `assertNull(cancellationFailure.get())` fails. Precisely targeted at the window corrective 5 closed. |
| `collaboratorHotPathsDoNotAcquireLifecycleLock` (`:717`) | **YES** | Holds `lifecycleLock` via reflection on a separate thread, then asserts the collaborator hot paths all complete within 2 s. Any `lifecycleLock` acquisition on those paths deadlocks the 2 s `callerDone.await`. This is the check that catches the sibling-task failure mode, and it is real. Gap: `cancel` is not in it (MINOR-8). |
| `waiterRejectionTerminalizesPlanBeforeExceptionalPublication` (`:389`) | **YES** | Uses an injected `notificationStarter` to snapshot `outstandingTerminalCount()` at the publication handoff and asserts it was already 0 — i.e. plan terminalization strictly precedes exceptional notification. Publishing before terminalization fails it. Good seam; pins §3.6d's waiter-rejection ordering rather than just its outcome. |
| `planDrainAndWaiterRejectionCompeteForOneTerminalTransition` (`:449`) | **YES** | Asserts *both* claim calls return `true` (each owns its own CAS) while the coordinator records exactly one terminal transition and the plan-drain cause wins. Would fail on a double-decrement or on either claim silently losing. |
| `armReturnsFalseWhenFlushExpiryAlreadyWon` (`:488`) | **YES** | Drives real drain expiry (`awaitDrainExpiry` spins on the actual flag, not an enum), then asserts `arm` returns false and neither installed hook ran. Fails if the trampoline let a post-expiry `arm` succeed. |
| `livePlanTokenRemainsConsumableDuringFlushing` (`:138`) | **YES** | `register(token)` during `FLUSHING` must succeed; a state check in `consumeRegistration` would throw. |
| `planAndAdmissionAcquisitionRejectAfterFlushWithoutSideEffects` (`:332`) | **YES** | Asserts empty **and** `registeredProducerCount()` unchanged **and** `outstandingAdmissionProducerCount() == 0` — catches a register-then-check-state implementation, which a bare `assertTrue(...isEmpty())` would not. |
| `drainExpiryTerminalizesMissingPlanWithoutIncompleteState` (`DefaultOperationCompletionTest.java:677`) | **MOSTLY** | The drain-expiry half is real (`FAILED` + `CANCELLED_BEFORE_MUTATION` + `TimeoutException` after an actual 40 ms flush). But the trailing `assertThrows(IllegalArgumentException.class, () -> TerminalStatus.valueOf("INCOMPLETE"))` is an assertion on an enum's contents — it can only fail if someone *adds* an `INCOMPLETE` constant. Harmless, but it is the "assertion that cannot fail on the property it names" genre; the drain behaviour above it is what actually guards §3.6b. |
| `drainOwnerFreezesCommittedReceiptWhenTerminalNotificationLosesExpiryRace` (`:697`) | **YES** | Owner hook freezes a `COMMITTED`/`UNAVAILABLE` record; asserts the published result keeps `COMMITTED` + `UNAVAILABLE` + `PARTIAL`, and that a *late* `completion.terminal` throws inside the expiry window. Directly pins the §3.6b "truthful world-mutation status, persistence failure represented only by `HistorySettlement`" rule. |
| `planTokenRegistrationValidatesIdentityAndConsumesCapabilityOnce` (`:894`) | **PARTIALLY — see MINOR-5** | The identity half is real (wrong `operationId` rejected, mismatched `chunkKey` in a terminal record rejected). The "consumes capability once" half never reaches the CAS it names — the coordinator's own duplicate-key guard fires first. Passes against a broken `consumeRegistration` CAS. |

**Amendment-3 markers** at `DefaultOperationCompletionTest.java:888` and `:975` are present,
correctly neutralised, and correctly scoped (both sit on operations with zero successful
registrations). Per the brief, not faulted. I checked whether anything *else* depends on the gap:
`buildResult`'s empty-`registrations` path classifies `SUCCEEDED` (no failures, no records), which
is the known defect; nothing in correctives 3-5 newly relies on it, and the `PlanProducerToken`
path deliberately provides no aggregate pre-registration cause channel, matching §3.6d's
"Amendment-3 boundary" paragraph.

---

## Dev-record accuracy

Generally accurate; line references I spot-checked resolve to the claimed constructs. Three
corrections:

1. **Corrective 3, scope point 2** claims "the post-flush close test asserts one completed result
   and no republish". The assertion is present but vacuous with respect to the mechanism — see
   MAJOR-1. The disposition should read "closed by the admission lifecycle producer +
   `registrationOpen` guard; the publication-reservation callback is defence in depth and is not
   reachable", which is both truer and a stronger claim.

2. **Corrective 4**, on the non-SPI overload: "validates service, operation, chunk, sequence,
   capability reuse". Service and operation are validated; chunk and sequence are compared against
   values derived from the token itself, and capability reuse is caught by the coordinator's map
   before the capability CAS is consulted (MINOR-5). Overstated.

3. **Corrective 5** describes the losing-entry recheck as "rechecks the authoritative closed marker
   after observing released arm state and returns false rather than throwing" — this is exactly
   what `OperationCompletionService.java:1300-1304` does, and the cited test genuinely pins it.
   Accurate.

Corrective 3's `Status: NEEDS_CONTEXT` on point 4 is superseded and correctly resolved by
corrective 4: the r10 shapes (`tryAcquireLease`, lease-coupled `registerProducer`,
`claimRegisteredProducer`) are **gone** from `OperationCompletionService.java` — I grepped; the
only surviving `registerProducer` is the general keyed lifecycle overload at `:117`, which §3.6b
still requires for coordinator/sequencer lifecycle producers and which is not one of the shapes
r13 rescinded. Confirmed.

The "no Gradle run" deviations are consistent with the constraint given to those rounds and with
mine.

---

## What I verified and could NOT fault

- **Isolation is complete and inductive.** Every `CompletionStage`/`CompletableFuture` override in
  both wrapper classes routes through the notification executor or
  `isolatedContinuationExecutor`; `toCompletableFuture`, `newIncompleteFuture`, `defaultExecutor`
  and `minimalCompletionStage` keep derived stages wrapped. I enumerated every public accessor
  that returns a stage across the five files and found no escape.
- **The three-outcome CAS is airtight** — one winner, never zero, and every close is paired with a
  publication and a release.
- **The `arm` trampoline is by construction, not convention.** I actively looked for the window
  (acquire → never arm → flush; acquire → arm races expiry; deliver races release races cancel)
  and could not find one.
- **The admission command queue** (`appendCommand`/`scheduleDrain`/`drainOne`) is free of lost
  wakeups: `commandHead`, `AdmissionCommandNode.next` and `drainScheduled` are all volatile, and
  the `tail.next = node` → `scheduleDrain()` ordering pairs correctly with `drainOne`'s
  `drainScheduled.set(false)` → re-read of `commandHead.next`. The transient broken-chain window
  between the tail CAS and the `next` write is recovered by the appender's own `scheduleDrain`.
- **Drain expiry vs. settlement completion cannot both terminalize a plan** — mutual exclusion on
  a single `Producer.lifecycle` CAS, on the same `Producer` instance.
- **`outstandingTerminals` cannot be double-decremented or lost**, so `buildResult` is total.
- **`SUCCEEDED` under a failed required settlement is not reachable** (two independent gates), and
  `TerminalStatus` is never mutated by persistence failure — only the receipt's
  `HistorySettlement` and the `OperationResult` classification, per §3.6b r4 amendment 4.
- **`schedule`'s release race** (timer handle assigned vs. `releaseAfterPublicationLocked` reading
  it) is covered in both orders by the `commandTail == closedMarker` recheck at `:1252`.
- **Lock ordering is globally consistent today**: `serializationLock < coordinator monitor <
  lifecycleLock`, with no path inverting it in the delivered wiring.
- **`isolatedContinuationExecutor`'s caller-runs fallback** (`:407-413`) runs the continuation on a
  notification task, never on the control thread — so the §1b "caller-runs for owner-bound work is
  forbidden" rule is not breached by the rejection path.
