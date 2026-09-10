# Review — Task 11 correctives 1+2, concurrency / thread-ownership / liveness lens

Reviewer: fresh independent adversarial thread (no prior context in this effort).
Scope: correctives 1 and 2 together, and the resulting state of the code on disk.
Lens: concurrency, thread-ownership, liveness. Persistence/exactly-once-result correctness is
covered by the sibling reviewer and is not duplicated here except where a defect is also a
liveness defect.
Method: every cited line was read in the file on disk. Dev records were treated as claims only.

## VERDICT

**REJECT** — two BLOCKING defects.

1. One externally observable `CompletionStage` escapes the corrective-2 isolation boundary
   entirely, running arbitrary consumer continuations on the control thread *inside* the
   coordinator serialization lock — the exact defect shape that got sibling task 13 rejected
   twice.
2. The post-`TERMINATED` inline path silently strands the coordinator in `COMPLETING` and never
   publishes `OperationResult`, permanently hanging the actor-facing future. Architecture §3.6b
   names this failure mode verbatim ("no stranded or duplicated `COMPLETING`") and the code
   produces it.

Both are single-point defects in otherwise careful work. The two corrective-1 MAJORs are
genuinely closed, and the isolation machinery that *was* built (`CompletionServiceStage` /
`CompletionServiceFuture`) is unusually complete — I could not fault it. The rejection is for
the one stage that was missed and the one unguarded transition.

---

## Disposition of the 2 corrective-1 MAJORs

### MAJOR 1 — Timer-handle retention (uncancelled `ScheduledFuture`s retained the coordinator)

**CLOSED.** Verified end-to-end, including exceptional paths.

- `OperationCompletionService.java:74` — `retryTimer.setRemoveOnCancelPolicy(true)`: a cancelled
  task is physically removed from the `DelayQueue`, not merely flagged. This is the actual leak
  fix; without it `cancel()` leaves the node (and its captured coordinator) in the queue until
  its delay elapses.
- `OperationCompletionService.java:508-524` — `Producer.schedule` now *returns* the
  `ScheduledFuture` handle, which is what makes cancellation possible at all.
- `OperationCompletionService.java:428` — `terminate()` calls `retryTimer.shutdownNow()`.
- Cancellation sites: `DefaultOperationCompletion.java:537` (`reschedule` cancels the superseded
  handle before re-arming), `:559` (`SettlementDeadline.cancel`), `:610`
  (`FinalizerSettlement.abort`), `:684` (`attemptFinished` cancels the attempt timeout), and
  `:719-727` (`PersistenceSettlement.abort` cancels deadline + attempt timeout + retry).

Exceptional paths specifically checked, since that is where handle leaks normally survive a
corrective:

- `DefaultOperationCompletion.java:254-257` — `planTransitionFailed` aborts a live
  `persistenceSettlement` (cancelling all three of its handles) before terminalizing.
- `DefaultOperationCompletion.java:281-287` — `finalizerTransitionFailed` aborts the finalizer
  settlement (cancelling its deadline) before settling.
- `DefaultOperationCompletion.java:604` / `:714` — both `settle(...)` paths call `abort()` *before*
  `producer.complete(...)`, so cancellation is ordered before deregistration on every terminal
  route including timeout and `UNAVAILABLE`.

I also checked the one path that could re-leak: `reschedule()` at `:532-542` cancels the old
handle and then calls `producer.schedule(...)`, which throws `IllegalStateException` at
`OperationCompletionService.java:518` if the producer is already complete. That would leave the
old (already-cancelled, already-removed) handle in `task`. No leak, and the path is unreachable
anyway because `abort()` sets `cancelled = true` (`:720` → `:609`/`:722` → `:557`) before
completing the producer, and `reschedule()` returns early on `cancelled` at `:533-534`.

Residual, non-blocking: `Producer.flushHooks` (`OperationCompletionService.java:451`) is
append-only — `SettlementDeadline.start()` adds a hook at `DefaultOperationCompletion.java:523`
and nothing removes it when the deadline is later cancelled. It is one hook per settlement and
dies with the producer, so it is bounded and not a leak of consequence. Noted for completeness,
not counted as a finding.

### MAJOR 2 — Finalizer boundary lacked the drain clamp that persistence got

**CLOSED.** The finalizer now uses the identical clamp mechanism, not a parallel one.

- `DefaultOperationCompletion.java:578-582` — `FinalizerSettlement` constructs a
  `SettlementDeadline(producer, finalizerTimeout, () -> settle(new TimeoutException(...)))`,
  i.e. the same class `PersistenceSettlement` uses at `:633-637`.
- `DefaultOperationCompletion.java:523` — `SettlementDeadline.start()` registers
  `producer.onFlushing(this::reschedule)`, so flush re-arms the timer against the clamped bound.
- `DefaultOperationCompletion.java:527-530` — `remainingNanos()` reads
  `completionService.effectiveDeadlineNanos(originalDeadlineNanos)`.
- `OperationCompletionService.java:238-245` — `effectiveDeadlineNanos` returns
  `Math.min(requested, flushDeadlineNanos)`, with `flushDeadlineNanos` set under `lifecycleLock`
  at `:158` before `state = FLUSHING` at `:161`.
- `DefaultOperationCompletion.java:58` — `finalizerTimeout` is validated positive-and-finite via
  `requirePositiveDuration` (`:425-432`), so there is no configuration that yields an unbounded
  finalizer.

A never-settling finalizer therefore cannot hang flush *by itself*: at flush the hook fires, the
deadline collapses to the drain bound, the timer expires, `settle(TimeoutException)` runs, and
the producer deregisters. I traced this against a `FinalizerBoundary.begin()` returning a stage
that never completes and could not construct a hang. **Closed.**

Caveat that is *not* a regression of this MAJOR but is reported separately as Finding 3: the
clamp bounds *settlements*, and `flush()` still has no bound of its own for a producer that owns
no settlement at all.

---

## §1b / §3.6b / §3.6c conformance (corrective 2), clause by clause, my lens only

| Clause | Source | Verdict |
|---|---|---|
| "Synchronous consumer continuations never run on the control executor" | §3.6b | **DEVIATES** — Finding 1. `SameChunkPlanSequencer.java:84` + `:76`. |
| "the single control executor never directly invokes an externally observable future completion that can run arbitrary synchronous continuations on that control thread" (r7 am. 2) | §3.6b | **DEVIATES** — Finding 1, verbatim breach. |
| "publishes immutable delivery outcomes through lifecycle-owned, isolated notification tasks" | §3.6b | Conforms for the four isolated stages. `OperationCompletionService.java:53-62` (per-task virtual thread), `:353-371` (`enqueuePublication`), `StageOutcome` `:628-638`. |
| "A blocked consumer continuation may delay only that consumer; it cannot stop other notifications, coordinator transitions, producer-fence quiescence, or shutdown flush" | §3.6b | Conforms **for isolated stages** (one virtual thread per publication; the `pendingOutcomePublications` decrement at `:390-406` runs in the same task, but every dependent of a published target is an async hop, so `publishTo` cannot block). **DEVIATES** via Finding 1, where the continuation is *not* isolated and does wedge everything. |
| "no state transition occurs before an unguarded submit (no stranded or duplicated `COMPLETING`)" | §3.6b | **DEVIATES** — Finding 2. `DefaultOperationCompletion.java:294-301` transitions state, then `publishOutcome` throws at `OperationCompletionService.java:251-253`. |
| "A rejected post-termination submission runs inline under the same coordinator serialization primitive" | §3.6b | Conforms mechanically — `runInline` at `:325-339` takes `serializationLock` at `:328`. But see Finding 4 on *which thread* runs it. |
| "Flush first closes producer registration, then waits for the registered-producer count to reach zero and for all queued completion work to execute; only then may the executor shut down" | §3.6b | Conforms in ordering (`flush` `:147-171`, `terminateIfQuiescent` `:408-418`, `terminate` `:420-430`). **DEVIATES on liveness** — Finding 3: no self-bound. |
| `tryAcquireLease` is an ACCEPTING-only synchronous preflight | §3.6c(2) | **Conforms.** `:101-118`, checks `state != ACCEPTING` under `lifecycleLock`, returns `Optional.empty()`, mutates nothing on rejection. |
| "If the lease cannot be acquired because the service is FLUSHING or TERMINATED, the request fails synchronously before plan registration or any accounting side effect" | §3.6c(2) | **Conforms** at the service side. Enforcement of *call order* is task 13's obligation; the SPI makes it expressible. |
| "The lease remains registered through execution of its delivery command. During FLUSHING, delivery commands associated with existing leases remain accepted." | §3.6c(2) | **Conforms.** `queueDelivery` `:597-624` rejects only on `TERMINATED` (`:607`), not on `FLUSHING`. Lease removal happens in `publicationFinished` `:395-398` — *after* the delivery task ran, not when it was queued. Correct. |
| "Reaching TERMINATED with an outstanding admission-delivery lease is an invariant failure" | §3.6c(2) | **Conforms, and is genuinely fenced** — not merely asserted. `terminateIfQuiescent` `:413-415` returns false while `producerLeases` is non-empty; `terminate()` `:420-426` additionally asserts + throws. The fence is real because `tryAcquireLease` and `flush` serialize on the same `lifecycleLock`. |
| Idempotent delivery per attempt; cross-attempt isolation | §3.6c(3) | **Conforms.** `deliveryQueued` guard `:600-601` under `lifecycleLock`; duplicate live identity rejected at `:108-111`; per-lease `future` at `:562`. |
| "Tick-thread submitters … perform the settlement synchronously (cheap, lock-bounded) … they never deliver, never block on delivery machinery" | §3.6c(4) | **DEVIATES** — Finding 4. `closeAdmission` is a named tick-thread submitter; post-`TERMINATED` it takes a *fair* blocking lock and runs O(#registrations) work. |
| "a tick/region thread must NEVER block, and must never run unbounded or arbitrary user work" | §1b | **DEVIATES** — Findings 1 (post-`TERMINATED` variant) and 4. |

---

## Findings

### BLOCKING 1 — `SameChunkPlanSequencer.Registration.ready()` escapes isolation entirely; consumer continuations run on the control thread inside `serializationLock`

**Files:** `SameChunkPlanSequencer.java:83-84`, `:76`; `OperationCompletionService.java:302-317`,
`:325-339`.

Corrective 2's mandate was full isolation of the observable `CompletionStage`/`Future` surface.
Every observable stage in the task goes through `completionService.isolateStage(...)` — I
enumerated them all:

- `OperationCompletionService.java:75` — `flushView`
- `OperationCompletionService.java:291`, `:338` — submitted-transition stages
- `OperationCompletionService.java:563` — `ProducerLease.futureView`
- `DefaultOperationCompletion.java:61` — `resultView`

Exactly one is not:

```java
// SameChunkPlanSequencer.java:83-84
private final CompletableFuture<Void> ready = new CompletableFuture<>();
private final CompletionStage<Void> readyView = ready.minimalCompletionStage();
```

`readyView` is returned from the public `Registration.ready()` (`:102-104`) — the documented gate
consumers wait on ("Completes only after every earlier registration for this chunk has
terminated"). `minimalCompletionStage()` gives *read-only-ness*, which is presumably what was
intended, but it gives **zero thread isolation**. The JDK's `MinimalStage` is a
`CompletableFuture` subclass that overrides `toCompletableFuture()`, `join`, `get` and `complete`
to throw `UnsupportedOperationException`, but does **not** override `defaultExecutor()` and does
**not** change dependent firing: `readyView.thenRun(action)` creates a `UniRun` with a `null`
executor, so `action` runs on whichever thread calls `ready.complete(null)`.

That thread is chosen at `SameChunkPlanSequencer.java:76`:

```java
private void activate(Registration registration) {
    completionService.execute(() -> registration.ready.complete(null));
}
```

**Constructible interleaving (normal operation, no shutdown involved):**

1. Plans A and B are registered for the same chunk K. `register` at `:34-50` puts A first, so
   B's `ready` is pending.
2. Task 14's broker — the intended consumer of this API — attaches the natural continuation:
   `registration.ready().thenRun(() -> admitAndSchedule())`, or `.thenCompose(v -> ...)`.
   Both are non-async on a `MinimalStage`.
3. Plan A terminalizes on its owning region thread (§3.6 phase 12) → `Registration.terminal()`
   `:107-109` → `SameChunkPlanSequencer.terminal` `:52-73` → `activate(B)` `:70-72`.
4. `completionService.execute` → `submit` `:126-139` → state is `ACCEPTING` → `enqueue` `:278-292`
   → the control executor runs `runQueued` `:294-323`.
5. `runQueued` takes `serializationLock` at `:302` and calls `command.run()` at `:307`.
6. `command` is `() -> B.ready.complete(null)`. `CompletableFuture.complete` → `postComplete()` →
   the `UniRun` has a `null` executor → **`admitAndSchedule()` executes synchronously on the
   completion-service control thread, inside `serializationLock`.**

Consequences, in ascending severity:

- Arbitrary consumer code on the control executor — breaches §3.6b "Synchronous consumer
  continuations never run on the control executor" and r7 amendment 2 verbatim.
- It runs **inside `serializationLock`**, the coordinator serialization primitive. This is
  precisely the shape task 13 was rejected for twice: *a lock held across `future.complete(...)`
  so user continuations run inside the critical section*.
- If that continuation blocks for any reason — awaiting a GET snapshot, `tryAcquire` contention,
  a `join()` on another operation's stage, an I/O call in a history hook — the single control
  thread is wedged **while holding `serializationLock`**. Every coordinator transition stops,
  `terminateIfQuiescent` (`:408-418`) is never reached, producer-fence quiescence stalls, and
  `flush()` never completes. A `runInline` on another thread also parks on `serializationLock`
  at `:328`. That is a full-service deadlock reachable from one ordinary consumer continuation.
- **Post-`TERMINATED` this lands on a tick thread.** `Registration.terminal()` is called from a
  region thread at phase 12. After termination, `submit` `:131` routes to `runInline` `:325-339`,
  which runs `command.run()` on the *calling* thread — so `ready.complete(null)` and therefore
  the consumer's continuation execute **on a region/tick thread, inside `serializationLock`**.
  Direct §1b violation: arbitrary user work, and a blocking fair-lock acquisition, on a tick
  thread.

The fix is one line in shape — route `readyView` through `completionService.isolateStage(ready)`
like every other observable stage — but I am not proposing an implementation, only recording that
the omission is a single point.

### BLOCKING 2 — `closeAdmission()` after service `TERMINATED` strands the coordinator in `COMPLETING` and silently never publishes the result

**Files:** `DefaultOperationCompletion.java:86-95`, `:289-302`;
`OperationCompletionService.java:126-139`, `:247-258`, `:325-339`.

`maybeComplete()` transitions state **before** publishing:

```java
// DefaultOperationCompletion.java:294-301
state = State.COMPLETING;
OperationResult operationResult = buildResult();
state = switch (operationResult.classification()) { ... };   // SUCCEEDED | FAILED | PARTIAL
completionService.publishOutcome(result, operationResult);   // <-- can throw
```

and `publishOutcome` throws unconditionally once the service has terminated:

```java
// OperationCompletionService.java:251-253
if (state == State.TERMINATED) {
    throw new IllegalStateException("Cannot publish a new outcome after completion-service termination");
}
```

`result` (`:31`) is the **only** backing future for the actor-facing `future()` (`:120-122` →
`resultView` `:61`), and `:301` is its **only** completion site. Once `state` has advanced past
`OPEN`, `maybeComplete()` returns immediately at `:290`. So the throw leaves the operation
permanently in a terminal-but-unpublished state.

**Constructible interleaving:**

1. An operation registers one plan. `register` `:66-83` registers producer P₁ at `:78`;
   `startFinalizers` `:140-146` registered producer P₀ at `:141`.
2. The plan's terminal arrives → `terminalOnService` `:157-210` → `producer.complete(NO_TRANSITION)`
   at `:180` → P₁ deregisters via `transitionFinished(producer, true)`
   (`OperationCompletionService.java:373-388`).
3. Finalizers settle → `FinalizerSettlement.settle` `:600-606` → `producer.complete(...)` → P₀
   deregisters.
4. `closeAdmission()` has **not** been called yet, so `maybeComplete()` short-circuits at `:290`
   on `!admissionClosed`. The operation is incomplete but owns **zero registered producers and
   zero leases**.
5. Shutdown runs the frozen order `stopAccepting → drain → completion flush`. `flush(...)`
   `:147-171` sets `FLUSHING`; the no-op enqueued at `:159-160` drives
   `transitionFinished` → `terminateIfQuiescent` `:408-418` → producers empty, transitions zero,
   publications zero, leases empty → **`terminate()` `:420-430`, state = `TERMINATED`.**
6. The broker now calls `closeAdmission()` — which §3.6c clause 4 explicitly designates a
   **tick-thread submitter**. `:94` → `completionService.execute(...)` → `submit` sees
   `TERMINATED` at `:131` → `runInline` `:325-339`.
7. `runInline` runs `closeAdmissionOnService` `:148-151` → `admissionClosed = true` →
   `maybeComplete()` → state advances to `COMPLETING` then to a terminal value → `publishOutcome`
   **throws**.

The throw is caught by `runInline`'s `catch (Throwable failure)` at `:331`, converted to
`outcomeFailure`, and published onto `submitted` at `:337`. But `closeAdmission` reached this via
`Executor.execute` (`:121-123`), whose return value is `void`, so the returned stage at `:338` is
discarded. Nobody observes `submitted`. Java does not report unobserved `CompletableFuture`
exceptions.

Net result: **the actor's `future()` never completes, and there is no exception, no log, and no
diagnostic anywhere.** A silent permanent hang. This breaches architecture §3.6
(`future()` "completes exactly once"), spec §4d ("every async failure reaches the actor exactly
once"), and §3.6b's explicit requirement that the inline fallback produce "no stranded or
duplicated `COMPLETING`" — a stranded `COMPLETING` is exactly what step 7 produces.

Note this is not exotic: step 4 is the ordinary empty-or-already-drained operation, and the
inline fallback exists *precisely* to serve post-flush arrivals. The path was built and left
non-functional for its main coordinator caller.

### MAJOR 3 — `flush(drainRemaining)` enforces no bound on itself; one never-completing producer hangs shutdown forever

**Files:** `OperationCompletionService.java:147-171`, `:238-245`, `:408-418`, `:432-434`.

`flushDeadlineNanos` is written once at `:158` and read in exactly one place — I verified this by
grep across `worldedit-core/src`:

```
OperationCompletionService.java:46,158,241   (declaration, write, read)
DefaultOperationCompletion.java:528          (the only consumer, via effectiveDeadlineNanos)
```

So the drain deadline is a *clamp offered to opt-in settlements*, not a bound on flush. There is
no timer, no `orTimeout`, no expiry callback that forces `flushFuture` to complete.
`flushFuture` completes only via `publishFlushOutcome()` `:432-434`, reachable only from
`terminateIfQuiescent()` returning true, which requires `producers.isEmpty()` at `:409`. Producers
are removed only through `transitionFinished(producer, true)` at `:378-380`, i.e. only via
`Producer.complete(...)`.

**Interleaving:** `register(k, s)` at `DefaultOperationCompletion.java:66-83` registers a producer
for the plan at `:78`. That producer is completed only from `terminalOnService` (`:180`, `:190`,
`:200`), from `PersistenceSettlement.settle` (`:716`), or by an exceptional transition. A plan that
never receives a `terminal(...)` — its owning region died, the world unloaded mid-phase, task 13
rejected it without emitting the terminal, the broker dropped it — leaves its producer registered
forever. Unlike the finalizer producer (which owns a `SettlementDeadline`, see MAJOR 2 above) and
unlike persistence (which owns one at `:633`), **a plain plan producer owns no deadline at all.**

Consequence: `flushFuture` never completes, `terminate()` never runs, so `retryTimer` and
`executor` are never shut down, and the frozen shutdown sequence
(`stopAccepting → drain → completion flush → executor shutdown`) blocks at step 3 permanently.
The server does not shut down.

Architecture §3.6 requires that drain-deadline expiry terminalize every non-mutated plan as
`CANCELLED_BEFORE_MUTATION`/`FAILED_BEFORE_MUTATION`. Performing that terminalization is task 14's
job, and I am not charging this task with it. What I *am* charging is that the service accepts a
`drainRemaining` argument, names it a deadline, validates it positive at `:149-151` — and then
gives task 14 **no mechanism whatsoever** to observe its expiry: no expiry hook, no timer, no
`flushDeadlineExpired` callback, and `effectiveDeadlineNanos` is package-private and only useful
to something that already owns a `SettlementDeadline`. As delivered, the drain deadline is
unenforceable by any caller.

### MAJOR 4 — The post-`TERMINATED` inline concession is documented as async-producers-only but is not enforced; a tick thread takes a fair blocking lock and runs O(#registrations) work

**Files:** `OperationCompletionService.java:126-139`, `:31`, `:325-339`;
`DefaultOperationCompletion.java:94`, `:113`, `:304-319`; `SameChunkPlanSequencer.java:76`.

The dev record's base attack point (`11-completion-protocol.md:154-156`) states that after
`TERMINATED` "§3.6b's serialized inline fallback may use the attaching thread", and §3.6c
constrains only backpressure ("Backpressure never invokes the post-termination inline fallback
from a tick thread"). Nothing constrains the coordinator or the sequencer.

`submit()` `:126-139` performs **no context check at all** — no `FaweThreadContext` consultation,
no assertion, no guard. `DefaultOperationCompletion` has an injected `coordinatorContextGuard`
(`:25`, invoked at `:67`, `:87`, `:99`), but it guards the *public SPI entry points*, not the
inline dispatch, and its semantics are entirely up to the injector.

Meanwhile §3.6c clause 4 explicitly designates **`close`** a tick-thread submitter, and
`closeAdmission()` reaches the inline path at `DefaultOperationCompletion.java:94`. Two further
tick-reachable inline entries exist: `:113` (`recordDuplicateTerminal`, from `terminal()` on a
region thread) and `SameChunkPlanSequencer.java:76` (from `Registration.terminal()` at phase 12).

On that tick thread, `runInline` `:325-339`:

- acquires `serializationLock` at `:328`, which is constructed **fair** (`:31`,
  `new ReentrantLock(true)`). A fair lock hands off in FIFO order and does not barge, so a tick
  thread queues behind every already-waiting inline caller. Blocking on a tick thread is
  categorically forbidden by §1b.
- runs `closeAdmissionOnService` → `maybeComplete()` → `buildResult()`
  (`DefaultOperationCompletion.java:304-319`), which streams over **all** `registrations` twice
  (`:305-307`, `:308-309`) and constructs the full result list. For a large edit that is O(chunks)
  — tens of thousands of entries — on a region thread, inside a fair lock. §1b: "must never run
  unbounded ... work" on a tick thread.

I could not construct an unbounded *block* here in the current code (Finding 2 aside, the inline
bodies are lock-bounded), so this is MAJOR rather than BLOCKING — but combined with BLOCKING 1,
where the inline body runs *arbitrary consumer code* on that same tick thread, the absence of any
enforcement is the load-bearing gap. The concession is documented; it is not enforced.

### MINOR 5 — `lifecycleLock` is held across virtual-thread creation

`OperationCompletionService.java:353-371`: `enqueuePublication` calls
`notificationExecutor.execute(...)` at `:360`, which is `Thread.ofVirtual().name(...).start()`
(`:53-62`). All three callers hold `lifecycleLock` across it: `publishOutcome` `:249-257`,
`publishStageOutcome` `:342-350`, and `ProducerLease.queueDelivery` `:598-623`.

Tick threads do acquire `lifecycleLock` (via `Producer.execute` from a finalizer/persistence
`whenComplete` — see MINOR 7 — and via `closeAdmission`). Thread creation is not user code and is
normally sub-microsecond, so I could not construct a real stall; but it is allocation plus
scheduler submission under a lock a tick thread waits on, and it can throw `OutOfMemoryError`
under thread pressure while the lock is held. Worth hoisting the `execute` outside the lock.

### MINOR 6 — A duplicate `terminal(...)` can go uncounted

`DefaultOperationCompletion.java:98-117`. `terminal()` reads `plan.producer` under the monitor at
`:110`, releases it, then calls `producer.execute(...)` at `:116`. If the first terminal has
meanwhile completed the producer (`:180`/`:190`/`:200`/`:716`), `Producer.execute`
(`OperationCompletionService.java:460-471`) sees `completionRequested` at `:464` and **silently
returns a completed stage**, dropping the transition. The duplicate is then neither recorded at
`:113` (because `plan.record` was still null when the monitor was held) nor counted at `:166`.

`duplicateTerminalCount()` under-reports. Duplicates are specified as diagnostic-only and nothing
double-counts or decrements, so this is not a correctness break — but it does contradict the
"counted exactly once" framing, and it means the diagnostic will silently under-report exactly in
the concurrent case it exists to detect.

### MINOR 7 — Injected boundary lambdas run on the control thread inside `serializationLock`

`DefaultOperationCompletion.java:590` (`boundary.begin()`) and `:659-662`
(`persistenceBoundary.attempt(...)`) are invoked from `FinalizerSettlement.start` / 
`PersistenceSettlement.startAttempt`, both of which reach the control executor via
`producer.execute(...)`/`producer.complete(...)` and therefore run inside `runQueued`'s
`serializationLock` (`OperationCompletionService.java:302-316`).

These are *producers*, not consumer continuations, so §3.6b's prohibition does not literally bind
them, and both are `try`-guarded (`:588-596`, `:658-666`) so a throw settles rather than escapes.
But they are injected lambdas of unbounded content executing on the single control thread while
holding the serialization primitive. If task 14 wires a `PersistenceBoundary.attempt` that does
synchronous I/O before returning its stage, the control executor stalls under lock. Recommend the
contract state explicitly that both must return promptly without blocking.

Relatedly, `finalizers.whenComplete(...)` at `:597` and `attempt.whenComplete(...)` at `:675-677`
are **non-async** continuations on consumer-supplied stages, so their bodies run on whatever thread
completes those stages — which for §3.6 phase 4/6 finalizer receipts is a **region thread**. The
bodies are `producer.execute(...)` only, which is lock-bounded and non-blocking
(`Executors.newSingleThreadExecutor` uses an unbounded queue, so `execute` never blocks), so this
is §3.6c-clause-4 compliant. Noted as verified rather than faulted, but it is the reason MINOR 5
matters.

### MINOR 8 — `CompletionServiceFuture.minimalCompletionStage()` deviates from the JDK contract

`CompletionServiceFuture.java:246-249` returns `new CompletionServiceStage<>(this, ...)`, whose
`toCompletableFuture()` (`CompletionServiceStage.java:296-307`) returns a working future. The JDK
contract for `minimalCompletionStage()` is that `toCompletableFuture()` throws
`UnsupportedOperationException`. Safe (strictly more isolated, not less), but surprising, and it
means a caller cannot rely on minimality as a defence. Cosmetic.

---

## Dev-record accuracy

Claims the artifacts do not support:

1. **Corrective 2, lines 214-218** — "every implicit, explicit, late, and `toCompletableFuture()`
   continuation is routed through an isolated notification task." **False.**
   `SameChunkPlanSequencer.java:84` is an externally observable stage routed through none of it
   (BLOCKING 1). The claim is true of `CompletionServiceStage`/`CompletionServiceFuture` and of the
   four stages that use them; it is stated as if it were true of the task's whole surface.

2. **Corrective 2, lines 219-222** — "publishes its finalized immutable `OperationResult` through
   the service at line 301 rather than completing it on the control executor." Accurate as to
   mechanism, but it omits that `:301` can **throw and permanently strand the operation**
   (BLOCKING 2). The record presents the publication path as unconditionally safe.

3. **Corrective 2, lines 228-231** — "verifies operation publication on notification tasks."
   The cited test (`DefaultOperationCompletionTest.java:193-235`) captures
   `isCompletionThread()`/`isNotificationThread()` **inside a `thenApply` consumer callback**
   (`:209-213`) and asserts at `:232-233`. It asserts the *consumer callback's* thread, never the
   *publishing* thread. An implementation that completed `result` directly on the control thread
   and isolated only the callbacks would pass this test unchanged. The assertion is strictly
   weaker than the claim.

4. **Corrective 2, line 226** — "the preflight/flush race" is covered.
   `OperationCompletionServiceTest.java:264-284` uses a start barrier only, no blocker, and
   branches on the outcome at `:275` (`if (lease != null)`). When flush wins, the assertions at
   `:276-278` are skipped entirely and the test degenerates into "flush terminates" — already
   covered at `:33`. It is structurally incapable of failing on the race it is named for.

5. **Corrective 1, lines 197-198** — "the phase blocker forces the requested race interleavings
   before the completion worker can advance them." Half-true. `blockCompletionService`
   (`CompletionProtocolTestSupport.java:122-138`) parks the single control worker inside
   `runQueued`'s `command.run()` (`OperationCompletionService.java:307`) holding
   **`serializationLock`**. But every race the correctives name — producer deregistration vs.
   flush, lease preflight vs. flush — contends on **`lifecycleLock`**, which the blocker does not
   touch. It genuinely forces the interleaving for exactly one test
   (`DefaultOperationCompletionTest.java:75`), and there the assertion cannot observe it, because
   `duplicateTerminals++` is incremented on both the fast path
   (`DefaultOperationCompletion.java:154`) and the service path (`:166`).

6. **Corrective 1, line 191 / Corrective 2, line 234** — "the race-bearing suite completed 20
   consecutive runs" / "20 consecutive post-review runs." Verified as a *statement about runs*,
   but it is not evidence of race coverage. Three of the four race-named tests
   (`OperationCompletionServiceTest.java:54`, `:264`; `DefaultOperationCompletionTest.java:106`)
   are start-barrier-only with union-safe assertions that also pass under a fully sequential
   execution. 20 green runs of a test that cannot fail is 20 data points about nothing. Peak
   concurrency anywhere in the suite is two threads.

7. **Base record, line 141** — "drain clamp." Accurate for settlements (MAJOR 2 is genuinely
   closed) but the phrase invites the reading that flush is bounded. It is not (MAJOR 3).

Additional test-quality observations supporting the above (not themselves findings):

- `OperationCompletionServiceTest.java:174-210` is the **strongest test in the suite** and I want
  it on record as such: it holds a consumer blocked (`:187`), confirms entry (`:191`), then
  asserts flush completed (`:200`) while the consumer is still parked (`:205`) and the service is
  `TERMINATED` (`:206`). That is a genuine hold-across property. Caveat: the `Runnable::run`
  executor it passes is neutered by `isolatedContinuationExecutor`
  (`OperationCompletionService.java:266-267`), so the test would pass equally against a bounded
  notification pool and does not validate the per-task-thread design.
- No test anywhere asserts that a lock is not held across `future.complete(...)`. The nearest,
  `postTerminationInlineCommandDoesNotHoldLifecycleLock`
  (`OperationCompletionServiceTest.java:139-160`), probes the *command body* and only
  `lifecycleLock` — never `serializationLock`, which `runInline` actually does hold
  (`OperationCompletionService.java:328`). Had such a test existed, BLOCKING 1 would have been
  caught.
- "Control thread never runs consumer code" is spot-sampled at three call sites only
  (`OperationCompletionServiceTest.java:202`, `DefaultOperationCompletionTest.java:232`, `:258`).
  There is no instrumentation that would fail if any *other* callback ran on the control thread.
  No test in this module involves a tick thread at all.
- `assertEquals(0, service.scheduledTimerTaskCount())` at `DefaultOperationCompletionTest.java:518`
  has no drain fence, unlike its success-path equivalent at `OperationCompletionServiceTest.java:396`
  (which fences via `await(service.submit(() -> {}))` at `:393-394`). Flake candidate.
- Timer-handle cancellation is asserted on exactly two paths. The four paths that allocate the
  most handles — persistence exhaustion (`:296-332`), settlement-deadline exhaustion (`:376-410`),
  attempt timeout + retry (`:413-450`), cancelled persistence + retry (`:453-486`) — assert
  nothing about timer state.
- `settlementDeadlineExhaustionProducesUnavailable` (`:376-410`) sets a 30 ms deadline against a
  1 s attempt timeout and asserts only `UNAVAILABLE` + `PARTIAL` (`:407-408`). Both bounds produce
  that outcome, so the test cannot distinguish which fired and does not verify the path it is
  named for.
- No coverage of unbounded queue/thread growth. `notificationExecutor` starts a new virtual thread
  per publication *and per continuation hop* with no ceiling
  (`OperationCompletionService.java:53-62`); `notificationSequence` (`:35`) is never read by any
  test.

---

## What I verified and could NOT fault

These were attacked deliberately and held. Listing them so the orchestrator can judge coverage
and so a re-corrective does not churn them.

**Lock ordering — no inversion exists.** I traced every acquisition of the three locks and
established a total order: `serializationLock` ≺ `monitor(DefaultOperationCompletion)` ≺
`lifecycleLock`.
- `serializationLock → monitor`: `runQueued` `:302` → `command.run()` `:307` →
  `terminalOnService`'s `synchronized (this)` at `DefaultOperationCompletion.java:162`.
- `monitor → lifecycleLock`: `register` (`synchronized`, `:66`) → `registerProducer` `:78` →
  `lifecycleLock` at `OperationCompletionService.java:81`; and `maybeComplete` (called under the
  monitor) → `publishOutcome` `:301` → `lifecycleLock` `:249`.
- No path holds `lifecycleLock` and then takes `serializationLock` or the coordinator monitor.
  The two places that could have — `submit` and `Producer.transitionFailed` — both release
  deliberately first: `submit` `:129-138` releases in `finally` *before* calling `runInline`, and
  `transitionFailed` `:535-548` releases before invoking `transitionFailure.accept(failure)`.
  Both look intentional. Good discipline.
- The coordinator never holds its monitor across a service call that could reenter: `terminal()`
  exits the `synchronized` block at `:111` before `producer.execute` at `:116`; `closeAdmission()`
  exits at `:93` before `completionService.execute` at `:94`; `SameChunkPlanSequencer` exits its
  monitor at `:45`/`:69` before `activate` at `:47`/`:71`. I looked specifically for a monitor
  held across `execute` and found none.
- Reentrancy under the notification-task isolation: a consumer continuation running on a
  notification virtual thread that calls back into the service takes `lifecycleLock` fresh — it
  holds nothing. No inversion introduced by corrective 2's isolation.

**`CompletionStage` / `CompletableFuture` isolation surface — I could not find an escape** in
`CompletionServiceStage` or `CompletionServiceFuture`. This was the main hunt and it is genuinely
well done. Specifically checked:
- All 43 `CompletionStage` methods in Java 21 are overridden in `CompletionServiceStage`, including
  the `default` methods (`exceptionallyAsync` `:229-236`, `exceptionallyCompose` `:275-294`) which
  would have been safe anyway but are covered. Every non-async variant delegates to the `*Async`
  form with the notification executor.
- `CompletionServiceFuture` overrides `newIncompleteFuture()` (`:30-32`) — this is the
  load-bearing one: it makes every future derived from `super.*Async` also a
  `CompletionServiceFuture`, so isolation is inherited down arbitrarily long chains. Missing it
  would have been an escape on the second link.
- `defaultExecutor()` (`:25-27`) covers the one-arg `completeAsync(Supplier)`, which the JDK
  implements as `completeAsync(supplier, defaultExecutor())` via virtual dispatch to the
  overridden two-arg form (`:237-239`).
- `orTimeout` / `completeOnTimeout` are *not* overridden. I checked whether this escapes: they
  complete from the JDK's internal `Delayer` thread, but every dependent is an async hop, so only
  the `complete()` call itself runs there. Not an escape.
- Explicit executors: `isolatedContinuationExecutor` (`OperationCompletionService.java:264-276`)
  hops to a notification thread *first*, then submits to the requested executor, and falls back to
  `command.run()` on the notification thread if the requested executor rejects (`:272-274`). So a
  caller-runs / direct executor cannot pull a continuation onto the calling or control thread.
- `toCompletableFuture()` (`CompletionServiceStage.java:296-307`) attaches
  `whenCompleteAsync(..., executor)` — an async hop — so the raw delegate never acquires a
  synchronous dependent.
- `CompletableFuture.allOf(ourFuture, other)` returns a plain future whose non-async dependents
  run on the completing thread; I traced this and the completing thread is the notification
  virtual thread from the `toCompletableFuture` hop, not the control thread. Not an escape.
- Late attachment after terminal publication, exception paths, `whenComplete` vs `handle`,
  chained/derived stages, and cancellation were each traced. All isolated.
- Raw futures are never exposed: `result` (`DefaultOperationCompletion.java:31`), `flushFuture`
  (`:40`), `ProducerLease.future` (`:562`), and `submitted` (`:282`, `:326`) are all private with
  only isolated views returned. Verified by grep over the package.

**Publication counting does not hold the fence across consumer code.** `enqueuePublication`
`:353-371` decrements `pendingOutcomePublications` in a `finally` *inside* the notification task,
after `outcome.publishTo(target)`. I attacked this as a potential "counter held across
`future.complete`" and it holds: because every dependent of a published target is an async hop
(above), `publishTo` only performs `executor.execute(...)` calls and cannot block. The comment at
`:143` ("Consumer continuations are isolated and therefore are not part of the flush fence") is
accurate *given* the isolation — which is exactly why BLOCKING 1 breaks it.

**Termination fence vs. leases is real, not decorative.** `tryAcquireLease` `:101-118` and
`flush` `:147-171` both mutate under `lifecycleLock`, and `flush` holds it across the entire body
including the `state = FLUSHING` write at `:161`. A lease therefore cannot be acquired
concurrently with the ACCEPTING→FLUSHING transition. `terminateIfQuiescent` `:413-415` refuses to
terminate while any lease is outstanding, and lease release happens in `publicationFinished`
`:390-406` *after* the delivery task ran. I tried to construct a slip-past and could not.

**Flush cannot terminate prematurely at the moment of transition.** `flush` enqueues a no-op at
`:159-160` *before* setting `FLUSHING`, while holding `lifecycleLock`. The executor thread running
that no-op blocks on `lifecycleLock` in `publishStageOutcome` `:342` until `flush` releases, so it
always observes `FLUSHING` and always drives one `terminateIfQuiescent`. The
publication-counted-before-transition-uncounted ordering in `runQueued` (`:318-322`) leaves no gap
where both counters read zero mid-flight.

**`SettlementDeadline` is single-thread confined despite plain (non-volatile) fields.** Fields
`originalDeadlineNanos`, `generation`, `task`, `cancelled`
(`DefaultOperationCompletion.java:506-509`) are unsynchronized. I traced every mutator —
`start` `:521`, `reschedule` `:532`, `deadlineReached` `:544`, `cancel` `:556` — and every one
reaches the single control executor: `producer.onFlushing` hooks are drained through
`enqueue` (`OperationCompletionService.java:163-165`, `:500`), and `producer.schedule` `:520`
runs `() -> execute(transition)` on the timer thread which merely *enqueues* to the control
executor. The single-thread executor supplies the happens-before. Correct, though fragile — it
depends on `Producer.schedule` never invoking the transition directly, which is worth a comment.

**Generation guards are correct.** `SettlementDeadline.generation` (`:536`, `:545`, `:558`) and
`PersistenceSettlement.attemptGeneration` (`:656`, `:681`, `:686`, `:721`) both close the stale-
timer-fires-after-supersession race, and both are checked before any state mutation. Corrective
1's claim here is accurate.

**`HistoryPersistencePolicy` admits no unbounded configuration.** `maxAttempts > 0`,
`attemptTimeout` and `settlementTimeout` strictly positive, `retryBackoff` non-negative, and every
`Duration` is forced through `toNanos()` in the compact constructor so an overflowing `Duration`
fails at construction rather than at scheduling. Matches §3.6b's "No configuration may select
unbounded attempts, backoff, or attempt duration."

**`SameChunkPlanSequencer` ordering and monitor discipline** (apart from BLOCKING 1): sequence
allocation is globally monotonic (`:41`), exhaustion is rejected before overflow (`:38-40`),
`terminal()` is idempotent (`:55-57`), out-of-order termination is rejected (`:59-61`), and the
per-chunk deque is evicted when empty (`:64-65`) with the global counter preventing sequence reuse
after eviction (comment at `:15` — correct). `activate` is always called outside the monitor.

**No `synchronized` blocks on the notification virtual threads.** I checked for Java 21 carrier
pinning (JEP 491 landed in 24, not 21): the notification task body `:360-366` and
`StageOutcome.publishTo` `:630-636` contain no `synchronized`, and `publicationFinished` uses
`ReentrantLock`, which parks without pinning. Consumer continuations may still pin, but that is
the consumer's carrier, bounded by the scheduler's compensation up to
`jdk.virtualThreadScheduler.maxPoolSize` (default 256). Not faulted, but see the note below.

Two things I checked and am explicitly **not** raising as findings, to save the next reviewer the
trip: (a) unbounded virtual-thread creation in `notificationExecutor` `:53-62` — one thread per
publication and per continuation hop is the intended cost of per-task isolation and virtual
threads are the right primitive for it; (b) `Producer.execute` silently dropping transitions for
a completed producer (`:464-466`) — correct for its callers, and the one place it under-reports is
MINOR 6.

---

## Recommended disposition

Corrective 3, narrowly scoped to:

1. Route `SameChunkPlanSequencer.Registration.readyView` through
   `completionService.isolateStage(...)` (BLOCKING 1). Then add the missing regression test: a
   continuation attached to `ready()` must not observe `isCompletionThread()`, and must not
   observe the attaching thread when the service has terminated.
2. Make `maybeComplete()`'s state transition and publication atomic with respect to service
   termination (BLOCKING 2) — publish first, or reserve the publication, or have the
   post-`TERMINATED` inline path publish the outcome directly rather than throwing. Whatever the
   shape, the invariant to test is: after `flush()` completes, a subsequent `closeAdmission()`
   must still complete `future()` exactly once.
3. Give `flush(drainRemaining)` a self-bound or an expiry hook so task 14 can honour §3.6's drain
   terminalization (MAJOR 3).
4. Either enforce the async-producer restriction on the inline fallback or accept tick-thread
   entry explicitly and bound the work it performs (MAJOR 4).

The test suite should not be re-blessed on "20 consecutive runs" — three of the four race-named
tests cannot fail on their named race (dev-record items 4-6). The one test seam that would have
caught BLOCKING 1 does not exist anywhere in the production code: nothing can pin a thread
mid-critical-section, so no test in this suite can observe a lock held across a completion.
