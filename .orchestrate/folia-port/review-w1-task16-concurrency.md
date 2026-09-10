# Review W1 / Task 16 — concurrency, liveness (§1b), resource bounding

Reviewer: fresh adversarial thread. Lens: concurrency, liveness, resource bounding.
Perimeter excluded: C4 scheduler-routing correctness and QueueHandler contract preservation
(covered by the second reviewer).

Everything below was read in the current working tree. Folia API semantics were verified
against `folia-api` sources (`dev.folia:folia-api:1.20.1-R0.1-SNAPSHOT-sources.jar`, in the
local Gradle cache) and against the server implementations
`PaperMC/Paper:paper-server/src/main/java/io/papermc/paper/threadedregions/scheduler/FoliaGlobalRegionScheduler.java`
and `FoliaAsyncScheduler.java` — not from memory.

---

## VERDICT

**REJECT**

One BLOCKING defect (a repeating task that throws once becomes permanently uncancellable,
untracked, and unbounded — and the test suite certifies that behaviour as correct), plus a
liveness hole where the entire "never block a tick thread" property rests on a predicate that
has **no Folia implementation anywhere in the tree** and no fail-closed guard.

The bookkeeping that *is* there is good. The cancel-before-attach race — the specific race the
mandate asked me to break — is genuinely correct, and I could not fault it. That makes the
BLOCKING finding more frustrating, not less: it is a single wrong branch in an otherwise
careful state machine.

---

## Can a tick thread block anywhere?

**Yes — three reachable paths.** Two of them are outside the classes' own code but are
handed out by them.

First, the good news. Given a *correct* `isTickThread()`, neither class blocks a tick thread in
its own code. I traced every waiting primitive:

| Site | File:line | On a tick thread |
| --- | --- | --- |
| `sync(Supplier)` / `sync(RunnableVal)` | `FoliaTaskManager.java:274-280` | short-circuits inline at 276-278, never reaches `awaitGlobal` |
| `syncWhenFree(...)` | `FoliaTaskManager.java:259-266` | delegates to `sync`, same short-circuit |
| `taskNowMain` / `taskWhenFree` / `taskSoonMain` | `FoliaTaskManager.java:224-256` | inline, no wait |
| `parallel(...)` (both overloads) | `FoliaTaskManager.java:284-307` | `requireNonTickWait` throws `IllegalStateException` at 482-486 |
| `wait(AtomicBoolean, int)` | `FoliaTaskManager.java:311-332` | same guard, throws |
| `cancel(int)` | `FoliaTaskManager.java:213-221` | non-blocking — `ScheduledTask.cancel()` javadoc: "if the task is currently being executed no attempt is made to halt the task" (verified in `ScheduledTask.java:24-27`) |
| `FoliaQueueHandler.sync/syncWhenFree` (all 7) | `FoliaQueueHandler.java:47-95` | inline at the `tickThread` branch; off-tick they only *register* callbacks, never wait |
| base `QueueHandler.operate()` → `queue.wait(1)` | `QueueHandler.java:171-176` | **unreachable** — `FoliaQueueHandler.run()` throws at 42-44, and `repeat()` refuses to register the drain at `FoliaTaskManager.java:134-138` |

Now the three paths that do reach a wait or unbounded work on a tick thread:

**Path 1 — the predicate itself (see MAJOR-5).**
`FoliaTaskManager.java:71` and `FoliaQueueHandler.java:33` both bind
`() -> FaweThreadContext.current().isTickThread()`. There is exactly one
`FaweThreadContext` implementation in the repository — `BukkitThreadContext`
(`worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/util/BukkitThreadContext.java:34-36`),
whose `isTickThread()` returns `Fawe.isMainThread()`. `ContextResolver.register` is called from
**two test files and no production code** (`DefaultFoliaRegionDispatcherTest.java:80`,
`TicketAuthorityTest.java:46`). If bootstrap registers the Bukkit context on Folia, every region
thread answers `false`, and:

```
region thread R  ──> TaskManager.sync(supplier)
                     tickThread.getAsBoolean() == false          (FoliaTaskManager.java:276)
                 ──> awaitGlobal(supplier)                       (FoliaTaskManager.java:368)
                 ──> globalRegionScheduler.run(plugin, callback) (FoliaTaskManager.java:379)
                 ──> result.get(60s)                             (FoliaTaskManager.java:382)  <-- R is parked
```

If `R` *is* the global region thread, the callback it is waiting for can only run on `R`.
Guaranteed 60-second stall of the entire global region, then `IllegalStateException`. This is
the textbook self-deadlock shape from the mandate, and it is a **bounded** wait — which is
exactly the point the mandate makes: a bounded wait on a tick thread is still a wait, and 60 s
of frozen global region is indistinguishable from a hang to every player on the server.

Nothing in either class fails closed if the predicate is wrong. `folia-api` is on the compile
path of this module; a cheap belt-and-braces guard is available and unused.

**Path 2 — unbounded work, not a wait (see MAJOR-2).**
`register()` calls `reapFinishedTasks()` on **every** submission
(`FoliaTaskManager.java:489`), which scans the whole map. `TaskManager.taskNowAsync` on a tick
thread routes to `async(...)` → `submit` → `register`, so a region thread performs up to
65 536 `getExecutionState()` reads before its own submission is accepted. §1b forbids
unbounded work on a tick thread on the same footing as blocking.

**Path 3 — futures handed out with no deadline (see MAJOR-3).**
`FoliaQueueHandler` returns `DispatchFuture` (`FoliaQueueHandler.java:248-264`), a bare
`CompletableFuture` with no completion deadline. `FoliaTaskManager.awaitGlobal` bounds its own
wait at 60 s; the QueueHandler surface bounds nothing. `QueueHandler.complete(Future)`
(`QueueHandler.java:195-203`) — still public, still inherited — loops `task.get()` with no
timeout. Live callers that `.get()` a `FoliaQueueHandler` future include
`AbstractBukkitGetBlocks.java:174` and `PaperweightGetBlocks.java:802`.

---

## Leak analysis of the id→handle map

**Structure.** Insert only in `register()` (`FoliaTaskManager.java:488-511`), always paired with
a `trackingCapacity.tryAcquire()`. Remove only in `complete()` (`638-643`), guarded by
`completed.compareAndSet(false, true)` so `tasks.remove` and `trackingCapacity.release()` each
happen **exactly once per registration**. I checked this specifically for permit inflation and
found none.

**Paths I tried to break, and could not:**

| Path | Outcome | Where |
| --- | --- | --- |
| `submission.submit(callback)` throws | `catch (RuntimeException \| Error)` → `complete()` → entry removed, permit released, rethrow | `354-365` |
| Callback fires re-entrantly *before* `submit` returns | `attach` is idempotent via `compareAndExchange`; outer `attach`/`completeIfTerminal` are no-ops on an already-completed registration | `589-598`, `358-361`; test at `FoliaTaskManagerTest.java:121-130` |
| Handle already `CANCELLED`/`FINISHED` when `submit` returns | `completeIfTerminal()` reaps immediately | `360`, `625-636`; test at `133-142` |
| `cancel(id)` lands in the pre-attach window | **Not lost.** `cancel()` sets `cancellationRequested` before `complete()`; `attach()` re-reads the flag at `595-597` and cancels the handle once it arrives; `execute()` re-reads it at `602-604` and returns without running | `589-623` |
| Two threads cancel concurrently | `completed` CAS makes it idempotent; `ScheduledTask.cancel()` returns `CANCELLED_ALREADY` | `616-623` |
| One-shot normal completion | `finally { complete(); }` | `609-613` |

The cancel-before-attach handling is correct in **both** legs. Credit where due — this is the
race the mandate flagged, and it is not broken.

**The path that does leak: BLOCKING-1.** A *repeating* task whose body throws. `execute()`'s
`finally` fires `complete()` (`610-612`), removing the entry and releasing the permit — but
Folia catches the exception, logs it, and **reschedules the repeating task**. The registration
is gone; the handle is alive forever. Details and the verification below.

**Behaviour at the 65 536 bound.** **Rejection.** Not eviction, not a silent drop:

```java
if (!trackingCapacity.tryAcquire()) {
    throw new RejectedExecutionException("Folia task handle limit reached (" + maxTrackedTasks + ')');
}
```
`FoliaTaskManager.java:490-494`, covered by a real test at `FoliaTaskManagerTest.java:145-160`.

**This is the right choice** — eviction would orphan a live Folia handle, which is precisely the
BLOCKING-1 failure mode reached deliberately. But see MAJOR-6 for how it surfaces: it escapes
through `void` methods (`async`, `task`, `later`, `laterAsync`) that every legacy caller treats
as fire-and-forget.

**Residual, benign.** Entries for tasks Folia cancels externally (plugin disable,
`cancelTasks`) are reaped only by the next `register()`. After disable there are no more
submissions, so the map retains them until the manager is collected. Not a live-server leak.
`cancelAll()` (`348-352`) is the orderly drain, and the dev record correctly identifies that
task 17 must call it at shutdown.

---

## Findings

### BLOCKING-1 — a repeating task that throws once becomes permanently uncancellable, untracked, and unbounded

`worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaTaskManager.java:600-614`

```java
private void execute(ScheduledTask scheduledTask, Runnable runnable) {
    attach(scheduledTask);
    if (cancellationRequested.get()) { return; }
    boolean succeeded = false;
    try {
        runnable.run();
        succeeded = true;
    } finally {
        if (!repeating || !succeeded) {   // <-- line 610
            complete();
        }
    }
}
```

**Verified Folia behaviour** (`PaperMC/Paper`, `FoliaGlobalRegionScheduler.java`; identical in
`FoliaAsyncScheduler.java`):

```java
try {
    this.run.accept(this);
} catch (final Throwable throwable) {
    this.plugin.getLogger().log(Level.WARNING, "Global task for ... generated an exception", throwable);
}
...
} else if (STATE_EXECUTING == this.compareAndExchangeStateVolatile(STATE_EXECUTING, STATE_IDLE)) {
    reschedule = true;
}
if (reschedule) { FoliaGlobalRegionScheduler.this.scheduleInternal(this, this.repeatDelay); }
```

Folia **swallows the exception and reschedules the repeating task**. It does not cancel it.
`ScheduledTask.ExecutionState.FINISHED` is documented as "The task is **not repeating**, and the
task finished executing" (`ScheduledTask.java:97-99`) — a live repeating task has no terminal
state other than `CANCELLED`/`CANCELLED_RUNNING`.

**Interleaving:**

1. `repeat(timer, 1)` → registration `#R` in `tasks`, one permit held.
2. Tick *n*: global region invokes the callback → `execute` → `timer.run()` throws.
3. `finally`: `!succeeded` → `complete()` → `tasks.remove(R)`, `trackingCapacity.release()`.
4. Exception propagates into Folia → caught, logged, `state → IDLE`, **rescheduled**.
5. Tick *n+1*: callback runs again. `attach` no-ops, `cancellationRequested` is false, body
   throws again, `complete()`'s CAS already true → no-op. **No permit re-acquired, no map entry.**
6. Forever. At `interval=1` that is 20 stack traces per second into the server log.
7. `cancel(id)` → `tasks.get(id) == null` → **silent no-op** (`217-220`).
   `cancelAll()` iterates `tasks.values()` → the task is invisible to it (`348-352`).

**Consequences:**
- The task is **uncancellable through the TaskManager API** for the lifetime of the server.
- The 65 536 bound stops bounding *live Folia handles* — permits are returned for tasks that
  are still running. The mandate's "bounded map" property fails in exactly the failure case it
  is meant to survive.
- Unbounded log growth on a long-lived server.

**Blast radius — every repeating task in FAWE:**

| Call site | Task | Interval |
| --- | --- | --- |
| `worldedit-core/src/main/java/com/fastasyncworldedit/core/Fawe.java:139` | `repeatAsync(MemUtil::checkAndSetApproachingLimit, 1)` | every tick, whole server lifetime |
| `worldedit-core/src/main/java/com/fastasyncworldedit/core/Fawe.java:141` | `repeat(timer, 1)` — the FAWE TPS timer | every tick, whole server lifetime |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/adapter/Regenerator.java:107` | regen driver, **and it holds `taskId` for a later `cancel(taskId)`** | every tick, per regen |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/listener/ChunkListener.java:66` | chunk watchdog | repeating |
| `worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/listener/RenderListener.java:31` | render throttle | repeating |

`Regenerator` is the sharpest case: `runTasks(...)` executes arbitrary chunk generation, one
transient throw leaks a 20 Hz task that the regen's own `cancel(taskId)` can no longer stop.

**Why the test suite did not catch it — and actively certifies it:**
`FoliaTaskManagerTest.java:113-117` asserts `trackedTaskCount() == 0` after a repeating callback
throws, i.e. it encodes the defect as the intended contract. It can only do so because the fake
models the *wrong* API:

```java
// FoliaTaskManagerTest.java:501-513
private void fire(Consumer<ScheduledTask> callback) {
    ...
    } catch (RuntimeException | Error failure) {
        state = ExecutionState.FINISHED;   // Folia: repeating -> IDLE, never FINISHED
        throw failure;                     // Folia: catches and logs, never rethrows
    }
}
```

Both divergences point the same way, and together they hide the bug.

**Fix direction (not prescriptive):** on a repeating failure, do not `complete()`. Either leave
the registration tracked (Folia will run it again, and `cancel(id)` must keep working), or
explicitly `handle.cancel()` *before* completing so that dropping the entry and killing the
handle are atomic. The current code does neither.

---

### MAJOR-2 — O(n) map scan on every submission, reachable from a region tick thread

`FoliaTaskManager.java:488-489` and `513-521`

```java
private TaskRegistration register(boolean repeating) {
    reapFinishedTasks();                       // full scan of `tasks`, every submit
    if (!trackingCapacity.tryAcquire()) { ... }
```

`reapFinishedTasks()` walks every entry and calls `getExecutionState()` on each live handle —
a volatile read into Folia's scheduler state per entry, up to 65 536 of them. Every
`async` / `task` / `later` / `laterAsync` / `repeat` / `repeatAsync` / `sync` (via `awaitGlobal`)
pays this.

Two problems:

1. **Quadratic hot path.** FAWE's scheduling surface is high-frequency. Cost per submit grows
   linearly with live tasks; total cost over a burst of *n* submissions is O(n²).
2. **On a tick thread.** `TaskManager.taskNowAsync` from a region thread →
   `async(...)` → `submit` → `register` → full scan. §1b: a tick thread "must never run
   unbounded work". 65 536 volatile reads is bounded only in the arithmetic sense.

The reap is also redundant for the common case: one-shot tasks already self-remove in
`execute`'s `finally`, and repeating tasks can never be reaped by it (no terminal state).
It only catches externally-cancelled handles — which needs a far cheaper trigger than a full
scan per submission.

`taskId(ScheduledTask)` (`339-346`) is a second O(n) linear scan; it is currently test-only.

---

### MAJOR-3 — `FoliaQueueHandler` futures have no completion deadline; a stalled global region hangs callers forever

`FoliaQueueHandler.java:248-264` (`DispatchFuture`), `171-227` (dispatch), `195-203` of `QueueHandler.java` (the unbounded consumer)

`FoliaTaskManager.awaitGlobal` bounds its wait at `ownerWaitTimeoutNanos` (60 s default,
`FoliaTaskManager.java:382`) and cancels the task on timeout. The QueueHandler surface has no
equivalent: `DispatchFuture` completes only when `dispatcher.onGlobal`'s stage settles.

`DefaultFoliaRegionDispatcher.onGlobal` (`221-236`) does complete its stage on every path I
traced — rejection after `stopAccepting` (`226-229` → `reject` at `333-339`) and submission
failure (`232-234`) both complete exceptionally. So the *ordered* shutdown is safe.

The hazard is the window **before** `stopAccepting` is called: `globalRegionScheduler.execute`
succeeds, the server stops ticking the global region, the callback never runs, the stage never
settles, and every caller in `.get()` hangs through shutdown with no diagnostic. Callers:
`QueueHandler.complete(Future)` (`QueueHandler.java:195-203`, an unbounded *chained* `get()`
loop), `AbstractBukkitGetBlocks.java:174`, `PaperweightGetBlocks.java:802`.

The chunk pipeline is wave 2, so the *callers* are out of scope — but the future contract they
consume is defined here, and it is strictly weaker than the one `FoliaTaskManager` gives itself.
An owner-wait deadline on `DispatchFuture` costs nothing and closes this.

---

### MAJOR-4 — the location-free sync path has no admission control, and `syncWhenFree` doubles global-region load

`FoliaQueueHandler.java:171-227`; `DefaultFoliaRegionDispatcher.java:221-236`

`dispatchGlobal` and `dispatchGlobalWhenFree` call `dispatcher.onGlobal(...)` unconditionally.
`DefaultFoliaRegionDispatcher.onGlobal` consults no `FoliaBackpressure` — it registers and calls
`globalRegionScheduler.execute`. There is no `tryAcquire`, no shed path, no queue-depth check.

Architecture §3.4 defines a full admission model; this seam bypasses it entirely. Every legacy
`sync(...)` call from every FAWE worker lands on the single global region with no throttle.

The old design had an implicit throttle: the drain loop was time-sliced by `getAllocate()`
(`QueueHandler.java:150-163`), which capped sync work per tick. Striking the drain (correctly,
per §2) removed the rate limiter **without replacing it**. That is a resource-bounding
regression, not just a routing change.

Compounding it: `dispatchGlobalWhenFree` submits **two** global tasks per call — a priority gate
(`179-184`) plus the real dispatch. `FoliaQueueHandlerTest.java:79` confirms the arithmetic:
3 `sync` + 4 `syncWhenFree` = 11 global submissions. The surface designed to be *lower* priority
now costs 2× the global-region turns of the normal path.

---

### MAJOR-5 — the entire no-block guarantee depends on a `FaweThreadContext` that does not exist, with no fail-closed guard

`FoliaTaskManager.java:71`, `FoliaQueueHandler.java:33`

Traced in "Can a tick thread block anywhere?", Path 1. Summary:

- Both production constructors bind `() -> FaweThreadContext.current().isTickThread()`.
- The only implementation in the tree is `BukkitThreadContext` (`isTickThread()` →
  `Fawe.isMainThread()`), which is wrong on Folia for every region thread.
- `ContextResolver.register` has **no production call site** (grep: two test files only).
- Consequence if the wrong context is registered: `awaitGlobal` parks a region thread for
  60 s; if it is the global region thread, that is a guaranteed self-deadlock that always burns
  the full timeout.
- If **no** context is registered, `ContextResolver.resolve()` throws
  `IllegalStateException("No FaweThreadContext has been registered")`
  (`ContextResolver.java:25-31`) — which at least fails loudly, but from inside a scheduler
  callback where it will be caught and logged by Folia, not surfaced.

Task 17 owns registration, and the dev record's "Attack points" flags bootstrap *ordering* for
the drain strike — but not this. A class whose safety property is entirely delegated to an
unimplemented SPI should assert that delegation at construction. `folia-api` is on this module's
compile path; `Bukkit.isGlobalTickThread()` / `Bukkit.isOwnedByCurrentRegion(...)` are directly
available as a second opinion inside `awaitGlobal` before it parks.

---

### MAJOR-6 — `RejectedExecutionException` at the bound escapes through `void` fire-and-forget methods

`FoliaTaskManager.java:490-494`, reached from `169-210`

`async(Runnable)`, `task(Runnable)`, `later(Runnable, int)` and `laterAsync(Runnable, int)` all
return `void` in the frozen `TaskManager` contract. At the bound they now throw an unchecked
`RejectedExecutionException`. Legacy call sites — written against a `BukkitScheduler` that never
threw — do not handle it.

Two failure modes, both bad in different ways:
- Thrown on a region thread: propagates into Folia's `catch (Throwable)`, gets logged as a
  generic task exception, and **the submitted work silently never runs**.
- Thrown on a FAWE worker mid-edit: aborts the edit with an exception that names a scheduler
  limit, not the operation.

Rejection is the correct policy (stated above). What is missing is a distinguishable, rate-limited
diagnostic at the point of exhaustion — right now the only signal is an exception message inside
whatever generic handler happens to catch it.

Also worth noting: the bound counts *pending* one-shots, not just live repeating tasks. A burst
of `later(r, longDelay)` can exhaust 65 536 permits with entirely legitimate work.

---

### MINOR-7 — task-id ABA after counter wrap

`FoliaTaskManager.java:523-525`, `497-503`, `358-361`

`nextId()` wraps `1..Integer.MAX_VALUE`; `putIfAbsent` only checks *live* entries, so a
completed task's id is immediately reusable. `submit` can also return an id that is already
complete (the `fireBeforeReturn` path at `358-361`, deliberately tested at
`FoliaTaskManagerTest.java:121-130`).

A caller that holds an id across the wrap — `Regenerator.java:107` holds one for the duration of
a regen — can have its `cancel(staleId)` land on an unrelated fresh task. At ~1 000 submissions/s
the wrap is ~25 days; this plugin runs on long-lived servers. Bukkit ids wrap too, so this is
rough parity, but Bukkit does not reuse an id while the original holder may still act on it.

### MINOR-8 — `parallel()` injects interrupts into the shared public ForkJoinPool

`FoliaTaskManager.java:286`, `451-455`

`parallel(Collection)` submits to `getPublicForkJoinPool()` — the shared pool whose base-class
javadoc says "ONLY SUBMIT SHORT LIVED TASKS / DO NOT USE SLEEP/WAIT/LOCKS"
(`TaskManager.java:82-85`). On timeout, `cancelFutures` calls `future.cancel(true)`, interrupting
the pool's worker threads. `FutureTask.handlePossibleCancellationInterrupt` does **not** clear
the runner's interrupt status, so the flag can outlive the task on a pooled thread — the exact
"interrupt that outlives the task" shape from the mandate, in leaked rather than swallowed form.
Modern `ForkJoinPool.awaitWork` clears it opportunistically, so it is usually self-healing.

Impact is low: both `parallel` overloads are `@Deprecated(forRemoval = true)` and have no
internal callers. Flagging it because the mandate asked specifically about pooled-thread
interrupt hygiene.

### MINOR-9 — `wait(AtomicBoolean, int)` changes its failure mode

`FoliaTaskManager.java:311-332` vs `TaskManager.java:287-302`

Base: loops indefinitely, logs a warning past 60 s, swallows `InterruptedException`.
Folia: throws `IllegalStateException` on timeout and on interrupt.

The new behaviour is better (the old one is a liveness bug), and the interrupt flag is correctly
restored at `326-329`. The `TimeUnit.NANOSECONDS.timedWait` loop recomputes the remaining
deadline each iteration and is spurious-wakeup safe — correct. Noting only that it is a
behavioural break for third-party callers of a deprecated method.

### MINOR-10 — test resource hygiene and global-state mutation

`FoliaQueueHandlerTest.java:182-187`, `FoliaTaskManagerTest.java:278-281`

Every `fixture()` constructs a real `QueueHandler`, whose constructor allocates two
`ForkJoinPool`s plus a `ThreadPoolExecutor` (`QueueHandler.java:60-88`) that are never shut down
— roughly eight sets per suite run. Every fixture also constructs a `FoliaTaskManager`, whose
base constructor writes the static `TaskManager.INSTANCE` (`TaskManager.java:33-35`), so tests
mutate shared global state and are order-coupled.

### MINOR-11 — the one multi-threaded test is itself racy

`FoliaTaskManagerTest.java:210-231`

`RecordingSchedulers`' fields (`route`, `callback`, `lastTask`, `delay`, `period`, `unit`,
`FoliaTaskManagerTest.java:368-375`) are plain non-volatile. The virtual thread writes them
inside `manager.sync(...)`; the main thread spins on `hasSubmission()` reading `route` and then
calls `fireLast()` reading `callback`/`lastTask` — with no happens-before edge in either
direction. `FakeScheduledTask.state` (`471`) is likewise non-volatile and written from both.
Formally the spin may never terminate or `fireLast()` may observe a stale/null callback.

---

## Test quality verdict

**Deterministic and genuinely meaningful for routing and lifecycle bookkeeping. Provides
essentially no evidence about concurrency. One central leak-bound test certifies a defect.**

Applying the requested skepticism test-by-test — *can it fail on the property it names?*

**Yes, real tests:**

| Test | Property | Can fail? |
| --- | --- | --- |
| `handleMapIsBoundedAndCapacityReturnsAfterCancellation:145` | bound + permit return | **Yes** — drops the `tryAcquire` and it fails |
| `inheritedWorkerSyncWaitIsBoundedAndCancellationPropagates:233-239` | wait is bounded | **Yes** — drop the timeout and it hangs |
| `parallelAggregatesFailuresAndInterruptsRunningWorkOnTimeout:243` | deadline + `cancel(true)` interrupt delivery | **Yes** — `cancel(false)` and it fails |
| `synchronousSchedulerCallbackCannotReinsertACompletedHandle:121` | reentrant attach idempotence | **Yes** |
| `schedulerCancelledHandleDrainsAsSoonAsSubmissionReturns:133` | terminal reap on submit-return | **Yes** |
| `queueConstructionDoesNotInstallTheLegacyGlobalDrain:162` | drain strike | **Yes** |
| `syncWhenFreeDefersItsCallbackByOneGlobalOwnerTurn:84` | one-turn gate | **Yes** |
| `cancellingAQueuedLocationFreeFutureSkipsItsCallback:117` | cancel suppresses callback | **Yes**, for the *cancel-strictly-before-run* ordering only |
| `unsafeAndManualDrainEntryPointsFailBeforeSideEffects:173` | fail before side effects | **Yes** |

**No — and this is where the suite is hollow:**

1. **Zero tests exercise concurrent execution.** `FoliaQueueHandlerTest.RecordingDispatcher.onGlobal`
   (`259-269`) runs the task **inline on the calling thread**. Every FoliaQueueHandler test is
   fully sequential. No `DispatchFuture` is ever pending across threads, so `.get()` never
   actually blocks in any test, and the interesting `claimCallback`/`cancel` interleaving —
   cancel arriving while the callback sits between `claimCallback()` (`207`) and
   `callable.call()` (`211`) — has no test and **cannot** fail this suite.
2. **The cross-thread attach race is untested.** Only the same-thread reentrant variant
   (`fireBeforeReturn`) is covered. On real Folia, `asyncScheduler.runNow` can invoke the
   callback on another thread before `runNow` returns — a genuinely different interleaving.
   (I traced it by hand; it is safe. But the suite is not why I know that.)
3. **The only multi-threaded test is a sequencing test, not a race test** — and is itself racy
   (MINOR-11). Spin-until-submitted then fire is a deterministic handoff. It would not detect a
   lost cancel or a double permit release.
4. **`oneShotAndFailedRepeatingCallbacksDrainTheHandleMap:89` is worse than vacuous — it is
   wrong.** It asserts precisely the behaviour that constitutes BLOCKING-1, and it can only do so
   because `FakeScheduledTask.fire` (`501-513`) models Bukkit semantics rather than Folia's. A
   fake that diverges from the real API in the direction of the bug is the most expensive kind of
   test to have.
5. No test asserts the `-1` sentinel is returned for the struck drain — only that no submission
   occurs. No test covers `repeat` with a non-`FoliaQueueHandler` runnable near that guard.

The prior review's framing applies here with one adjustment: these tests are not
start-barrier-with-union-safe-assertions, they are honestly *sequential*, and most of them do
test something real. The problem is scope, not theatre — the concurrency claims in the dev
record have no test backing them at all, and the one leak test that exists points the wrong way.

---

## Dev-record accuracy

**Accurate and verified:**
- APIC-019/020/022 routing and the tick floors. I verified the underlying contracts rather than
  trusting the comments: `FoliaGlobalRegionScheduler.runDelayed` throws on `delayTicks <= 0` and
  `runAtFixedRate` throws on `periodTicks <= 0`; `FoliaAsyncScheduler.runDelayed` rejects only
  `delay < 0` and `runAtFixedRate` rejects `period <= 0` while allowing `initialDelay == 0`. The
  code's `globalInitialDelay`/`repeatingPeriod` = `max(1, ·)` and the async `max(0, ·)` are each
  correct for their scheduler. The in-code comments at `531-541` are right.
- APIC-023 "race-safe before handle attachment" — **true**, and it is the strongest part of the
  implementation.
- APIC-028 `runUnsafe` → `UnsupportedOperationException` matches architecture §3.5 verbatim
  ("`runUnsafe` → **DEGRADED**: fails fast with `UnsupportedOperationException` + caption before
  the callback"). I confirmed there are **no internal callers** of `runUnsafe`/`startUnsafe`/
  `startSet`/`endSet` in `worldedit-core` or `worldedit-bukkit`, so this is fail-closed with zero
  internal breakage.
- The ten public descriptors are preserved; the three `async` ones are correctly inherited.
- The `syncWhenFree` framing — "a first G owner turn is a priority gate … keeping normal sync
  work ahead" with the qualifier "already-eligible" — is honestly scoped. It does not overclaim
  a general priority guarantee it cannot provide.
- The "Deviations" note that the mandate's "§3.5 C4 scheduler mapping table" does not exist is
  **correct**. I read §3.5 (`architecture.md:580-643`): it defines targets and the ten preserved
  descriptors, but contains no scheduler table; C4 in §1 is operation completion. Good catch by
  the worker, honestly reported.

**Overclaimed:**
- APIC-023 "**bounded to 65,536 live handles**" — **false** in the repeating-failure case.
  Permits are released for handles that remain live (BLOCKING-1). The map stays bounded; live
  handles do not.
- APIC-025 "interrupt restoration" — true at all three waiting sites (`383-386`, `420-423`,
  `326-329`), and I could not fault it. It omits that `parallel` *injects* interrupts into the
  shared public pool, whose interrupt status `FutureTask` does not clear (MINOR-8).

**Missing:**
- The repeating-throw path is absent from "Attack points". Worse, the record describes
  "repeating failure … remove their entries" as if draining were the goal — it is the leak.
  Nowhere does the record state that Folia catches the exception and **reschedules** the task,
  which is the fact that inverts the meaning of that line.
- No mention that both classes are inert-but-unsafe without a Folia `FaweThreadContext`, and
  that none exists in the tree (MAJOR-5). The record's attack points cover bootstrap *ordering*
  for the drain strike but not this far larger dependency.
- No mention that striking the drain removed the `getAllocate()` rate limiter without a
  replacement (MAJOR-4).

**Unverifiable here:** "Direct Java 25 compilation against the pre-built core classes and
deterministic no-server tests pass." I am read-only and did not run Gradle, per constraints. I
make no claim either way.

---

## What I verified and could NOT fault

1. **Cancel-before-attach is not lost.** The mandate's specific race. `cancel()` sets
   `cancellationRequested` *before* `complete()`; `attach()` re-reads it at `595-597` and cancels
   the handle when it arrives; `execute()` re-reads it at `602-604` and returns without running.
   Both legs are covered. I tried to construct a lost-cancel interleaving and could not.
2. **No double permit release, no permit inflation.** `complete()`'s
   `completed.compareAndSet(false, true)` (`638-643`) makes `tasks.remove` + `release()`
   exactly-once across every path — reentrant callback, concurrent cancel, submission failure,
   timeout-then-cancel in `awaitGlobal`.
3. **Reentrant/synchronous callback during `submit` is safe.** `attach`'s `compareAndExchange`
   tolerates the handle already being set by the callback thread; the outer
   `attach`/`completeIfTerminal` degrade to no-ops. Verified by reading and by the deterministic
   test at `121-130`.
4. **Interrupt status is restored at every waiting site.** `awaitGlobal` (`383-386`),
   `runParallel` (`420-423`), `wait` (`326-329`). No swallowed interrupts anywhere in either
   class. This is a real improvement over the base class, which swallows in three places.
5. **`requireNonTickWait` genuinely fails closed** for `parallel` and `wait` — checked before any
   side effect in both overloads (`286`, `292`, `313`).
6. **The §2 drain strike is real and complete.** `FoliaQueueHandler.run()` throws (`42-44`) and
   `repeat()` refuses the registration (`134-138`), so the base class's blocking
   `operate()` → `queue.wait(1)` (`QueueHandler.java:165-189`) is unreachable on Folia by two
   independent mechanisms. Test at `162-170` covers the registration guard.
7. **`DispatchFuture`'s claim protocol is sound for every ordering I could construct.**
   claim-then-cancel returns `false` (`256-262`); cancel-then-run skips the callable via
   `claimCallback()` (`207-209`); the `whenComplete` bridges never complete a cancelled future
   (`186-188`, `218-220`). The `cancel(false)` — never interrupting — is the right call for work
   that may be executing on a region thread.
8. **`unwrap` (`229-238`) correctly strips `CompletionException` and `CallableDispatchException`**
   so the original cause reaches `.get()`. Verified by the `assertSame` at
   `FoliaQueueHandlerTest.java:112`.
9. **`saturatedAdd` (`547-553`) is a correct overflow-safe addition**, and the deadline arithmetic
   in both `awaitGlobal` and `runParallel` uses `System.nanoTime()` differences correctly
   (no absolute-time comparison, no wrap bug).
10. **No `BukkitScheduler` usage, no AsyncCatcher or physicsFreeze touch** anywhere in either
    class. The §1b prohibition on global toggles holds.
11. **`ConcurrentHashMap` iteration during removal** in `reapFinishedTasks`/`cancelAll` is safe
    (weakly consistent iterators; `cancelAll` additionally snapshots via `List.copyOf`).

---

## Summary of required changes for PASS

1. **BLOCKING-1** — stop dropping the registration of a repeating task that throws, or cancel the
   handle atomically with the drop. Fix `FakeScheduledTask` to model Folia's real semantics
   (exception caught, repeating task rescheduled, state → `IDLE`) and invert the assertion at
   `FoliaTaskManagerTest.java:113-117`.
2. **MAJOR-5** — fail closed if the registered `FaweThreadContext` is not Folia-aware, or add a
   direct `folia-api` ownership check in `awaitGlobal` before it parks.
3. **MAJOR-2** — replace the per-submit full scan with an event-driven or amortised reap.
4. **MAJOR-3** — give `DispatchFuture` the same owner-wait deadline `awaitGlobal` gives itself.
5. **MAJOR-4** — route the location-free sync path through `FoliaBackpressure`, or record an
   explicit accepted-risk decision that the global region is unthrottled post-drain-strike.
6. Add at least one test with a genuinely asynchronous dispatcher fake that can fail on a lost
   cancel and on a double permit release.
