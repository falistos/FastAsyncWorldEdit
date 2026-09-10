# Cross-review W1 — Task 13 `DefaultFoliaBackpressure` — lens: concurrency / thread-ownership / liveness (§1b)

Reviewer: fresh independent Opus thread. Everything cited below was read on disk this session:
`DefaultFoliaBackpressure.java` (1222 lines), `DefaultFoliaBackpressureTest.java` (630 lines),
`architecture.md` §1 (liveness), §3.4, §3.6, §3.6b, §3.6c, `tasks/13-backpressure.md` (mandate +
all four dev records), `OperationCompletionService.java`, `CompletionServiceStage.java`,
`FoliaBackpressure.java`, `RegionKey.java`, and JDK 25 `CompletableFuture`/`ForkJoinPool` sources
(`temurin-25.jdk/lib/src.zip`) for the seeded question. No Gradle was run; nothing was edited.

---

## VERDICT

**REJECT**

Three independent BLOCKING findings, each of which places unbounded or arbitrary-thread work on a
region thread, or permanently strands an admission attempt. The §3.6c *shape* is right — I confirm
the orchestrator's surface check and go further below on what is provably clean — but clause 4
(tick-thread submitters) is not met, and the two structural safety properties that make the
settlement/notification split actually safe (per-delivery exception isolation, and an executor on
the cancellation callback) are missing.

Failure mode #2 of the two rejected attempts (lock held across `future.complete`) is genuinely,
verifiably gone. Failure mode #1 recurs in a weaker but real form. Failure mode #3 is fixed.

---

## Seeded question — is deadline expiry subject to common-pool starvation?

**Yes. Verdict: this is a real defect, not a theoretical one, and there is a strictly better
in-contract mechanism already sitting in the injected collaborator.**

### What the JDK actually does (verified, not recalled)

`DefaultFoliaBackpressure.java:455` calls
`CompletableFuture.delayedExecutor(delay, NANOSECONDS).execute(() -> expire(waiter))`.

From `temurin-25` `java.base/java/util/concurrent/CompletableFuture.java`:

- line 477: `private static final ForkJoinPool ASYNC_POOL = ForkJoinPool.asyncCommonPool();`
- lines 2890-2892: the one-arg `delayedExecutor(delay, unit)` binds `executor = ASYNC_POOL`.
- lines 2938-2951 `DelayedExecutor.execute(r)`: `ASYNC_POOL.scheduleDelayedTask(new
  ScheduledForkJoinTask<>(nanoDelay, 0L, true, new TaskSubmitter(executor, r), null, e))`.
- lines 2954-2962 `TaskSubmitter.run()`: `executor.execute(action)`.

And `ForkJoinPool.asyncCommonPool()` (line 3120) returns `common`, only forcing parallelism to 2 if
it was configured to 0. So:

- the **timer fire** happens on the common pool's `DelayScheduler` thread and is not blockable —
  that part is fine;
- `expire(waiter)` itself is then **pushed to and executed on `ForkJoinPool.commonPool()`**, whose
  parallelism is `availableProcessors() - 1` (i.e. **1 worker on a 2-vCPU container**, a completely
  ordinary Minecraft host shape).

### Can that pool be saturated?

Yes, by three distinct sources, one of which is task 13 itself.

1. **Self-inflicted.** `DefaultFoliaBackpressure.java:588` submits `serviceWaiters` to the same pool
   via `CompletableFuture.runAsync(this::serviceWaiters)`, and `serviceWaiters` (line 594) does a
   **blocking** `accountingLock.lock()`. On a 2-vCPU host (parallelism 1) that single worker parks
   in `lock()` whenever a FAWE worker or a region thread holds `accountingLock`; the queued
   `expire` task cannot run until it unparks. ForkJoinPool only compensates for `ManagedBlocker`
   and `join()`; a plain `ReentrantLock.lock()` gets **no compensation thread**. Bounded, but it is
   head-of-line blocking on the exact path the whole re-plan was about.
2. **JVM-shared.** The common pool is a process-global resource. Any plugin, library, or JDK
   consumer doing `CompletableFuture.supplyAsync(blockingIO)` or a blocking `parallelStream()`
   occupies workers with no compensation. FAWE itself is nearly clean here (I grepped: only
   `FoliaTaskManager.java:398`, which passes an explicit executor, and this file's line 588), but
   backpressure has no way to reserve capacity against the rest of the server.
3. **No lifecycle fence.** The pending `expire` tasks are owned by nothing. `stopAccepting` does
   not cancel them, and `OperationCompletionService.flush` does not wait for them (they are not
   producers, not leases, not queued transitions). §3.6b explicitly requires the opposite for the
   analogous timer: *"The retry timer remains lifecycle-guaranteed until settlement flush
   completes."*

### Is `expire` bounded once it runs?

**Mostly, but not O(1).** `expire` (lines 482-496) takes the lock, re-checks `QUEUED` +
`deadlineExpired`, does `removeQueued` + `reject`, then `requestWaiterService`. That last call runs
`serviceWaiterBatch` **inline under the lock**, examining up to `2 × WAITER_SERVICE_BATCH` = 128
waiters and producing up to 128 grants; after unlock, `deliverAll` issues up to 128 lease
deliveries, each taking the completion service's `lifecycleLock` and starting a virtual thread. And
`deliverAll → deliverGrant → deliverAll` can recurse (line 526). So "bounded" holds per invocation
only in the sense of ≤128 per level, with unbounded recursion depth in the cancel-supersession
chain. See MAJOR-2 and MAJOR-3.

### Is there a better in-contract mechanism?

Yes, and it is already in the injected collaborator. `OperationCompletionService.Producer.schedule
(Runnable, Duration)` (`OperationCompletionService.java:763-779`) schedules on the service's
**dedicated `retryTimer`** thread and re-enters through the registered producer. That gives, all at
once, what the common pool cannot:

- a dedicated timer thread not shared with arbitrary server work;
- lifecycle fencing (`producer` registration keeps flush open until the timer settles, satisfying
  §3.6b's "lifecycle-guaranteed until settlement flush completes");
- `retryTimer.setRemoveOnCancelPolicy(true)` (line 88) so cancelled deadlines don't accumulate.

The reason the worker did not use it is presumably that `Producer.schedule` routes the transition
through the single control executor (`enqueue` → `runQueued` → `serializationLock`). Under the
**pre-r8** design that would have been the original sin. Under the **current** r7-am-2 / r8
notification-isolation design it is safe: `expire`'s critical section runs no user code and
completes no future — `deliverAll` only enqueues immutable outcomes to isolated notification tasks.
A blocked consumer therefore cannot occupy the control thread. `Producer.schedule` is now strictly
better than the common pool on every axis.

**Bottom line on the seeded question:** §3.6c's letter ("deadline expiry is a state settlement…
once settled, effective immediately regardless of when its notification is delivered") is
satisfied — settlement genuinely does not wait for notification. But the *scheduling* of that
settlement was moved from a lock the worker controlled onto a JVM-shared, unfenced, potentially
single-threaded pool. The requirement that triggered the whole re-plan was "deadline expiry must
never be blockable"; expiry is now blockable by anything else running in the JVM. That is a
narrower window than the previous design, but it is not zero, and the fix is a one-line swap to an
already-injected, already-lifecycle-fenced timer. **MAJOR-1 below.**

---

## §3.6c conformance, clause by clause (lens clauses)

### Clause 1 — state settlement under the accounting lock; never runs user code, never completes futures

**CONFORMANT with two caveats.** I read every `accountingLock` critical section
(`171-204`, `252-282`, `314-325`, `336-347`, `353-363`, `387-421`, `460-480`, `484-496`,
`501-515`, `594-603`, `779-809`, `817-828`, `1018-1025`). Not one of them contains a
`future.complete`, a `lease.deliver`/`deliverExceptionally`, a `rejectionHandler` invocation, a
`Consumer` call, or a blocking wait. Settlement collects immutable `AdmissionDelivery` values and
returns. Deadline-expiry removal (`removeQueued`, line 489) is visible before any delivery. This is
the clause the two previous attempts died on and it is now genuinely clean.

Caveats: (a) `grant()` calls the injected `nanoTime` supplier **under** the lock (line 544) — in
production `System::nanoTime`, but it is an injection point through which a collaborator can block
inside the critical section, and the test suite exploits exactly that at
`DefaultFoliaBackpressureTest.java:378-384`; (b) `scheduleWaiterService()` calls
`CompletableFuture.runAsync` under the lock (line 588), which can throw
`RejectedExecutionException` — see MAJOR-5.

### Clause 2 — lease preflight before accounting; delivery after unlock; hook on the isolated stage

**CONFORMANT.** `tryAcquireLease` is the first accounting-relevant statement in both entries
(line 169 and line 373), strictly before the lock. Every `deliverAll(...)` call site follows an
`accountingLock.unlock()` (I re-verified all ten independently of the orchestrator's check;
note lines 419-421/432, where `deliverAll` is outside the try/finally entirely — correct). The
rejection hook is installed on `lease.future()` (line 1000), which is
`ProducerLease.futureView` = `isolateStage(future)`; `CompletionServiceStage.whenComplete`
(`CompletionServiceStage.java:239-241`) forwards to `whenCompleteAsync(action, notificationExecutor)`
→ a fresh virtual thread. The hook therefore cannot run on any settlement thread. Verified end to
end, not assumed.

Spirit deviation: "Backpressure owns NO completion thread, no fallback drainer, no serialization
primitive of its own." No thread is *owned*, true — but backpressure now depends on two
`ForkJoinPool.commonPool()` submissions (lines 455 and 588) that sit outside the completion
service's lifecycle fence. The letter is satisfied by disowning the thread; the intent (all
asynchrony flows through the one backend-owned service) is not.

### Clause 3 — attempt identity and supersession

**PARTIALLY CONFORMANT.** Identity is sound: `AdmissionAttemptId` is monotonic
(`nextAdmissionAttemptId`, line 991, with overflow fail-fast), is the `producerLeases` key, and
`ProducerLease.queueDelivery`'s `deliveryQueued` flag gives per-attempt delivery idempotence.
Cancel-before-grant-delivery does release the permit exactly once — `cancel` (case `GRANTED`) and
`deliverGrant` (the `cancellationObserved` branch) are mutually exclusive under the lock, and the
loser sees `REJECTED`/`DELIVERY_CLAIMED` and no-ops.

The deviation is the linearization point. §3.6c item 3 says *"If a granted attempt is cancelled
**before grant delivery**…"*. The code moves the point earlier, to `DELIVERY_CLAIMED`
(line 510), which is set under the lock but **before** `lease.deliver` runs (line 518). See
MAJOR-4: this is not forced by the design, and it is completely untested.

### Clause 4 — tick-thread submitters settle synchronously, cheaply, lock-bounded, never block on delivery machinery

**NON-CONFORMANT.** This is the clause that carries the REJECT.

| tick path | lock acquisition | inline work | delivery work |
|---|---|---|---|
| `transfer` (245) | `tryLock(100µs)` — bounded park | accounting + `serviceWaiterBatch` ≤128 | `deliverAll` ≤128 lease deliveries |
| `enter(FINALIZING)` (775) | **`lock()` — unbounded wait** | same | same |
| `close` (815) | **`lock()` — unbounded wait** | same | same, **and can throw** |
| `cancel` (458) — *not in the sanctioned list, reachable from a tick thread* | **`lock()`** | same | same |
| `tryAcquire` (165) | `tryLock()` (correct) — but **`lifecycleLock.lock()` first**, inside `tryAcquireLease` | — | lease + publication + **virtual thread start, on every call including misses** |

The asymmetry is telling: `transfer` deliberately bounds its wait to 100µs
(`TRANSFER_LOCK_TIMEOUT_MICROS`), acknowledging that a tick-thread wait on `accountingLock` is a
hazard — and then `enter` and `close`, on the same threads, use an unbounded `lock()` with no
justification anywhere in the file or the dev record.

### Clause 5 — async acquire returns the service's isolated stage; blocked consumer cannot delay settlement

**CONFORMANT.** `acquire` returns `lease.future()` (lines 383, 433), the isolated view. A blocked
consumer continuation runs on a notification virtual thread, holds no backpressure lock, and cannot
reach `accountingLock`. The property holds — but note it is supplied by task 11's
`CompletionServiceStage`, not by anything task 13 wrote, which matters for how much credit the test
below deserves.

---

## Findings

### BLOCKING-1 — the cancellation callback has no executor: an operation cancel can run the entire settlement + delivery cascade on a region thread

`DefaultFoliaBackpressure.java:424-427`

```java
cancellationSignal.whenComplete((ignored, failure) -> {
    waiter.cancellationObserved = true;
    cancel(waiter);
});
```

`whenComplete` — not `whenCompleteAsync`, no executor. A plain `CompletionStage` continuation runs
**on whatever thread completes the stage**, or synchronously on the registering thread if the stage
is already complete. `cancellationSignal` is supplied by the caller (task 14/17); §3.4's javadoc
defines it as *"completes when the operation is cancelled"*, i.e. one stage per **operation**, not
per waiter.

`cancel(waiter)` is not cheap: `accountingLock.lock()` (unbounded wait) → `removeQueued`/`release`
→ `reject` → `requestWaiterService` (≤128 waiter evaluations + up to 128 grants, inline under the
lock) → unlock → `deliverAll` (up to 128 × [`accountingLock` round-trip + completion-service
`lifecycleLock` + `Thread.ofVirtual().start()`]).

**Concrete interleaving.** A player runs `//cancel`, or a chunk-unload / world-unload handler fires,
or the plugin's disable path completes the operation's cancellation future. On Folia every one of
those runs on a **region thread**. That thread now executes, serially:

1. one `cancel(waiter)` per waiter registered against that operation — the callbacks are chained on
   the same `CompletableFuture` and all fire on the completing thread;
2. with up to `maxWaitersPerRegion` = 256 per region and `MAX_GLOBAL_WAITERS` = 65,536 globally,
   the loop count is bounded only by the global waiter cap;
3. each iteration blocks on `accountingLock` for an unbounded time and starts up to 128 virtual
   threads.

This is precisely §3.4's forbidden shape — *"no owner-bound task on the submitting thread"* — and
§1b's *"a tick/region thread must never block and must never run unbounded work."* It is also,
structurally, the same family as the inline-drain-on-a-tick-thread that killed attempt #1: the work
was removed from the rejection path and reappeared on the cancellation path.

The fix is one token: `whenCompleteAsync(handler, completionService)` (or the notification
executor). That this was not done, while the identical concern was handled correctly two lines
earlier for the rejection hook via the isolated stage, reads as an oversight rather than a
deliberate trade-off — nothing in the dev record mentions the cancellation callback's thread.

### BLOCKING-2 — `deliverAll` has no per-delivery exception isolation: one throw strands every remaining lease, and the corresponding `acquire()` stages never settle

`DefaultFoliaBackpressure.java:1049-1057`

```java
private void deliverAll(List<AdmissionDelivery> deliveries) {
    for (AdmissionDelivery delivery : deliveries) {
        if (delivery.failure() == null) {
            deliverGrant(delivery.waiter());
        } else if (!delivery.waiter().lease.deliverExceptionally(delivery.failure())) {
            LOGGER.error(...);
        }
    }
}
```

No `try`/`catch` around the loop body. `deliverGrant` throws on at least three reachable paths:

- line 519: `if (!waiter.lease.deliver(waiter.permit)) throw new IllegalStateException(...)` —
  reachable when `OperationCompletionService.expireFlush` (line 581-591) has already claimed that
  lease with the drain `TimeoutException` between the settlement that produced the delivery and its
  execution;
- `release(waiter.permit)` → `requiredState` (line 873-879) → `IllegalStateException("missing
  backpressure accounting for region")`;
- `releaseReadyCapacity` → `removeReadySince` (line 926-937) → `IllegalStateException("missing ready
  timestamp")`.

**Concrete interleaving (normal operation, no shutdown).** A region-thread `close()` releases
capacity, `requestWaiterService` grants 40 queued waiters, `deliveries` has 40 entries. Delivery
#3's `deliverGrant` hits any of the above fail-fast ISEs. The loop aborts. Waiters #4-#40:

- their `ProducerLease` is live, `deliveryQueued == false`, and **nothing will ever deliver it** —
  they were removed from `regularWaiters` by `serviceWaiters` (line 652) and their state is
  `GRANTED`, so no later `serviceWaiters`, `expire`, or `cancel` pass will revisit them;
- their `acquire()` `CompletionStage` therefore **never completes** — the FAWE worker awaiting
  admission waits forever. That is a hang, and a silent one;
- their permits are never closed → the accounting they hold is never released → `pruneIfIdle` never
  fires → the region's capacity is permanently reduced;
- `producerLeases` stays non-empty → `terminateIfQuiescent`
  (`OperationCompletionService.java:607-617`) can never return true outside a flush → the service
  cannot terminate. At shutdown `expireFlush` mops these up with a `TimeoutException`, so the
  process does eventually exit — but §3.6c's *"Reaching TERMINATED with an outstanding
  admission-delivery lease is an invariant failure"* is the stated invariant, and the system is
  operating in violation of it for the entire uptime after the first such throw.

The exception then propagates out of the `finally { unlock; deliverAll(...); }` of whichever method
was settling. In `enter()` (lines 806-812) it **replaces** the deliberate `transitionFailure`
`IllegalStateException`, destroying the fail-fast diagnostic. In `transfer()` (lines 278-281) it
converts a `return false` — the safe "defer before mutation" answer — into a thrown exception on
the owner thread immediately before mutation. In `close()` it makes a method documented "Idempotent"
and used from commit-phase `finally` blocks throw on a region thread.

Every delivery must be individually isolated: `try { … } catch (Throwable t) { LOGGER.error(…); }`
around each iteration, plus a guaranteed terminal outcome for a waiter whose delivery threw.

### BLOCKING-3 — `tryAcquire`, the only tick-legal admission entry, blocks on a globally-contended lock and starts a virtual thread on every call, including every miss

`DefaultFoliaBackpressure.java:165-226`

The mandate's acceptance criterion is literal: *"`tryAcquire` provably never blocks (returns
`Optional`, no await path)."* The `accountingLock.tryLock()` at line 181 is correct and is
genuinely non-blocking. But line 169 runs **first**:

```java
Optional<ProducerLease<Permit>> optionalLease = completionService.tryAcquireLease(attemptId);
```

`OperationCompletionService.tryAcquireLease` (line 169-187) does `lifecycleLock.lock()` — an
**unbounded blocking acquire on the completion service's single global lock**, which is contended by
every publication start/finish, every queued transition, every producer registration, every
`Producer.execute`, and `expireFlush` across the whole backend. A region thread on the G-A1 fast
path parks there.

Worse is the cost profile on the **miss** path, which is the hot path precisely when it matters:

- line 208 `lease.deliverExceptionally(refusal)` → `queueDelivery` → `lifecycleLock.lock()` a
  **second** time → `reservePublicationLocked` → `startPublication` → `notificationExecutor.execute`
  → `startNotification` → `Thread.ofVirtual().name(...).start(command)`.

So **every** `tryAcquire` — including the ones that return `Optional.empty()` because the region is
saturated — allocates an `AdmissionAttemptId`, an entry in the service's `producerLeases` `HashMap`,
a `CompletableFuture`, a `CompletionServiceStage`, a `Publication`, takes `lifecycleLock` twice, and
**starts a virtual thread** whose only job is to complete a future nobody holds. Under saturation
the miss rate approaches 100%, so region-thread saturation produces a storm of virtual-thread
creation *on the region threads*, on the one path §3.4 designated as the cheap owner fast path.

The design intent is stated at lines 206-207 ("The private lease still receives one outcome for
flush fencing"), but a fast-path *miss* — which by the code's own comment is "a non-terminal owner
fast-path miss", produces no registration, no plan, and no terminal record — needs no flush fence at
all. The lease should be acquired lazily, only once a grant is actually about to be issued.

This finding becomes structurally fatal under r10 — see the r10 section.

### MAJOR-1 — deadline expiry and waiter service run on `ForkJoinPool.commonPool()`, outside any lifecycle fence

`DefaultFoliaBackpressure.java:455` and `:588`. Full analysis in the seeded-question section above.
Concrete failure: on a 2-vCPU host the common pool has one worker; `serviceWaiters` parks it in
`accountingLock.lock()`; the queued `expire` task waits behind it with no FJP compensation. Plus:
no `stopAccepting`/`flush` path cancels or waits for pending `expire` tasks, contradicting §3.6b's
"lifecycle-guaranteed until settlement flush completes". Remedy:
`OperationCompletionService.Producer.schedule(…)`.

### MAJOR-2 — tick-thread settlement does up to 128 inline waiter evaluations, 128 grants, and 128 virtual-thread starts per call

`requestWaiterService` (578-583) resets the scan budget and calls `serviceWaiterBatch`
**synchronously**, i.e. inside the caller's critical section. `serviceWaiterBatch` examines up to
`WAITER_SERVICE_BATCH` continuation + `WAITER_SERVICE_BATCH` regular = 128 waiters and can produce
128 grants. `deliverAll` then performs 128 × (`accountingLock` acquire/release in `deliverGrant` +
`lifecycleLock` + virtual-thread start).

Every sanctioned tick-thread submitter reaches this: `transfer` (line 276), `enter(FINALIZING)`
(line 800), `close` (line 823). §3.6c clause 4 says these settle "cheap, lock-bounded" and "enqueue
the delivery" — enqueueing is the right shape, but ~128 thread starts and 128 global-lock
acquisitions per permit close, at commit-broker rates, is not "cheap". A tick-thread caller should
settle its own accounting and *only* `scheduleWaiterService()` (post the async pass), never run
`serviceWaiterBatch` inline.

### MAJOR-3 — `deliverAll` → `deliverGrant` → `deliverAll` is unbounded mutual recursion, reachable on a tick thread

`deliverGrant` (498-527) collects its own `deliveries` list and calls `deliverAll(deliveries)` at
line 526. The cancel-supersession branch (503-506) calls `release` + `requestWaiterService`, which
can append fresh grants to that list. So:

`deliverAll(L)` → `deliverGrant(w₁)` → [w₁ cancelled] → `requestWaiterService` grants w₂…w₁₂₉ →
`deliverAll(L₂)` → `deliverGrant(w₂)` → [w₂ cancelled] → …

Each level consumes distinct waiters, so the recursion terminates, but the depth is bounded only by
the waiter population (up to 65,536 globally), not by a constant. Under a mass cancellation — the
exact scenario BLOCKING-1 puts on a region thread — this is deep recursion plus unbounded work on a
tick thread. The exception path recurses too: line 522 `waiter.permit.close()` re-enters
`close(permit)` → `requestWaiterService` → `deliverAll`.

Deliveries should be drained iteratively from a work list, not by recursive descent.

### MAJOR-4 — supersession linearizes at `DELIVERY_CLAIMED`, not at delivery, and a cancelled operation can be handed a live permit

`DefaultFoliaBackpressure.java:507-525`. Under the lock, `deliverGrant` sets
`state = DELIVERY_CLAIMED` and releases; `lease.deliver(permit)` runs afterwards, outside the lock.
`cancel()` arriving in that window hits `case NEW, DELIVERY_CLAIMED, REJECTED -> {}` (line 473) and
does nothing. The consumer's `acquire()` stage therefore completes **normally, with a live Permit,
for an operation that is already cancelled** — the consumer must then discover the cancellation
itself and close the permit.

§3.6c item 3 is explicit: *"If a granted attempt is cancelled before grant delivery, the undelivered
grant is superseded by cancellation, its permit accounting is released exactly once, and delivery
reports cancellation rather than exposing a closed permit."* Delivery has not occurred at the claim.

The dev record flags this as an attack point and argues no user code runs at the claim — true, but
that is not the concern; the concern is which outcome the consumer observes. And the weaker point is
**not forced**: `ProducerLease.queueDelivery` is itself a single-shot atomic claim under
`lifecycleLock`. `cancel()` could linearize on the lease directly — attempt
`lease.deliverExceptionally(cancellation)`, and release the permit only if it returns `true` —
giving the exact §3.6c semantics with no lock held across delivery. Choosing the weaker point needs
either that argument rebutted or the clause amended.

### MAJOR-5 — a `RejectedExecutionException` from `scheduleWaiterService` permanently kills the waiter service

`DefaultFoliaBackpressure.java:585-590`

```java
if (!waiterSettlementScheduled && (...)) {
    waiterSettlementScheduled = true;
    CompletableFuture.runAsync(this::serviceWaiters);
}
```

The flag is set **before** the submission. `ForkJoinPool.scheduleDelayedTask`/`externalPush` throws
`RejectedExecutionException` when the pool is `SHUTDOWN` (`ForkJoinPool.java:3497-3505`), which
happens during JVM shutdown, and `runAsync` can also fail with `OutOfMemoryError`. Only
`serviceWaiters()` ever resets the flag (line 596). If the submission throws, the flag is stuck
`true` forever and **no further asynchronous waiter service is ever scheduled** for the lifetime of
the instance — every queued waiter beyond the first inline 128-batch then sits until its deadline
(or forever, given MAJOR-6). The throw also propagates out of `requestWaiterService` from under the
lock, into the caller's `try` (a region thread, in `close`/`enter`/`transfer`).

Set the flag only on successful submission, and roll it back in a `catch`.

### MAJOR-6 — `Demand.deadlineNanos` is never validated, so the only bound on waiter starvation is unenforced

Neither `FoliaBackpressure.Demand`'s compact constructor (`FoliaBackpressure.java:36-43`, which
validates `chunks` and `preparedBytes`/`finalizerChains` only) nor `validateDemand`
(`DefaultFoliaBackpressure.java:965-970`) constrains `deadlineNanos`. Yet the rotation comment at
lines 659-660 rests entirely on it:

> *"Rotation is work-conserving rather than strict FIFO. A feasible large demand may be bypassed,
> but every waiter has a finite deadline which bounds that starvation."*

A caller passing `Long.MAX_VALUE` (the natural "no deadline" spelling) gets a waiter that the
work-conserving rotation may bypass forever, occupying a `maxWaitersPerRegion` slot permanently and
tightening admission for that region for the rest of uptime. The starvation argument in the code is
an assumption about callers that the code does not enforce and no test covers.

Secondary, same line: `System.nanoTime()` may legally be negative. With `deadlineNanos =
Long.MAX_VALUE` and a negative `now`, `deadlineNanos - now` **overflows to negative**, so
`deadlineExpired` (line 962) returns `true` and `scheduleDeadline` (line 450-453) expires the waiter
immediately. So the same sentinel value means "never expires" on one platform and "expires
instantly" on another. Reject non-finite/sentinel deadlines at the SPI boundary, or clamp.

### MAJOR-7 — the injected `nanoTime` clock does not drive the deadline timer, making deadline behaviour untestable deterministically

`scheduleDeadline` (line 450) computes `delay` from the **injected** `nanoTime`, then hands it to
`CompletableFuture.delayedExecutor`, which uses the **real** clock. With any non-real injected
clock the two disagree and expiry fires at the wrong wall time. Consequence for review: the single
deadline test (`DefaultFoliaBackpressureTest.java:186`) must use a real 50 ms sleep, which is why
it can only observe expiry, never control its interleaving against a competing settlement.

### MINOR-1 — `serviceWaiters`' non-`QUEUED` branch silently leaks the waiter count

`DefaultFoliaBackpressure.java:627-630` removes a non-`QUEUED` waiter from the set without calling
`decrementWaiters`. I traced every reject/grant path (`removeQueued`, `cancel`, `expire`,
`failAttemptLocked`, the `serviceWaiters` reject and grant branches) and each removes-and-decrements
before setting a non-`QUEUED` state, so this branch appears dead today. But if it ever fires it
permanently inflates `globalWaiters` and `state.regularWaiters`/`continuationWaiters`, which blocks
`pruneIfIdle` forever (line 891-892) and permanently narrows `canQueue` for that region — a silent
slow leak. It should fail fast, not silently absorb.

### MINOR-2 — `transfer`'s `tryLock(100µs)` parks a region thread and is interrupt-sensitive

`tryLockForTransfer` (939-946) uses the timed, **interruptible** `ReentrantLock.tryLock`, which
parks. 100 µs on a region thread is defensible; note only that an interrupted region thread makes
`transfer` return `false` (correctly deferring, and correctly re-setting the interrupt flag), so an
interrupt storm degrades to permanent transfer refusal.

### MINOR-3 — common-pool workers are `InnocuousForkJoinWorkerThread`

`serviceWaiters` and `expire` run on innocuous common-pool threads with cleared `ThreadLocal`s and a
reset context classloader. Neither currently touches `FaweThreadContext` or any `ThreadLocal`, so
this is latent — but it means backpressure's internal accounting executes on threads that are, from
FAWE's and the architecture's point of view, nobody's: not FAWE workers, not owner threads, not
completion-service threads, and not thread-dump-assertable per §3.4's *"acyclic, bounded,
cancellable, thread-dump-assertable"*.

### MINOR-4 — `RegionKey` has no `equals`/`hashCode`

`RegionKey.java` is an empty final class, so the `ConcurrentHashMap<RegionKey, RegionState>` and
`previousRegion.equals(actualRegion)` (line 261) use identity. Already escalated as NEEDS_CONTEXT by
the worker and correctly so; flagged here only because `transfer`'s correctness (same-region
short-circuit vs. a real move) depends on it and the tests use `assertSame` (`:342`, `:345`), which
will silently need rewriting when task 14 lands value equality.

---

## Do the three failure modes that killed the two earlier attempts recur?

**1. "An inline rejection drain that could run on a TICK thread and block unboundedly on a private
completion lock."**

**Partially — in a recognizable, weaker form.** The private completion lock and the inline drain are
gone; I verified the class holds exactly one lock (`accountingLock`, line 69) and no queue,
executor, thread factory, tick detector, or fallback drainer remains. But: (a) `enter` and `close`
take that lock with an **unbounded-wait** `lock()` from region threads, while `transfer` bounds
itself to 100 µs — an inconsistency the code never justifies; (b) tick-thread settlement still runs
up to 128 waiter evaluations + 128 deliveries inline (MAJOR-2); (c) the cancellation path grafts the
whole cascade onto an arbitrary, plausibly-region thread with no executor (BLOCKING-1). The work was
moved off the rejection path and reappeared on the cancellation path.

**2. "A lock held across `future.complete(...)`, so user continuations ran inside the critical
section and deadline expiry queued behind them."**

**No — genuinely fixed.** I read all thirteen `accountingLock` critical sections individually. None
completes a future, invokes a lease `deliver`/`deliverExceptionally`, calls the rejection handler,
or invokes any caller-supplied `Consumer`/`BiConsumer`. Settlement produces immutable
`AdmissionDelivery` values; delivery happens strictly after `unlock()`; and the future completion
itself happens one further hop away, on an isolated notification virtual thread inside the
completion service. Both `whenComplete` sites were checked: `observeRejection` (line 1000) is on the
*isolated* `lease.future()` view, and I traced `CompletionServiceStage.whenComplete` to confirm it
forwards to `whenCompleteAsync(action, notificationExecutor)`. The cancellation `whenComplete`
(line 424) is registered outside the lock and its handler acquires the lock itself — no nesting.
This is the one thing the fresh implementation unambiguously got right.

**3. "Competing concurrent drainers with no single-drainer guard."**

**No — fixed, and correctly so.** There is only one drainer body (`serviceWaiterBatch`), and every
caller of it — inline `requestWaiterService` from any settling thread, and the async
`serviceWaiters` — holds `accountingLock` for its entire execution. The lock *is* the single-drainer
guard, and mutual exclusion is total. `waiterSettlementScheduled` correctly prevents duplicate
scheduling (subject to MAJOR-5's leak on submission failure). I specifically checked whether
`requestWaiterService`'s unconditional reset of `continuationScanRemaining`/`regularScanRemaining`
(lines 579-580) could starve the tail of a long queue against a concurrent async pass: it cannot —
`serviceWaiters` removes every examined waiter from the head and re-appends non-admittable ones to
the tail (`deferred`, line 658), so the queue genuinely rotates and each reset is paired with an
immediate 64-per-partition pass. Fairness is weakened (deliberately, documented) but not broken.

---

## Test quality verdict

**Insufficient to accept a 1222-line lock-based concurrent admission gate. There is not one
genuinely multi-threaded race test in the suite.**

Applying the skepticism requested — for each test claiming a race or liveness property, can it
actually FAIL on that property?

| Test | Claims | Can it fail on the claim? |
|---|---|---|
| `blockedConsumerDoesNotDelayDeadlineSettlement` (:166) | blocked consumer ⇏ delayed expiry | **No.** The blocking consumer runs on a virtual thread started by `startNotification`; it holds no backpressure lock and shares no executor with the common-pool `expire` task. There is **no mechanism by which it could fail**. It is guaranteed by `CompletionServiceStage`'s isolation — task 11's code, not task 13's. Valid as a regression guard against the *old* design; proves nothing about the current one. |
| `blockedRejectionHookDoesNotDelayAnotherAttempt` (:213) | blocked hook ⇏ blocked admission | **No.** Same reason — the hook is on the isolated stage and runs on its own virtual thread. Cannot fail. |
| `cancellationSupersedesUndeliveredGrant` (:248) | supersession race | **No.** The cancellation signal is `CompletableFuture.completedFuture(null)`, so `whenComplete` at line 424 runs **synchronously on the test thread**, before `deliverAll` at line 432 has ever executed. The entire test is one deterministic sequential path. The `DELIVERY_CLAIMED` linearization point (lines 507-525) — the exact mechanism under review and the exact thing the dev record flags as an attack point — is **never exercised against a concurrent cancel**. Zero coverage of the named race. |
| `queuedCancellationSettlesAndInvokesHook` (:412) | cancellation of a queued waiter | Partly — it exercises the `QUEUED` branch, but again with no concurrency: `cancellation.complete(null)` happens on the test thread with no competing settlement. |
| `tryAcquireReturnsWhileAccountingIsBusy` (:373) | `tryAcquire` non-blocking under contention | **Yes — this one is real.** The injected `nanoTime` blocks inside `grant()` *while holding `accountingLock`* (verified: `deadlineExpired` is call #1, `tryLock` succeeds, `grant`'s `nanoTime` is call #2 under the lock). A second caller's `tryAcquire` must return within 1 s. Change `tryLock()` to `lock()` and this test hangs and fails. Good test — but it covers only `accountingLock`; it does not cover `lifecycleLock` (BLOCKING-3) or the virtual-thread start. |
| `stopSettlesMoreThanOneWaiterBatch` (:487) | settlement crosses the 64-batch boundary | **Yes.** 100 waiters vs. `WAITER_SERVICE_BATCH` 64; if the async re-arm (`scheduleWaiterService`) were broken, ~36 waiters would never settle and the joins would hang. Good test. |
| `completionPreflightFailsBeforeAccounting` (:128) | preflight precedes accounting | **Yes**, and it also asserts `outstandingProducerLeaseCount() == 0` — a real no-leak assertion. Good test. |
| `tryAcquireMissIsNotATerminalRejection` (:144) | miss ⇏ rejection hook | **Yes**, deterministic and meaningful. |
| remaining 6 (`exposesInitialCertificationLimits`, `asyncAcquireUsesIsolatedNotification`, `permitStagesAdvanceInOrderAndCloseIsIdempotent`, `continuationCapacityIsCarvedOutOfFinalizers`, `transferRefusesFullTargetWithoutMovingAccounting`, `idleRegionsAreEvictedAfterTransfer`) | single-threaded functional assertions | Yes, and they are fine — but they are not concurrency tests and were never claimed to be. |

So: **3 of the 4 tests carrying liveness/race names cannot fail on the property they name** — the
same pattern found in the sibling task's review. The dev record's "the standalone 15-test suite
passed five consecutive runs" is therefore not acceptance evidence for the concurrency claims.

**Uncovered, all of it in-lens and all of it cheap to write:**

- `deliverGrant` claiming vs. a concurrent `cancel` — the actual §3.6c item-3 race (needs two
  threads and a barrier inside `deliverGrant`, e.g. via the injected clock).
- Any settlement path reached from a simulated tick thread with a work/allocation budget assertion
  (clause 4's "cheap, lock-bounded" is asserted nowhere).
- `deliverAll` throwing mid-batch — the BLOCKING-2 scenario. A single test that injects a failing
  lease and asserts the remaining attempts still settle would have caught it.
- `service.flush()` racing a granted-but-undelivered waiter (the `expireFlush` vs. `deliverGrant`
  interleaving).
- Deadline expiry under a saturated common pool.
- Any test at all with more than two threads, or with `Thread.onSpinWait` contention loops driving
  the state machine.

---

## Dev-record accuracy

Checked each material claim in the "Fresh implementation (§3.6c re-plan)" record against the file:

- **"removed the private completion executor, completion lock/queue, tick detector, fallback
  drainer/thread factory, inline drain, and shutdown join. No notification thread, executor, or
  ordering lock remains… only `accountingLock` serializes settlement."** — **Accurate.** Confirmed by
  full-file read.
- **"Clause 1: … They collect immutable `AdmissionDelivery` values and never invoke hooks or complete
  futures while locked."** — **Accurate.** Independently verified across all thirteen critical
  sections.
- **"Clause 2: … the rejection hook is installed on the service-isolated lease stage."** —
  **Accurate**, traced through `CompletionServiceStage:239-241`.
- **"Clause 4: transfer, FINALIZING entry, and close settle synchronously under the bounded accounting
  critical section, unlock, then enqueue any waiter deliveries."** — **Materially incomplete.** True
  as far as it goes, but it omits (a) that `enter`/`close` use an unbounded `lock()` while `transfer`
  bounds itself, (b) that the "bounded" critical section includes up to 128 inline waiter
  evaluations, and (c) that `cancel` — not listed — reaches the same cascade from an arbitrary
  caller-chosen thread.
- **"Clause 5: … tests … prove a forever-stalled earlier consumer does not block expiry settlement."**
  — **Overstated.** The test cannot fail on that property (see above). "Does not observe a failure"
  ≠ "proves".
- **"Recheck common-pool continuation of capped waiter-settlement scans. It performs accounting only,
  never notification/user work."** — **Accurate as far as it goes, and creditably self-flagged**, but
  it discusses only line 588 (`serviceWaiters`) and is silent on line 455 (`expire`), which is the
  one that matters for the property the whole re-plan existed to protect.
- **"outstanding producer leases keep completion flush fenced until every batch settles"** —
  **Accurate in the intended path, but not robust:** BLOCKING-2 shows a lease can be abandoned
  undelivered, in which case the fence holds forever rather than "until every batch settles".
- **Escalations** (`RegionKey` identity; the preflight↔`OperationCompletion.register` seam) —
  **accurate and correctly raised.** The second one is precisely the r10 gap below; the worker was
  right to escalate rather than manufacture a registration key.

Overall: the record is honest about what was rebuilt and self-flags two of the three areas I found
problems in. Its weakness is treating "the test passed" as "the property is proved".

---

## What the r10 ruling breaks (per instruction: not faulting the pre-r10 call shape)

Per the brief I do not fault `tryAcquireLease(attemptId)` being one-arg. But the r10 change is
**not mechanical** here, and two consequences are load-bearing:

1. **Delivery is hard-gated on a registration that backpressure never performs.**
   `ProducerLease.queueDelivery` (`OperationCompletionService.java:875-878`) throws
   `IllegalStateException("Producer-registration capability is not consumed")` unless
   `registerProducer(lease, planKey, flushExpiryHook, transitionFailure)` ran first. Backpressure
   never calls it. Under r10, **every** `lease.deliver`/`deliverExceptionally` in this file — grant
   (218, 518), rejection (208, 1053), expiry, cancellation — throws. Combined with BLOCKING-2's
   missing exception isolation, the first delivery of the first batch throws and everything
   downstream is dead. Migration requires task 13 to obtain a `planKey`, a `FlushExpiryHook`, and a
   `Consumer<Throwable>` — and the worker's own escalation correctly states that `Demand` carries no
   chunk key or plan sequence from which a `planKey` can be manufactured. This is a design seam, not
   a rename.

2. **BLOCKING-3 becomes structurally untenable, not merely expensive.** Under r10, taking a lease per
   `tryAcquire` means **consuming a producer-registration capability per owner-thread fast-path
   miss** — i.e. registering a completion producer, with a flush-expiry hook, for every G-A1 miss.
   That is not a viable shape at any scale. The lease must become lazy (acquired only when a grant is
   about to be issued) as part of the r10 alignment, which is a change to the admission flow, not a
   call-site edit.

3. **No flush-expiry hook exists for admission waiters.** §3.6b r10 Q1: *"No registered plan or
   admission-open coordinator may lack this hook."* Backpressure's queued waiters are exactly
   outstanding admission attempts. Today `expireFlush` delivers a `TimeoutException` to their leases
   (`OperationCompletionService.java:581-591`), which covers *notification* but not backpressure's
   *internal* settlement: the waiter stays `QUEUED` in `regularWaiters`, its region counters stay
   incremented, its region is never pruned, and any later `expire`/`cancel` will log "already
   claimed". The r10 alignment must add a hook that settles the waiter set, not just deliver to it.

4. **Lock ordering to re-check during alignment.** Today there is no `accountingLock` ↔
   `lifecycleLock` inversion: backpressure always releases `accountingLock` before touching the
   service, and the service never calls into backpressure under `lifecycleLock`. Once a
   `flushExpiryHook` exists, it will run on the control executor under `serializationLock` and will
   want `accountingLock`, while backpressure's `deliverAll` inside it takes `lifecycleLock`. The
   resulting order (serialization → accounting → lifecycle) is consistent with the service's own
   (`runInline`: serialization → lifecycle), so it should hold — but it must be asserted, not
   assumed, when the hook lands.

---

## What I verified and could NOT fault

Stated explicitly so the next reviewer does not re-spend budget here.

- **No lock held across any user-visible completion.** All thirteen `accountingLock` critical
  sections read individually. No `future.complete`, no `lease.deliver`, no `rejectionHandler`, no
  caller-supplied `Consumer`/`BiConsumer`, no blocking wait inside any of them. The single defect
  that terminated attempt #2 is genuinely eliminated.
- **All ten `deliverAll` call sites follow `accountingLock.unlock()`** — re-verified independently
  of the orchestrator's pre-check, including the non-obvious one at `acquire` (unlock in the
  `finally` at 420, `deliverAll` outside the try at 432).
- **Both `whenComplete` sites traced to their execution context.** `observeRejection` (1000) is on
  the isolated stage → notification virtual thread → the rejection hook can never run on a
  settlement thread. (The cancellation `whenComplete` at 424 is BLOCKING-1, but its *handler* also
  correctly acquires the lock itself rather than assuming one is held.)
- **No re-entrancy from delivery back into a held lock.** `deliverGrant` and `lease.deliver` always
  execute with `accountingLock` released; the reentrant `close()` in `deliverGrant`'s catch (522)
  and the `deliverAll` recursion (526) both re-acquire cleanly. `accountingLock` is a
  `ReentrantLock`, so even an unexpected reentry cannot self-deadlock. (The recursion is MAJOR-3 for
  depth/work, not for deadlock.)
- **Single-drainer discipline is sound.** `serviceWaiterBatch` has exactly one body and every caller
  holds the lock for its full execution. Attempt #3's competing-drainer defect does not recur.
- **The waiter rotation genuinely rotates.** I traced the head-removal / `deferred` tail-reappend
  interaction with `scanRemaining` resets and confirmed the tail is not starved by a stream of
  inline `requestWaiterService` calls. (The residual issue is MAJOR-6: the *fairness* argument rests
  on deadlines that are never validated.)
- **No waiter-count or region-state leak in the `deferred` window.** The window where a waiter is out
  of `regularWaiters`/`continuationWaiters` lies entirely inside the lock (removal at 648, re-add at
  658), so a concurrent `cancel`/`expire` cannot observe it and double-decrement. I specifically
  looked for this and it is correct.
- **`pruneIfIdle` is conservative and cannot evict live state.** It requires `livePermits == 0`, all
  four reserved counters zero, all four observable counters zero, both waiter counts zero, and
  `readySince` empty (881-897), and `regions.remove(region, state)` is identity-checked. Queued
  waiters keep their region's count non-zero, so the eviction-vs-live-waiter hazard I was asked to
  hunt does not exist. `transfer` correctly prunes the source only after all accounting has moved
  (273-275).
- **Permit accounting is released exactly once on every terminal path.** `release` is guarded by
  `permit.closed` (831), sets it (853), and every caller — `close`, the invalid-transition branch of
  `enter`, `cancel`, `deliverGrant`'s supersession branch, `failAttemptLocked` — reaches it under
  the lock. The `globalReadyHeld`/`globalFinalizersHeld`/`readyCapacityHeld` flags are individually
  one-shot. I could not construct a double-release or a lost release on any non-exceptional path.
- **`recordScheduleDelay`/`recordSlice` are correctly non-blocking** (`tryLock`, drop on contention,
  no state creation for absent regions) — appropriate for owner-thread telemetry.
- **`stopAccepting` is idempotent and fences both admission entries** (`initialRejection` at 437 for
  `acquire`, line 183 for `tryAcquire`), both under the lock.
- **`pressure()` is genuinely lock-free** over `volatile` fields, correctly documented as
  non-atomic (295), and returns `ZERO_PRESSURE` for absent regions without creating state.
- **The continuation carve-out is subtractive, not additive** (119-127): `regularFinalizerLimit =
  maxFinalizersPerRegion - continuationFinalizerLimit`, with the same treatment for waiter slots and
  global finalizers, plus constructor validation that the carve is strictly inside the limit. This
  satisfies the mandate's explicit "carved out of (not added to)" requirement, and
  `continuationCapacityIsCarvedOutOfFinalizers` (:304) asserts it correctly.
