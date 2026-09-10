# Task 13 cross-review — admission correctness lens

**Reviewer:** fresh independent adversarial thread (Opus). No prior context on this task.
**Lens:** accounting integrity, permit lifecycle, resource bounding, no-silent-loss.
**Scope read in full:** `DefaultFoliaBackpressure.java` (1222 l), `DefaultFoliaBackpressureTest.java`
(630 l), `FoliaBackpressure.java`, `RegionKey.java`, `OperationCompletionService.java` (912 l),
architecture §3.4 / §3.6c / §3.7, spec §8, task file `13-backpressure.md` incl. all four dev records.
Concurrency/liveness/§1b is covered by a second reviewer; crossovers are marked but not developed.

---

## VERDICT

**REJECT** — two BLOCKING findings.

1. `deliverAll` is not exception-isolated: one failing delivery abandons every remaining
   already-settled outcome in the same batch, leaking their permits permanently and hanging
   their operations. Reachable on the ordinary shutdown-flush path.
2. Continuation admissions bypass **all** byte and ready-chunk accounting with no validation and
   no linkage to the reservation they claim to reuse. The 64 MiB/region and `min(1 GiB, maxHeap/4)`
   global byte caps do not apply to them. This is a hole in the resource the class exists to bound.

Neither is in the completion seam that the two prior attempts died on. Both are in code the worker
was told was "proven" and could be salvaged.

---

## Was the salvage ruling safe?

**No — not fully.** The ruling was reasonable in shape (the permit lifecycle and transfer machinery
really are good) but wrong in its premise that the salvaged code had been reviewed. It had not: the
prior reviews were scoped to the notification/completion seam, so the accounting itself entered this
attempt with essentially zero adversarial coverage, and it is carrying real defects.

Imported by the salvage, not introduced by the §3.6c rebuild:

| Finding | Origin | Salvage-imported? |
|---|---|---|
| B2 continuation byte/chunk bypass | `grant()` lines 542–560, `canAdmitContinuation` 708–716 | **yes** |
| M1 global continuation ceiling = 64 server-wide | constructor lines 123–127 | **yes** |
| M2 `MAX_GLOBAL_WAITERS` shared across partitions | corrective-1 "N4", line 53 / 681 | **yes** |
| M4 EWMA `tryLock`-drop, `chunks` discarded | corrective-1 "N1", lines 309–347 | **yes** |
| m1 waiter-count leak on non-QUEUED removal | line 627 | **yes** |
| m2 duplicate `ready*`/`reserved*` counters | `RegionState` | **yes** |
| B1 `deliverAll` abort | §3.6c rebuild, line 1049 | no |

What the salvage got **right**, and I could not fault after direct attack:

- **`transfer` atomicity.** Lines 252–282 are correct in a way that is easy to get wrong. The whole
  move is under one lock acquisition; `moveRegionalAccounting` → `permit.region = actualRegion` →
  `pruneIfIdle(previousRegion, ...)` is the only safe order and it is the order used. Refusal
  (`!canTransfer`, closed permit, lock timeout) returns `false` with the permit's region field
  untouched and zero counters moved. I could not construct a partial transfer, a both-charged, or a
  neither-charged state. `livePermits` is decremented on `previous` and incremented on `target`
  inside the same critical section, so the old region cannot be pruned out from under a live permit.
- **Same-region transfer** short-circuits to `true` before touching anything (line 261).
- **`pruneIfIdle` predicate is complete** (lines 881–897): it checks all eleven accounting fields
  plus `readySince.isEmpty()`. I diffed it against every field of `RegionState` — nothing gating is
  omitted, and only the EWMA fields are deliberately excluded (documented). `regions.remove(region,
  state)` is the identity-checked two-arg form, so a recreated region cannot inherit stale state.
- **Stage machine** (lines 775–813). The `next.ordinal() != stage.ordinal() + 1` test is exactly
  right, invalid transitions `release()` before throwing so nothing leaks, and `close()` is
  genuinely idempotent via the `closed` flag under the lock.
- **`releaseReadyCapacity` flag discipline.** `enter(FINALIZING)` clears `readyCapacityHeld` and
  `globalReadyHeld`, so the later `close()` cannot double-release. Same for `globalFinalizersHeld`.
  I traced every grant→release pairing and found no double-release.
- **Per-region carve-out is genuinely subtractive**, and so are the waiter partitions:
  `regularFinalizerLimit = maxFinalizersPerRegion − continuationFinalizerLimit` (line 119),
  `regularWaiterLimit = maxWaitersPerRegion − continuationFinalizerLimit` (line 122). Regular
  admission reads only `reservedRegularFinalizers`; continuation reads only
  `reservedContinuationFinalizers`. Regular work can never consume continuation capacity at the
  per-region level.

---

## §3.4 conformance (my clauses)

| Clause | Status |
|---|---|
| SPI methods present & signatures unchanged | PASS |
| Certification values in `initialLimits` | PASS — 256 / 64 MiB / 64 / 256 / 65 536 / `min(1 GiB, maxHeap/4)` / 4 096, exact (lines 133–146), asserted by test |
| Continuation budget carved out, not added; no separate `Limits` field | PASS per-region; no field added |
| Per-region limits do not replace the global byte cap | PASS for regular (`canAdmitRegular` checks both, lines 694–706); **FAIL for continuations** (B2) |
| Permit lifecycle READY→SCHEDULED→COMMITTING→FINALIZING→close | PASS |
| Invalid transition terminates the plan exceptionally | PASS (with MINOR m3) |
| `close()` idempotent, releases all remaining accounting | PASS |
| `transfer` moves accounting before mutation; refuses cleanly | PASS |
| Saturation → rejection before acceptance | PASS in accounting; **delivery is not guaranteed** (B1) |
| Full waiter queue / expired deadline → terminalized `NOT_ACCEPTED` hook | PASS mechanically (`observeRejection`, line 999) — exactly-once because it hangs off a `CompletableFuture` that completes once |
| Bounds are real (no unbounded map/queue/collection) | **FAIL** — see B2 (bytes) and B1 (lease-map growth on the abandoned path) |
| `pressure()` fields truthful | mostly — see B2 (`readyBytes` under-reports) and m4 (`finalizerChains`) |

## §3.6c conformance (my clauses)

- **Clause 1 — settlement never completes futures or invokes hooks under the lock: VERIFIED TRUE.**
  I checked every one of the eight sites that build a `deliveries` list. In all of them
  `accountingLock.unlock()` textually precedes `deliverAll(...)` inside the same `finally`
  (279–280, 360–361, 477–478, 493–494, 599–601, 806–808, 824–826, 1021–1023), and `acquire` calls it
  outside the try entirely (432). `deliverGrant` re-takes the lock only to claim, then delivers after
  unlocking (501–525). This clause is genuinely discharged and the dev record's claim is accurate.
- **Clause 2 — lease preflight before accounting: PASS in ordering** (373 precedes 387). The
  `registerProducer(lease, …)` step that r10 introduced is absent; per the brief I do not fault the
  call shape, but see "State of tree" below for what it means for the acceptance criteria.
- **Clause 3 — attempt identity & supersession: PASS, and better than it looks.**
  `AdmissionAttemptId` deliberately has no `equals`/`hashCode`, so identity equality keys the shared
  service's `producerLeases` map — two `DefaultFoliaBackpressure` instances sharing one completion
  service cannot collide. A record-with-long would have thrown "already holds a delivery lease".
  The `DELIVERY_CLAIMED` linearization is correct: I enumerated all three cancel/deliver interleavings
  and each yields exactly one outcome with the permit released exactly once.
- **Clause 4 — tick-thread settlement enqueues under the *waiter's* pre-acquired lease: PASS.**
  `close`/`enter(FINALIZING)`/`transfer` settle synchronously and hand deliveries to each waiter's own
  lease. (Crossover: those same paths can start up to 128 virtual threads from a tick thread —
  reviewer 2's lane.)

---

## Findings

### BLOCKING

#### B1 — `deliverAll` abandons every remaining settled outcome after one delivery failure
`DefaultFoliaBackpressure.java:1049-1057`, with `DefaultFoliaBackpressure.java:516-525`

```java
private void deliverAll(List<AdmissionDelivery> deliveries) {
    for (AdmissionDelivery delivery : deliveries) {
        if (delivery.failure() == null) {
            deliverGrant(delivery.waiter());          // <-- can throw
        } else if (!delivery.waiter().lease.deliverExceptionally(delivery.failure())) {
            LOGGER.error(...);                        // <-- correctly tolerant
        }
    }
}
```

The rejection branch tolerates a lost race (returns `false`, logs, continues). The **grant** branch
does not: `deliverGrant` converts a `deliver()` that returns `false` into a thrown
`IllegalStateException` (line 519), closes only *its own* permit, and rethrows (521–524). There is no
per-item `try`/`catch`, so the loop dies and every later entry in the batch — all of them already
settled in accounting, removed from the waiter queue, counters already decremented — is silently
dropped.

**Concrete sequence (shutdown drain, no exotic conditions required):**
1. Region R is saturated. Waiters W1, W2, W3 are QUEUED with live producer leases.
2. Shutdown calls `OperationCompletionService.flush(d)`. At the deadline, `expireFlush`
   (`OperationCompletionService.java:569-596`) sets `deliveryQueued = true` on every outstanding
   lease and publishes a `TimeoutException`.
3. An in-flight permit closes → `close()` → `requestWaiterService` → `serviceWaiters` grants W1 and
   W2 (`initialRejection` is null: `stoppedReason` is unset and their deadlines have not passed).
   Both are charged: `deliveries = [granted(W1), granted(W2)]`.
4. `deliverAll` → `deliverGrant(W1)` → state `DELIVERY_CLAIMED` → `lease.deliver(permit)` returns
   `false` (`queueDelivery` short-circuits on `deliveryQueued`, `OperationCompletionService.java:868`)
   → ISE → `W1.permit.close()` → **rethrow**.
5. `deliverGrant(W2)` never runs. W2 is `GRANTED`, its permit is fully charged against R
   (`reservedReadyChunks`, `reservedReadyBytes`, `reservedRegularFinalizers`, `globalReadyChunks`,
   `globalReadyBytes`, `globalFinalizers`, `livePermits`), and the only reference to it is
   `Waiter.permit`, which is private and now unreachable. **Nothing will ever close it.**
   R's `livePermits != 0` forever → the region entry never prunes → its capacity and a slice of the
   global caps are permanently consumed. `stopAccepting` does not touch granted waiters, so there is
   no recovery path.
6. The ISE also propagates out of `Permit.close()` — an `AutoCloseable` throwing from a caller's
   `finally`.

Same abort is reachable from `transfer`, `enter`, `expire`, `cancel`, `stopAccepting` and the async
`serviceWaiters`, since all of them call `deliverAll` with multi-entry lists.

Secondary consequence: an abandoned waiter's lease is never released from
`OperationCompletionService.producerLeases`, so `terminateIfQuiescent`
(`OperationCompletionService.java:607-617`) can never fire — **shutdown never completes**, and the
`terminate()` invariant assertion at line 620 is the only thing that would ever surface it.

Fix is narrow: wrap each iteration in `try`/`catch (Throwable)`, log, continue; and treat
`deliver() == false` on the grant path as "supersede + close + continue", not as a throw.

#### B2 — continuation admissions bypass all byte and ready-chunk accounting, unvalidated
`DefaultFoliaBackpressure.java:542-560`, `:708-716`, `:228-242`

```java
boolean globalReservation      = kind == AdmissionKind.REGULAR;
boolean readyCapacityReservation = kind == AdmissionKind.REGULAR;
// A continuation reuses the accepted plan's global/ready reservation and only draws
// from the finalizer capacity which regular admissions cannot consume.
```

For `CONTINUATION`, `grant()` adds **nothing** to `readyBytes`, `reservedReadyBytes`,
`globalReadyBytes`, `readyChunks`, `reservedReadyChunks` or `globalReadyChunks`, and
`canAdmitContinuation` (708–716) checks **only** finalizer chains. `acquireContinuation` validates
`previouslyAccepted()` and `finalizerChains() > 0` — proving the author knew where the continuation
preconditions belong — but does not validate `preparedBytes` or `chunks`.

The comment asserts a precondition the code never establishes. `Demand` carries an `operationId`
that the implementation never reads; there is no link from a continuation to the permit whose
reservation it claims to reuse, no check that such a permit still exists, and no check that the
continuation's bytes are within it.

**Concrete sequence — this is the *normal* path, not an abuse:**
1. Caller acquires a regular permit for region R: `chunks=16`, `preparedBytes=64 MiB`. Charged.
2. `enter(SCHEDULED)` → `enter(COMMITTING)` → `enter(FINALIZING)`. Line 799 calls
   `releaseReadyCapacity`, which **releases all 64 MiB** from `readyBytes`, `reservedReadyBytes` and
   `globalReadyBytes`. This is exactly what §3.4 specifies for FINALIZING.
3. FINALIZING is precisely when §3.4 says a continuation is taken (entity-phase resume, light
   materialization, neighbor settlement). Caller calls `acquireContinuation(R, demand(chunks=16,
   preparedBytes=64 MiB, previouslyAccepted=true, finalizerChains=1))`.
4. Admitted. Zero bytes accounted. The reservation it "reuses" was released one step earlier.
5. Repeat across `continuationFinalizerLimit` chains per region and across regions up to the global
   continuation ceiling (M1 puts that at 64): **4 GiB of prepared bytes entirely outside the
   `min(1 GiB, maxHeap/4)` global cap**, and `preparedBytes` is caller-supplied so the per-chain
   figure is unbounded.
6. `pressure(R).readyBytes()` reports 0 throughout. Task 14's `DiagnosticSnapshot.readyBytes` is
   therefore false, and an operator watching the `FAWE_QUEUE` extended line sees no memory pressure
   while the heap fills.

If the co-signed intent is "a continuation carries no prepared payload", then a validation guard is
**mandatory** — nothing else in the system enforces it, `previouslyAccepted` is an unenforced
caller-supplied boolean, and tasks 14/16/17 (the callers) do not exist yet to be constrained by
convention. If the intent is that continuations may carry payload, the accounting is simply missing.
Either way this is a hole in the sole gate that spec §8 relies on for bounded resources.

Related, same root: continuation `chunks` are excluded from `readyChunks` but **added** to
`scheduledChunks` on `enter(SCHEDULED)` (line 793), so `scheduledChunks` — a `volatile int` feeding
`Pressure.scheduledDrains` and hence `inflight=` — accumulates a quantity that no cap bounds.

### MAJOR

#### M1 — global continuation finalizer ceiling is one region's worth, server-wide
`DefaultFoliaBackpressure.java:123-127`

```java
this.continuationGlobalFinalizerLimit = Math.min(
        limits.maxFinalizersPerRegion(),          // 64
        limits.maxGlobalFinalizers() - 1);        // 4095
this.regularGlobalFinalizerLimit = limits.maxGlobalFinalizers() - continuationGlobalFinalizerLimit;
```

With the certification values this yields **64 continuation chains for the entire server** against
4 032 regular chains. §3.4 specifies the carve-out per region and says nothing about a global
continuation partition; the `min(maxFinalizersPerRegion, …)` derivation is invented here, is
undocumented, and has no stated basis.

**Break:** with `continuationFinalizerLimit = 16`, four saturated regions consume the entire global
continuation budget. Region 5's continuation — required to *release* work that has already been
accepted and is holding finalizer capacity — cannot be admitted, queues, and is bounded only by its
deadline. When the deadline fires it is rejected, so already-accepted work fails. This reintroduces
at global scale the exact failure mode ("finalizer continuations queueing behind the commits they
unblock") that F4 mandated the carve-out to eliminate. The per-region carve is correct; the global
one defeats it.

#### M2 — `MAX_GLOBAL_WAITERS` is shared across the two waiter partitions
`DefaultFoliaBackpressure.java:53`, `:680-683`

```java
private static final int MAX_GLOBAL_WAITERS = 65_536;
...
private boolean canQueue(RegionState state, AdmissionKind kind) {
    if (globalWaiters >= MAX_GLOBAL_WAITERS) {
        return false;                      // <-- applied before the kind check
    }
```

The per-region waiter partition is carefully disjoint (`continuationWaiterLimit` vs
`regularWaiterLimit`, separate `LinkedHashSet`s, continuations serviced first in
`serviceWaiterBatch`). The global gate throws that away: 65 536 **regular** waiters — reachable at
~256 saturated regions × 255 regular slots — make every continuation on every region fail
`canQueue`, so continuations cannot even enter the queue and are rejected outright with
`RejectedExecutionException("backpressure admission waiter limit reached")`. Same class of defect as
M1: the carve-out is honored per-region and abandoned globally. It needs the same subtractive split.

Secondary: 65 536 is a hardcoded constant, not a `Limits` field and not config-injected, so it is not
tracked as a `[NEEDS-RUNTIME]` slot for the W1-exit numeric freeze.

#### M3 — the `tryAcquire` miss path is the expensive path
`DefaultFoliaBackpressure.java:165-226`

Every `tryAcquire` — including every miss — allocates an `AdmissionAttemptId`, takes
`OperationCompletionService.lifecycleLock` to acquire a lease (line 169), and on refusal calls
`lease.deliverExceptionally` (line 208), which re-takes `lifecycleLock`, reserves a publication, and
**starts a virtual thread** (`startPublication` → `startNotification` → `Thread.ofVirtual().start`).

`tryAcquire` is the only admission entry legal from a tick thread, and G-A1 uses it exclusively. Under
saturation — the condition it exists for — misses are the common case, so the drain loop pays a lock
round-trip plus a thread start per rejected candidate chunk, on the owner thread. Each miss also
increments `pendingOutcomePublications`, which participates in shutdown quiescence.

This is arguably forced by §3.6c clause 2 ("lease preflight … before backpressure accounting"), and
the dev record already classifies a miss as non-terminal (no rejection hook). If a miss is
non-terminal, it plausibly does not need a lease at all — but that cannot be known before the
accounting check, which preflight must precede. **This is an architectural tension worth escalating
rather than a worker fault**, but it is a real per-tick cost on the hot path and I am flagging it as
MAJOR because nothing currently bounds how often it is paid. Crossover with reviewer 2.

#### M4 — the per-region signals degrade exactly under load
`DefaultFoliaBackpressure.java:309-347`

Both `recordScheduleDelay` and `recordSlice` use `accountingLock.tryLock()` and **silently discard the
sample** on contention. C6 makes these per-region signals the replacement for global TPS, and §3.7
feeds them to the adaptive slice controller ("halve when schedule delay > 75 ms or a slice exceeds
1.5 ms"). The accounting lock is hottest precisely when the region is saturated, so the EWMA is
systematically biased toward low-contention samples and the controller is least likely to see the
excursions that should trigger a halving. Corrective-1 "N1" introduced this deliberately to make the
telemetry non-blocking; the non-blocking goal is right, the drop-on-contention mechanism is not
(a lock-free accumulator or a per-region striped counter would give both).

Separately, `recordSlice(region, chunks, runtimeNanos)` validates `chunks` and then **discards it**
(line 342 uses only `runtimeNanos`). The SPI promises a per-chunk signal path that is not retained, so
task 14 cannot derive per-chunk cost from `pressure()` and must re-measure it.

### MINOR

- **m1 — latent waiter-count leak.** `DefaultFoliaBackpressure.java:627-630`: the `state != QUEUED`
  branch does `iterator.remove(); continue;` without `decrementWaiters`. I traced every state
  transition and believe the branch is currently unreachable (every path that leaves QUEUED calls
  `removeQueued` first). But if a future edit reaches it, `globalWaiters` and the region's waiter
  count inflate permanently → `pruneIfIdle` never fires → the region entry and its waiter slots leak
  for the process lifetime. It should assert or decrement, not silently drop.
- **m2 — `readyChunks`/`reservedReadyChunks` and `readyBytes`/`reservedReadyBytes` are provably
  identical.** They are incremented together in `grant` (546–549, 561–565) and decremented together in
  `releaseReadyCapacity` (857–865) and `moveRegionalAccounting` (740–751). The reserved/ready
  distinction is vestigial. Admission reads `reserved*`; `pressure()` reads the other. A future
  one-sided edit breaks admission while the diagnostics keep looking correct — a bad failure mode for
  the sole resource gate.
- **m3 — `deliverAll` in `finally` can mask the transition failure.** `enter()` (806–812) computes
  `transitionFailure` and throws it *after* the `finally`. If `deliverAll` throws inside that
  `finally`, the `IllegalStateException` describing the invalid transition is lost and the caller sees
  an unrelated delivery failure. Same shape makes `close()` and `transfer()` throw. Compounded by B1.
- **m4 — `Pressure.finalizerChains` reports `activeFinalizers`, not the gating counter.** Admission
  gates on `reservedRegularFinalizers` / `reservedContinuationFinalizers` (both charged from grant);
  `pressure()` (line 299) reports `activeFinalizers`, which only counts FINALIZING-stage permits. The
  §3.4 `FAWE_QUEUE` mapping (`inflight = scheduledDrains + finalizerChains`) reads naturally as a
  partition, which the implementation's choice satisfies — but it means neither task 14 nor an
  operator can see how close a region is to `maxFinalizersPerRegion`, which is the constraint that
  actually rejects work. Needs a task-14 co-sign either way.
- **m5 — `scheduledChunks` / `readyChunks` / `activeFinalizers` are `volatile int`.** Regular
  admission bounds them, but continuation `chunks` flow into `scheduledChunks` unbounded (see B2), so
  overflow is theoretically reachable and would corrupt `inflight=`.
- **m6 — `stopAccepting` shares one `Throwable` instance** across every rejected waiter (line 356 →
  `initialRejection` returns `stoppedReason` directly), so every operation's failure carries the same
  stack trace. The test asserts `assertSame(stopped, failure.getCause())`, locking the behavior in.
- **m7 — `transfer` conflates "busy" with "cannot admit".** `tryLockForTransfer` (939–946) returns
  `false` after 100 µs, and `transfer` returns `false`, which the SPI documents as "the target region
  cannot admit it now". The direction is safe (the plan defers before mutation) but the caller cannot
  distinguish a transient lock miss from a real capacity refusal, and §3.7 says a plan that cannot
  settle its transfer before the deadline terminates.
- **m8 — hardcoded numeric slots not tracked for the W1-exit freeze:** `MAX_GLOBAL_WAITERS` (53),
  `WAITER_SERVICE_BATCH` = 64 (52), `TRANSFER_LOCK_TIMEOUT_MICROS` = 100 (54), `EWMA_WEIGHT_SHIFT` = 3
  (51), and the derivation of `continuationGlobalFinalizerLimit`. The dev record claims "all seven
  public `Limits` slots and the internal `continuationFinalizerLimit` carve remain injected", which is
  true but incomplete — these five are neither injected nor listed.
- **m9 — asymmetric constructor validation.** Line 107 requires `continuationFinalizerLimit <
  maxFinalizersPerRegion` (strict) but line 113 requires `<= maxWaitersPerRegion` (non-strict). At
  equality, `regularWaiterLimit == 0`, so `canQueue` returns `false` for every regular admission and
  **no regular acquire can ever queue** — every saturated regular request is rejected immediately.
  Accepted silently.

---

## `RegionKey` — is the assumed minimal contract sound?

`RegionKey.java` is an empty `public final class` with an implicit no-arg constructor and **no
`equals`/`hashCode`** — i.e. identity equality today. The implementation relies on it as a
`ConcurrentHashMap` key (line 70) and calls `previousRegion.equals(actualRegion)` (line 261).

The contract this code actually needs, which task 14 must satisfy:

1. **Value-based `equals`/`hashCode`**, canonical per logical region.
2. **Immutable, with a hash stable for at least the lifetime of any outstanding permit or waiter.**

Neither is guaranteed today, and the in-tree comment — *"derived from live Folia ownership at drain
time"* — points at both hazards:

- **If task 14 mints a fresh `RegionKey` per drain** (which the comment invites), identity equality
  makes every drain a *different* region. `pressure(region)` returns `ZERO_PRESSURE` for any key the
  caller did not itself use to admit; more seriously, `canAdmitRegular` looks up `regions.get(region)`
  and finds nothing, so **every per-region cap degenerates into a per-attempt cap** and the 256-chunk
  / 64 MiB bounds stop existing. The global caps would be the only surviving bound. This would be the
  single most damaging outcome and it is not hypothetical given the comment.
- **If task 14 makes `RegionKey` mutable** (e.g. it holds the observed region identity, updated on
  merge/split), the hash changes while the object is a live map key. `pruneIfIdle`'s
  `regions.remove(region, state)` then silently fails to locate the entry, and the `regions` map
  leaks one entry per region churn for the process lifetime — an unbounded map on a long-running
  server, which is exactly the failure class spec §8 forbids.

`transfer` itself is safe under either choice (it moves accounting between whatever two keys it is
given), so the risk is concentrated in admission and eviction, not in transfer.

The worker's decision not to touch `RegionKey.java` (task 14 is its single writer per architecture §5)
is correct. But the `NEEDS_CONTEXT` escalation understates the exposure: the dev record says the
accounting "relies only on stable `equals`/`hashCode` map-key behavior", which is true but reads as a
mild dependency. It is not mild — it is the difference between per-region bounds existing and not
existing. **Recommend the orchestrator promote this to a hard co-signed precondition on task 14
rather than a reconciliation note**, and that task 13 add a canonicalization seam or an explicit
documented precondition on the class.

---

## State of tree (context, not a design fault)

`DefaultFoliaBackpressure` calls `completionService.tryAcquireLease(attemptId)` (lines 169, 373).
The only `tryAcquireLease` in the current `OperationCompletionService` is
`tryAcquireLease(Object attemptIdentity, Object registrationKey)` (line 169 of that file). The folia
module therefore **does not compile against the in-tree core**. Additionally, `ProducerLease.queueDelivery`
throws `IllegalStateException` unless `registrationCapabilityConsumed` is true
(`OperationCompletionService.java:875-878`), and `DefaultFoliaBackpressure` never calls
`registerProducer(lease, …)` — so even after fixing the arity, every `deliver` / `deliverExceptionally`
would throw at runtime.

Per the brief this is the pre-r10 call shape awaiting the planned mechanical alignment pass, and I do
not fault it as a design error. I record it because it determines two things below: acceptance
criterion 1, and the reproducibility of the dev record's verification claims.

---

## Acceptance-criteria check

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Graph compile proof green | **FAIL** | Not run (Gradle prohibited, correctly). Cannot pass as-is: `tryAcquireLease` arity mismatch, above. Awaits the r10 alignment pass. |
| 2 | `tryAcquire` provably never blocks | **PARTIAL** | Returns `Optional`, uses `accountingLock.tryLock()`, has no await path — verified by reading every branch of 165–226. But it does block on `OperationCompletionService.lifecycleLock` twice (lease acquire, `deliverExceptionally`) and starts a virtual thread on the miss path. The claim holds for backpressure's own lock only. Test `tryAcquireReturnsWhileAccountingIsBusy` covers only the accountingLock path. |
| 3 | Stage machine rejects out-of-order transitions; `close()` idempotent | **PASS** | `enter` line 781 ordinal test; `close` line 819 `closed` guard. Test `permitStagesAdvanceInOrderAndCloseIsIdempotent` asserts one invalid transition (READY→COMMITTING), double-`close`, and that capacity is released after the invalid transition. |
| 4 | Continuation budget carved from finalizer capacity, not additive, asserted in a test | **PASS** | Lines 119/122. `continuationCapacityIsCarvedOutOfFinalizers` (test:304) is a genuine test: with `maxFinalizersPerRegion=4, continuationReserve=1` it takes exactly 3 regular permits, asserts the 4th misses, then asserts a continuation still succeeds. An additive implementation would have admitted 4 regular. This test would fail on a wrong implementation. |
| 5 | No caller-runs path for owner-bound work | **PASS** | No `execute`-then-fallback-`run()` in task-13 code. `CompletableFuture.runAsync` / `delayedExecutor` use the common pool. The one inline execution is `cancellationSignal.whenComplete` (line 424) firing synchronously on the `acquire` caller when the signal is already complete — but `acquire` is contractually never called from a tick thread, so this is not owner-bound work, and it is lock-bounded. (`OperationCompletionService.isolatedContinuationExecutor` line 395 has a `catch (RejectedExecutionException) { command.run(); }` caller-runs fallback, but that is task 11's and runs on a notification virtual thread.) |

---

## Test quality verdict

**Insufficient for this lens.** The suite is honest — I found no test that passes vacuously, no test
asserting an implementation detail in place of the contract, and no test whose name overstates what it
checks. That is better than the sibling task. Several are genuinely good:

- `continuationCapacityIsCarvedOutOfFinalizers` — proves subtractiveness; would fail on an additive impl.
- `stopSettlesMoreThanOneWaiterBatch` — 100 waiters against `WAITER_SERVICE_BATCH=64` genuinely
  exercises multi-batch settlement and the async continuation.
- `transferRefusesFullTargetWithoutMovingAccounting` — checks refusal, region-field stability, and
  post-transfer chunk counts on both regions.
- `permitStagesAdvanceInOrderAndCloseIsIdempotent` — the follow-up `tryAcquire` after the invalid
  transition proves capacity was actually released, not just that an exception was thrown.

The problem is coverage, and it is concentrated exactly where my findings are:

1. **Zero byte-accounting coverage.** Not one assertion reads `Pressure.readyBytes()`. `preparedBytes`
   is set to `chunks * 16L` in the helper and never checked. The brief's instruction to check byte
   accounting as carefully as chunk counts is not met by any test, and B2 is invisible to the suite.
2. **Zero global-cap coverage.** Every test uses limits where the per-region cap binds first
   (`limits(...)` sets global chunks to 64 and global bytes to 16 384 against per-region 1–8 and
   1 024). `maxGlobalReadyBytes`, `maxGlobalReadyChunks` and `maxGlobalFinalizers` are never the
   binding constraint in any test, so M1's derivation is untested.
3. **`transfer` is tested only for READY-stage regular permits.** The entire stage `switch` in
   `moveRegionalAccounting` (759–770) — the SCHEDULED/COMMITTING `scheduledChunks` move and the
   FINALIZING `activeFinalizers` move — has no coverage, nor does transferring a continuation permit.
   §3.7 says rebind-at-drain is the primary `transfer` caller, and a drain-time permit is not READY.
4. **No conservation invariant test.** Nothing asserts that after a mixed sequence of
   grant / reject / expire / cancel / transfer / invalid-transition / close, every counter returns to
   zero. `trackedRegionCount() == 0` is used once, in the transfer test only. A single
   randomized-sequence conservation test would have caught m1 and would catch most future regressions.
5. **Eviction test asserts the safe half only.** `idleRegionsAreEvictedAfterTransfer` proves a region
   *is* evicted when idle and that telemetry does not resurrect it. It never asserts that a region
   with live waiters or a live permit is **not** evicted — the direction that loses data.
6. **`fullWaiterPartitionRejectsBeforeAcceptance` does not prove exactly-once.** It uses
   `rejected.remove()` on a `ConcurrentLinkedQueue` and never asserts the queue is subsequently empty,
   so a duplicate rejection hook invocation would pass.
7. **No B1 coverage.** No test settles more than one delivery in a batch where the first one fails.

**Test-runner note:** the class carries both JUnit `@Test` annotations and a hand-rolled `main`
runner enumerating all 15 methods. The two must be kept in sync by hand; a method added with `@Test`
but omitted from the `TestCase[]` array runs under Gradle and not under the standalone runner (and
vice versa). Given that Gradle is currently prohibited and the standalone runner is the only
verification evidence, this is a real divergence risk.

---

## Dev-record accuracy

**Verified accurate:**
- Clause-1 claim ("collect immutable `AdmissionDelivery` values and never invoke hooks or complete
  futures while locked") — I checked all eight sites; true.
- Clause-3 `DELIVERY_CLAIMED` linearization description — accurate, including "no future or user code
  runs at the claim".
- Clause-4 claim that transfer/FINALIZING/close settle synchronously then enqueue under the waiters'
  pre-acquired leases — accurate.
- "Removed the private completion executor, completion lock/queue, tick detector, fallback drainer,
  thread factory, inline drain, and shutdown join" — confirmed by grep; none remain.
- Continuation-carve and stage-order test line references — correct and the tests are real.
- The `[NEEDS-RUNTIME]` deviation for the seven `Limits` slots plus the carve — accurate but
  incomplete (m8).

**Not supportable:**
- *"Java 25 targeted production+test compilation passed with `--release 25 -Xlint:all -Werror`"* and
  *"the standalone 15-test suite passed five consecutive runs"*. Against the current in-tree
  `OperationCompletionService` the class does not compile (arity) and, once it did, every delivery
  would throw (unconsumed registration capability). The worker must have built against a different
  core revision. The claim is not reproducible now and should not be treated as acceptance evidence.
- *"Kept: regional/global multi-resource accounting … proven bounded accounting"*. "Proven" is not
  supported: the byte dimension and every global cap have no test at all, and B2 shows the byte bound
  is not enforced for continuations.
- *"Recheck the regional/global regular-versus-continuation carve"* is listed as an attack point —
  correctly, and it is where M1 lives. The self-flag was right; the issue was not resolved.
- The `RegionKey` escalation understates the exposure (see above).

---

## What I verified and could NOT fault

Recorded so the next reviewer does not re-spend budget here:

- **No double-release exists.** I traced every grant→release pairing across all seven release sites
  (`close`, invalid `enter`, `cancel` on GRANTED, `deliverGrant` supersession, `serviceWaiters`
  rejection, `failAttemptLocked`, `tryAcquire` catch). The `readyCapacityHeld` / `globalReadyHeld` /
  `globalFinalizersHeld` / `closed` flags are all cleared under the lock at the moment of release, and
  `release()` short-circuits on `closed`. Each unit is released exactly once.
- **`enter(FINALIZING)` → `close()` does not double-count.** `releaseReadyCapacity` at 799 clears the
  flags, so the `close()` path's second call is a no-op, and `activeFinalizers` is incremented at 798
  and decremented at 839 exactly once.
- **The cancel/deliver race is correct in all three interleavings** (cancel-wins-lock,
  deliverGrant-wins-with-flag-visible, deliverGrant-wins-before-flag). Each yields one outcome and one
  release. §3.6c clause 3 is genuinely discharged.
- **`transfer` cannot produce a partial, both-charged, or neither-charged state**, and cannot leave the
  permit pointing at the wrong region on refusal. I attacked this specifically and could not break it.
- **`pruneIfIdle` cannot drop a region with live permits, waiters, or non-zero counters** — the
  predicate covers every gating field of `RegionState`, and the identity-checked
  `regions.remove(region, state)` prevents a recreated region from inheriting stale accounting.
- **`fits(current, additional, limit)`** (948–950) is overflow-safe for all non-negative inputs, and
  correctly rejects a single demand larger than the limit.
- **`AdmissionAttemptId` identity semantics** are correct for a shared completion service (no
  `equals`/`hashCode` → no cross-instance collision in `producerLeases`).
- **The rejection hook fires exactly once per attempt** — it hangs off a `CompletableFuture` that
  completes once, it correctly unwraps `CompletionException`, it correctly does *not* fire on
  successful grant delivery, and hook exceptions are caught and logged rather than corrupting
  settlement (`observeRejection`, 999–1014).
- **`serviceWaiters` terminates.** `examined` monotonically reduces the returned `scanRemaining`, so
  the rotation cannot spin.
- **`stopAccepting` is idempotent** and correctly leaves already-granted permits alone to drain.
- **The certification values in `initialLimits`** match §3.4 exactly, including
  `min(1 GiB, maxHeap/4)`.
