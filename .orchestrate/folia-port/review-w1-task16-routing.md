# Review W1 — Task 16 — Scheduler routing & QueueHandler contract preservation

Reviewer: fresh adversarial thread (Opus). Lens: **scheduler-routing correctness (C4/APIC
mapping) and `QueueHandler` contract preservation.** Concurrency/liveness/leaks are another
reviewer's lane and are only touched where they are a *consequence* of a routing decision.

Everything below was read in the current working tree. Folia scheduler contracts were verified
against the **pinned** `dev.folia:folia-api:26.1.2.build.8-stable` jar in the Gradle cache plus
the upstream Paper implementations (`FoliaGlobalRegionScheduler`, `FoliaAsyncScheduler`,
`FoliaRegionScheduler`) — not from memory.

---

## VERDICT

**PASS-WITH-NOTES**

Every row routes to the scheduler its binding row specifies. I found **no misrouted row, no
silently collapsed row, and no unimplemented row**. All ten preserved `QueueHandler` descriptors
keep their exact signatures. Tick/ms conversion and the scheduler delay floors are correct and —
unusually — correct for the *right reason*: the guards match the actual `IllegalArgumentException`
thresholds in the pinned Folia build, which I verified rather than assumed.

The notes are real and two of them are load-bearing:

- the struck drain's **time-budget responsibility was dropped, not redistributed** (MAJOR-1);
- `syncWhenFree`'s **priority contract is not implemented** — a one-turn handicap is not priority
  (MAJOR-2);
- one test the dev record leans on **proves nothing** (MAJOR-5).

None of these is a wrong-thread/silent-corruption risk, which is why this is not a REJECT.

---

## 0. The "§3.5 C4 scheduler mapping table" does not exist

I have to open with this because my own brief and the task-16 brief both cite it as binding.

Verified by full read of `architecture.md`:

- **C4 is "Operation completion"** (`architecture.md:187`), realized by §3.6. It has nothing to do
  with schedulers.
- **§3.5 contains no scheduler mapping table.** It contains the core dispatch targets, the ten
  preserved `QueueHandler` descriptors, the `syncOn` migration surface, and a prose derivation
  rule.
- `grep -n "C4\|mapping table\|scheduler mapping" architecture.md` returns three hits, all of them
  completion-protocol references.

The worker's **Deviations** entry states exactly this and substitutes the frozen
**APIC-019–028** (`spikes/w06-api-context-audit.md:63-72`) and **APIC-092–101**
(`spikes/w06b-apic-gap-closure.md:15-24`) row contracts. **That substitution is correct** and those
tables are the right binding surface. The table below is against them.

---

## 1. Row-by-row routing table

`G` = `GlobalRegionScheduler`, `A` = `AsyncScheduler`, `R` = `RegionScheduler`,
`E` = `EntityScheduler`, `W` = FAWE worker pool.

### `TaskManager` rows (APIC-019–028)

| Row | Required target | Implementing code | Verdict |
|---|---|---|---|
| APIC-019 `repeatAsync` | A | `FoliaTaskManager.java:158` `asyncScheduler.runAtFixedRate(..., MILLISECONDS)` | **CORRECT** |
| APIC-019 `async` | A | `FoliaTaskManager.java:173` `asyncScheduler.runNow` | **CORRECT** |
| APIC-019 `laterAsync` | A | `FoliaTaskManager.java:203` `asyncScheduler.runDelayed(..., MILLISECONDS)` | **CORRECT** |
| APIC-020 `repeat` | G | `FoliaTaskManager.java:143` `globalRegionScheduler.runAtFixedRate` | **CORRECT** (drain arg → `-1` sentinel, line 134-137 — sanctioned strike) |
| APIC-020 `task` | G | `FoliaTaskManager.java:182` `globalRegionScheduler.run` | **CORRECT** |
| APIC-020 `later` | G | `FoliaTaskManager.java:192-194` `run` when `delay<=0`, else `runDelayed` | **CORRECT** |
| APIC-021 `taskNow(r,async)` | explicit A or caller | inherited `TaskManager.java:183` | **CORRECT** (not overridden) |
| APIC-021 `taskNowAsync` | A only if caller is a tick thread | `FoliaTaskManager.java:234-241` | **CORRECT** |
| APIC-022 `taskNowMain` | inline on R/E/G, else G | `FoliaTaskManager.java:224-231` | **CORRECT** |
| APIC-022 `taskSoonMain(async)` | A / G | `FoliaTaskManager.java:244-251` | **CORRECT** |
| APIC-022 `taskWhenFree` | same derivation | `FoliaTaskManager.java:254` → `taskNowMain` | **CORRECT** (base also collapses, `TaskManager.java:315`) |
| APIC-023 `cancel(int)` | scheduler owning the id | `FoliaTaskManager.java:213-221` + `TaskRegistration` | **CORRECT** — see §3 |
| APIC-024 `objectTask` | G, fragmented | inherited `TaskManager.java:263`, reaches `task`+`later` → both G | **CORRECT** |
| APIC-025 `parallel` ×2 | W pool, A/W callers only | `FoliaTaskManager.java:284,291`, tick callers rejected at 482 | **CORRECT** |
| APIC-026 `wait`/`notify` | caller-local, A/W only | `FoliaTaskManager.java:311`; `notify` inherited | **CORRECT** (see MINOR-4 on the timeout behaviour change) |
| APIC-027 `sync`/`syncWhenFree` (RunnableVal/Supplier) | inline on R/E/G, else G | `FoliaTaskManager.java:259-280` → `awaitGlobal` (`:368`) → `globalRegionScheduler.run` | **CORRECT** |
| APIC-028 `runUnsafe` | DEGRADED, fail before callback | `FoliaQueueHandler.java:140` via `TaskManager.java:166` | **CORRECT** |

### `QueueHandler` rows (APIC-092–101)

| Row | Required target | Implementing code | Verdict |
|---|---|---|---|
| APIC-092 `async(Runnable,T)` | W secondary pool | **not overridden** → `QueueHandler.java:214` | **CORRECT** |
| APIC-093 `async(Runnable)` | W secondary pool | **not overridden** → `QueueHandler.java:225` | **CORRECT** |
| APIC-094 `async(Callable)` | W secondary pool | **not overridden** → `QueueHandler.java:237` | **CORRECT** |
| APIC-095 `sync(Runnable)` | inline R/E/G else G | `FoliaQueueHandler.java:47` → `execute` (`:149`) | **CORRECT** (return-value nit: MINOR-1) |
| APIC-096 `sync(Callable)` | inline R/E/G else G | `FoliaQueueHandler.java:52` | **CORRECT** |
| APIC-097 `sync(Supplier)` | inline R/E/G else G | `FoliaQueueHandler.java:61` | **CORRECT** |
| APIC-098 `syncWhenFree(Runnable,T)` | same target, **lower priority** | `FoliaQueueHandler.java:70` → `executeWhenFree` (`:160`) | **ROUTE CORRECT / PRIORITY NOT IMPLEMENTED** — MAJOR-2 |
| APIC-099 `syncWhenFree(Runnable)` | same | `FoliaQueueHandler.java:75` | **ROUTE CORRECT / PRIORITY NOT IMPLEMENTED** |
| APIC-100 `syncWhenFree(Callable)` | same | `FoliaQueueHandler.java:80` | **ROUTE CORRECT / PRIORITY NOT IMPLEMENTED** |
| APIC-101 `syncWhenFree(Supplier)` | same | `FoliaQueueHandler.java:89` | **ROUTE CORRECT / PRIORITY NOT IMPLEMENTED** |

### §3.5 context-carrying rows

| Row | Required target | Implementing code | Verdict |
|---|---|---|---|
| `syncOn(ChunkTarget, RegionCall)` | owning region (R) | `FoliaQueueHandler.java:98-107` → `dispatcher.onRegion(world, cx, cz, …)` | **CORRECT** — exact world/chunk forwarded |
| `syncOn(ChunkTarget, RegionTask)` | owning region (R) | `FoliaQueueHandler.java:110-119` | **CORRECT** |
| `syncOn(EntityTarget, EntityTask)` | owning entity context (E) | `FoliaQueueHandler.java:122-129` → `dispatcher.onEntity(entity, …)` | **CORRECT** — exact entity forwarded |
| `syncOnGlobal(GlobalTask)` | G | `FoliaQueueHandler.java:132-137` | **CORRECT** |

`R`/`E` are reached only through `FoliaRegionDispatcher`, never directly — `FoliaTaskManager` does
not even import `RegionScheduler`/`EntityScheduler`, which is a compile-time proof rather than a
convention.

### Tick-interval semantics — verified against the pinned build

I extracted the real validation thresholds rather than trusting the javadoc:

| Folia call | Rejects |
|---|---|
| `GlobalRegionScheduler.runDelayed(delayTicks)` | `delayTicks <= 0` → IAE |
| `GlobalRegionScheduler.runAtFixedRate(initial, period)` | either `<= 0` → IAE |
| `AsyncScheduler.runDelayed(delay, unit)` | `delay < 0` → IAE (**0 is legal**) |
| `AsyncScheduler.runAtFixedRate(initial, period, unit)` | `initial < 0`, `period <= 0` → IAE |
| `EntityScheduler.execute(..., delay)` | "Any value less-than 1 is treated as 1" (floor, not throw) |

Against `BukkitTaskManager` (`worldedit-bukkit/.../util/BukkitTaskManager.java:18-45`) as the
reference contract:

| Call | Bukkit | Folia | Same? |
|---|---|---|---|
| `repeat(r, n>0)` | `(n, n)` ticks | `(n, n)` ticks | yes |
| `repeat(r, 0)` | delay 0 → next tick, period normalized to 1 | `(1, 1)` → next tick, period 1 | yes |
| `repeatAsync(r, n>0)` | `(n, n)` ticks | `(50n, 50n)` ms | yes |
| `repeatAsync(r, 0)` | delay 0, period normalized to 1 tick | `(0 ms, 50 ms)` | yes |
| `later(r, 0)` | next tick | `run()` = next tick | yes |
| `later(r, n>0)` | `n` ticks | `runDelayed(n)` | yes |
| `laterAsync(r, -1)` | normalized to 0 | `0 ms` | yes |

The asymmetric floors — `globalInitialDelay` = `max(1, …)` (`:531`) vs. `repeatAsync`'s
`max(0, …)` (`:161`) — look like an off-by-one at first glance. They are not: they are exactly the
two different platform thresholds above. **The comments at `:531-541` explain this correctly.**

The `delay <= 0` guard at `FoliaTaskManager.java:192` is load-bearing:
`FaweBukkit.java:86` calls `later(this::setupPlotSquared, 0)`, which without the guard would throw
`IllegalArgumentException` at boot. Correctly handled.

---

## 2. The ten preserved `QueueHandler` methods

Signature = exact source + erasure match against `QueueHandler.java:214-339`.

| # | Descriptor | Signature preserved | Contract preserved |
|---|---|---|---|
| 1 | `<T> Future<T> async(Runnable, T)` | **yes** (inherited) | **yes** |
| 2 | `Future<?> async(Runnable)` | **yes** (inherited) | **yes** |
| 3 | `<T> Future<T> async(Callable<T>)` | **yes** (inherited) | **yes** |
| 4 | `<T> Future<T> sync(Runnable)` | **yes** | **no** — inline path returns `completedFuture(null)`; base returns `Futures.immediateCancelledFuture()` (MINOR-1) |
| 5 | `<T> Future<T> sync(Callable<T>) throws Exception` | **yes** (`throws` retained) | **yes** — checked exception propagates synchronously on the inline path, through the `Future` otherwise, exactly as the base |
| 6 | `<T> Future<T> sync(Supplier<T>)` | **yes** | **yes** |
| 7 | `<T> Future<T> syncWhenFree(Runnable, T)` | **yes** | **no** — priority not implemented (MAJOR-2) |
| 8 | `<T> Future<T> syncWhenFree(Runnable)` | **yes** | **no** — priority (MAJOR-2) + return value (MINOR-1) |
| 9 | `<T> Future<T> syncWhenFree(Callable<T>) throws Exception` | **yes** (`throws` retained) | **no** — priority (MAJOR-2) |
| 10 | `<T> Future<T> syncWhenFree(Supplier<T>)` | **yes** | **no** — priority (MAJOR-2) |

**Binary compatibility: intact.** No signature widened, narrowed, or re-generified; no `throws`
clause added; no return type changed; no method made less accessible. A third-party plugin
compiled against the old API links and runs. The only cross-backend observable differences are the
two behavioural ones above.

Also confirmed: because all seven sync/syncWhenFree descriptors are overridden, the base's
`syncTasks` and `syncWhenFree` `ConcurrentLinkedQueue`s (`QueueHandler.java:92,97`) are **never
populated** on Folia. There is no orphaned queued work waiting for a drain that will never run —
this was the obvious way to get this wrong and it was avoided.

---

## 3. `cancel(int)` bridge

**Round-trip per submit shape:**

| Shape | Exposes an id? | Round-trip |
|---|---|---|
| `repeat` | yes | **works** — `FoliaTaskManagerTest.java:32-36`; real consumer at `Regenerator.java:107` |
| `repeatAsync` | yes | **works** — test `:37-40` |
| `task` / `async` / `later` / `laterAsync` | **no — `void` in the base** | N/A by contract (APIC-019/020: "void submissions have no handle") |
| entity / region | **no such shape on `TaskManager`** | correctly vacuous — R/E live behind the dispatcher (task 12) |

**Can `cancel(id)` cancel the WRONG task?** I attacked this specifically.

- `nextId()` (`:523`) wraps `Integer.MAX_VALUE → 1`, so ids *are* recyclable in principle.
- But `register()` (`:497-505`) uses `putIfAbsent` in a bounded retry loop, and the map is
  capped at `maxTrackedTasks` by a semaphore. **An id belonging to a live registration can never
  be handed out again.** The retry loop is guaranteed to terminate: with ≤ 65 536 occupied ids and
  65 537 attempts, a free id must be found.
- Residual ABA: task finishes → id freed → **2³¹ further submissions** → id reused → a stale
  `cancel(oldId)` hits an unrelated task. This is the same exposure Bukkit has and requires ~2.1
  billion submissions. **Not a practical attack.**

**Can a task be cancelled before its handle is attached?** No.
`TaskRegistration.cancel()` (`:616`) sets `cancellationRequested` *before* reading the handle;
`attach()` (`:589`) re-checks `cancellationRequested` after the CAS and cancels. `execute()`
(`:600`) also re-checks before running. The three-way race is closed. Test coverage at
`FoliaTaskManagerTest.java:121-142` (fire-before-return, cancel-before-return).

**Handle identity assumption:** `attach()` throws if the scheduler hands the callback a different
`ScheduledTask` than it returned. I verified this holds — Folia's scheduled task invokes
`this.run.accept(this)` (`folia-server` Region-Threading-Base patch, line 7308). The assumption is
sound, though it is undocumented in the API.

**Bounded map drains:** verified by `FoliaTaskManagerTest.java:89-118` for one-shot completion,
failed repeats, and `:145-160` for capacity return after cancellation. H-LEAK discharged.

---

## 4. What the struck global drain used to do, and who does each part now

`QueueHandler.run()` (`QueueHandler.java:117-138`) + `operate()` (`:165-189`) +
`getAllocate()` (`:150-163`) did **five** things. Redistribution:

| Old responsibility | Who does it now |
|---|---|
| 1. Drain `syncTasks` on the main thread | **Dissolved correctly.** Each `sync(...)` is submitted directly as its own `GlobalRegionScheduler` task (`FoliaQueueHandler.java:202-227`). No queue, no drain. |
| 2. Drain `syncWhenFree` only after `syncTasks` is empty | **Replaced by a weaker mechanism** — a one-global-turn gate (`:177-200`). See MAJOR-2. |
| 3. **Enforce a per-tick time budget** (`getAllocate()`: adaptive 5–50 ms, TPS-aware via `Fawe.instance().getTimer().isAbove(18)`), stopping the drain loop mid-queue | **DROPPED. Nobody does this.** See MAJOR-1. |
| 4. `MemUtil.isMemoryFree()` memory-pressure hook | Dropped — but it was an empty `TODO` block upstream (`:126-130`). No loss. |
| 5. Wake blocked submitters (`notifySync` / `queue.wait(1)`) | **Obsolete correctly** — no queue exists to wait on. |

So: three of five correctly dissolved or obsolete, one weakened, **one silently dropped**.

Item 3 is the one that matters, and it is the answer to the question my brief asked. Detail in
MAJOR-1.

---

## 5. Findings

### MAJOR-1 — The drain's per-tick time budget was dropped, not redistributed

`FoliaQueueHandler.java:171-227`; orphaned code at `QueueHandler.java:150-189`.

The old drain executed queued sync work **inside a bounded time slice**:
`do { … } while (System.currentTimeMillis() - start < currentAllocate)` with `currentAllocate`
adaptively clamped to `[5, 50]` ms and reduced whenever TPS fell below ~18. That is FAWE's primary
TPS-protection mechanism for main-thread work.

On Folia every off-tick `sync`/`syncWhenFree` becomes **its own unbudgeted
`GlobalRegionScheduler` task**. Folia executes all tasks queued for a global tick in that tick.
There is no slice, no cap, no TPS feedback, no re-arm.

**Scenario.** A `//set` across ~10 000 chunks whose finalizers route through
`QueueHandler.sync(...)` from FAWE workers. Under Paper, ≤ 50 ms of that work runs per tick and
the remainder carries over. Under Folia, every submission that lands before the global tick
boundary executes in one global tick. Global-region MSPT spikes with no back-pressure from the
scheduling layer.

`getAllocate()` is now **dead code on the Folia path** (private, called only from `run()`, which
throws). `allocate`/`last` never update.

**Architecture C6 explicitly requires a replacement:** *"`AsyncPreloader` TPS>18 and
`QueueHandler.getAllocate()` get Folia equivalents keyed on the target region."* None exists.

Task 16's declared binding references are §2, §3.5, §1b and §4c — C6 is not among them, so I do
**not** treat this as a task-16 defect. But it is not in the dev record's Deviations or Attack
Points either, so right now **nobody owns it**. It needs an explicit disposition (a C6 task, or a
signed amendment) before wave 2 relies on this path.

### MAJOR-2 — `syncWhenFree` priority is a fixed one-turn handicap, not priority

`FoliaQueueHandler.java:177-200`.

The base contract (javadoc at `QueueHandler.java:288-297`, enforced at `:123-137`) is
*strict*: `syncWhenFree` work runs **only when `syncTasks` is empty**, and then **only if time
remains in the tick**. APIC-098–101 restate it as "lower priority within that target".

The implementation submits an **empty** global task as a gate, then submits the real callable from
the gate's completion. That yields a fixed one-global-turn head start for `sync`. It is not
priority.

**Scenario.** Worker W1 calls `syncWhenFree(A)` at t₀; the gate is queued for tick N+1. The gate
fires during tick N+1 and A's real submission is enqueued from the FAWE completion executor.
Worker W2 calls `sync(B)` during tick N+1. Both land in the global queue for tick N+2 in a
**racy order** — A may precede B. Under Paper, B is guaranteed to run first, and A only after
`syncTasks` fully drains. The ordering guarantee is lost.

Note the trade is not one-sided: the Folia version **cannot starve** `syncWhenFree`, which is
better for liveness than the base. But the dev record's claim that the gate is
*"a priority gate … keeping normal sync work ahead"* is only true for work submitted strictly
earlier, and that qualification is not stated.

Secondary cost: **every** off-tick `syncWhenFree` costs two global-region tasks. The test at
`FoliaQueueHandlerTest.java:79` asserts `globalCalls == 11` for 7 calls, so this is measured and
intentional — but uncoalesced.

### MAJOR-3 — `FoliaTaskManager` is a second, un-lifecycled path to the Folia schedulers

`FoliaTaskManager.java:143,158,173,182,192,203,379`.

`FoliaRegionDispatcher` is documented as *"The ONE class that talks to Folia schedulers"*
(`FoliaRegionDispatcher.java:34`). `FoliaTaskManager` legitimately talks to `GlobalRegionScheduler`
and `AsyncScheduler` directly — §2 sanctions this (*"`FoliaTaskManager.java` — TaskManager impl on
Folia schedulers"*), so it is **not** a C1 violation. But it means those submissions are invisible
to `stopAccepting()`, `drain(Duration)`, `liveTickets()`, and `outstandingFutures()`.

Concrete consequence at shutdown: `awaitGlobal` (`:368-393`) blocks a FAWE worker on a
`CompletableFuture` completed only by the global callback. `cancelAll()` (`:348`) cancels the
*handle* — so the callback never runs and **the future is never completed**. The worker blocks
for the full `ownerWaitTimeoutNanos` (default **60 s**, `:45`) before throwing. Meanwhile
`DrainReport` reports `unresolvedTasks = 0` because the dispatcher never saw the task.

§3.6 requires bounded shutdown drain. The fix is small: have `TaskRegistration.cancel()` complete
the pending `awaitGlobal` future exceptionally. The dev record's *"Task 17 must call `cancelAll()`
during shutdown"* is necessary but not sufficient.

### MAJOR-4 — `register()` performs an O(n) scan on every single submission

`FoliaTaskManager.java:488-521`.

`register()` calls `reapFinishedTasks()`, which iterates the entire `tasks` map and performs a
`getExecutionState()` read per entry. This runs on **every** `submit()` — i.e. every `task()`,
`async()`, `later()`, `laterAsync()`, `repeat()`, and every `TaskManager.sync()` via `awaitGlobal`.
`BukkitTaskManager` is O(1) on all of these.

Live recurring callers make this concrete: `AsyncPreloader` reschedules itself with
`laterAsync(this, 1)` (`AsyncPreloader.java:34,94`) — every 50 ms, each time scanning the whole
map. With the 65 536 bound this is up to 65 536 volatile reads per submission.

Reaping is necessary (Folia exposes no completion listener, as the dev record correctly notes) —
but amortizing it (reap every N registrations, or on capacity pressure only) would remove the
per-submission cost entirely.

### MAJOR-5 — The "ten descriptors preserved" test proves nothing, and the dev record cites it as proof

`FoliaQueueHandlerTest.java:35-46`.

The test asserts only `method(...).getReturnType() == Future.class` for each of the ten
descriptors. That assertion **cannot fail for any legal override** — Java forbids changing the
erased return type of an override. It does not check `getDeclaringClass()`, does not check the
`throws` clause, and does not check that `async(...)` is left un-overridden.

A hypothetical `FoliaQueueHandler.async(Callable<T>)` overridden to route to
`dispatcher.onRegion(...)` — i.e. detached worker payloads dumped onto a region tick thread — would
**pass this test**.

This matters beyond test hygiene: the dev record cites this exact test
(`FoliaQueueHandlerTest.java:35`) as the evidence for **APIC-092–094** *"the three public async
descriptors are inherited verbatim and stay on QueueHandler's detached FAWE secondary pool"*.
The test establishes neither "inherited" nor "secondary pool". The claim happens to be **true** —
I verified it by reading the source; `async` is genuinely not overridden — but it is **unevidenced
by the cited artifact.**

One-line fix: `assertSame(QueueHandler.class, method("async", Callable.class).getDeclaringClass())`.

### MINOR-1 — `sync(Runnable)` / `syncWhenFree(Runnable)` inline return value diverges from the base

`FoliaQueueHandler.java:150-153, 161-164` vs. `QueueHandler.java:393-403`.

On the inline (tick-thread) path the base returns `Futures.immediateCancelledFuture()`; Folia
returns `CompletableFuture.completedFuture(null)`. `handler.sync(runnable).get()` therefore throws
`CancellationException` on Paper and returns `null` on Folia.

I checked the provenance: `immediateCancelledFuture()` is **pre-existing upstream FAWE**, not
introduced by the port (`git diff` on `QueueHandler.java` only swaps the predicate). It is also
inconsistent with the base's own queued path, which completes with `null`. So the Folia behaviour
is arguably the *correct* one and the base is the latent bug — but the divergence is real,
observable, and lands on 2 of the 10 "PRESERVED" descriptors. Either fix the base or record the
divergence.

### MINOR-2 — `syncOn` hardcodes `TaskKind.FINALIZER` for every chunk and entity target

`FoliaQueueHandler.java:104, 116, 126`.

§3.5 designates `syncOn` as *the* migration target for all internal callers. Every one of them will
be instrumented as `FINALIZER`. §3.3 makes `TaskKind` the key for the §8 per-kind timers (schedule
delay, callback runtime) feeding the G3/G4 producers, so **nothing routed through
`QueueHandler.syncOn` can ever be attributed to `SNAPSHOT_CAPTURE`, `COMMIT`, or `PACKET`.**

`TaskKind` is explicitly "not an authorization", so this is not a safety issue — but the `syncOn`
signature (frozen in the base, `QueueHandler.java:350-379`) has no `TaskKind` parameter, so it
cannot be fixed here. Worth recording against the §8 perf-certification workstream.

### MINOR-3 — The drain strike is enforced in the wrong class and is bootstrap-order-fragile

`FoliaTaskManager.java:134-138`.

`QueueHandler`'s constructor unconditionally calls `TaskManager.taskManager().repeat(this, 1)`
(`QueueHandler.java:109`). The strike is implemented as `runnable instanceof FoliaQueueHandler` in
`FoliaTaskManager.repeat`.

**Scenario.** If `FoliaQueueHandler` is ever constructed while `TaskManager.INSTANCE` is not the
`FoliaTaskManager` — e.g. a `BukkitTaskManager` was created first during bootstrap — a **real
repeating drain is installed**, and `FoliaQueueHandler.run()` (`:42`) throws
`UnsupportedOperationException` **every tick, forever**.

The dev record flags the ordering dependency for task 17, and the test at
`FoliaQueueHandlerTest.java:162-170` proves the strike under the correct order. But
`FoliaQueueHandler` itself has no defensive check, and the subclass genuinely cannot intercept its
own superclass constructor — so the `instanceof` hack is the only option short of changing the
base. Recording it so task 17 tests the failing order, not just the passing one.

### MINOR-4 — `wait(AtomicBoolean, int)` changes from "never throws" to "throws on timeout"

`FoliaTaskManager.java:311-332` vs. `TaskManager.java:287-302`.

The base loops until the flag clears, logging after 60 s, and **never throws**. The Folia override
throws `IllegalStateException` on timeout and `IllegalArgumentException` for `timeout <= 0`.
APIC-026 permits "explicit timeout", so the row is discharged; both methods are
`@Deprecated(forRemoval)`; I found no internal callers. Low impact, but it is a behaviour change on
a public method.

### MINOR-5 — `runUnsafe` degradation omits the required caption

`FoliaQueueHandler.java:26-27, 140-147`.

§3.5 and APIC-028 specify *"`UnsupportedOperationException` + caption"*. The implementation throws
with a plain English string; no FAWE caption / `TranslatableComponent` is emitted. The fail-fast
timing is correct (before the callback — `TaskManager.java:166` is outside the `try`).

### MINOR-6 — `cancel(int)` tests exercise a path production cannot reach

`FoliaTaskManagerTest.java:45, 50, 55, 60`.

`assertCancellable(fixture, fixture.manager.taskId(fixture.schedulers.lastTask))` recovers an id
via the package-private `taskId(ScheduledTask)` helper (`FoliaTaskManager.java:339`). But
`task`/`later`/`async`/`laterAsync` are `void` — no production caller can obtain that id. The
genuine public round-trip is proven only for `repeat`/`repeatAsync`. Not wrong, just weaker
evidence than it appears.

### MINOR-7 — The scheduler fake conflates the two schedulers

`FoliaTaskManagerTest.java:366` — `RecordingSchedulers implements GlobalRegionScheduler,
AsyncScheduler`, and the **same instance** is passed as both constructor arguments
(`:280, :147, :296-303`).

A field mix-up (async scheduler stored in the `globalRegionScheduler` field) would be undetectable.
Real misrouting *is* still caught because the six routes map to six distinct method signatures —
so the test retains its value — but two separate fakes would make the proof airtight.

### MINOR-8 — `repeat` silently no-ops for any `FoliaQueueHandler` argument

`FoliaTaskManager.java:134-137` returns `-1` for *any* `FoliaQueueHandler`, not just the bootstrap
registration. Third-party code doing `taskManager.repeat(queueHandler, 20)` gets a silent no-op.
Vanishingly unlikely; noted for completeness.

---

## 6. Test quality verdict

**Adequate for routing; weak on contract preservation.**

**Genuinely strong — these would fail against wrong routing:**

- `routesEveryAbstractTaskManagerShapeToItsC4Scheduler` (`:29-61`) asserts the **route enum, the
  delay, the period, and the TimeUnit** for all six submit shapes. This is exactly the right shape
  of test — it would catch a scheduler swap, a unit confusion, and a delay off-by-one.
- `nonPositiveDelayUsesTheDocumentedSchedulerFloors` (`:64-86`) pins the four zero/negative floors
  to exact values. I independently verified all four against the pinned Folia thresholds; they
  match.
- `contextCarryingRowsRouteToTheExactDispatcherTarget` (`FoliaQueueHandlerTest.java:131-159`)
  asserts the exact world, chunkX, chunkZ, and entity identity forwarded to the dispatcher — a real
  routing proof, not a "something was scheduled" assertion.
- `queueConstructionDoesNotInstallTheLegacyGlobalDrain` (`:162-170`) genuinely proves the strike.
- `RecordingSchedulers.execute()` throwing `AssertionError` (`:378-380`) is a nice negative
  assertion: it proves the handle-less `execute()` overload is never used, so every submission
  stays cancellable.
- `syncWhenFreeDefersItsCallbackByOneGlobalOwnerTurn` (`:83-100`) honestly tests what was actually
  built — the test name does not overclaim.

**Would pass against a wrong routing:**

- `preservesAllTenPublicQueueHandlerDescriptors` (`:35-46`) — see MAJOR-5. **Vacuous.**
- `locationFreeSyncRowsUseTheGlobalDispatcherOffTick` (`:66-81`) asserts `dispatcher.kind` **once,
  after all seven calls** — only the last recorded value. Six of the seven kinds are unasserted.

**Missing:**

- No test that a `sync` submitted while a `syncWhenFree` gate is pending actually runs first — i.e.
  the priority contract of APIC-098–101 is untested (consistent with MAJOR-2: it isn't implemented).
- No test of the bootstrap-order failure mode in MINOR-3.
- No test pinning `getDeclaringClass()` for the three `async` descriptors.

---

## 7. Dev-record accuracy

**Mostly accurate, with one unsupported evidentiary claim and one omission.**

| Claim | Verdict |
|---|---|
| Deviation: "the current architecture's C4 is operation completion and §3.5 has no scheduler table" | **CORRECT.** I verified this by full read + grep. The task brief is wrong; the record is right. |
| Deviation: substituting APIC-019–028 / APIC-092–101 as the row contracts | **CORRECT** and appropriate. |
| APIC-019 "tick values converted to 50 ms units" | **CORRECT** — `Math.multiplyExact(ticks, 50)` at `:543`. |
| APIC-020 "the inherited QueueHandler drain registration alone returns the struck `-1` sentinel" | **CORRECT** but see MINOR-3 and MINOR-8. |
| APIC-023 "bounded to 65 536 live handles, race-safe before handle attachment, releases capacity" | **CORRECT** — I attacked all three and could not break them. |
| APIC-025/026 "reject tick callers, restore interruption, aggregate failures" | **CORRECT** — `requireNonTickWait` at `:482`, interrupt restoration at `:385, :422, :327`. |
| APIC-028 "no global safety switch is changed" | **CORRECT** — no `AsyncCatcher` or `physicsFreeze` reference anywhere in the module. |
| APIC-092–094 "inherited verbatim … stay on the FAWE secondary pool (`FoliaQueueHandlerTest.java:35`)" | **Claim true, citation invalid.** The cited test proves neither property (MAJOR-5). |
| APIC-098–101 "a first G owner turn is a priority gate … keeping normal sync work ahead" | **Overstated.** True only for strictly-earlier submissions (MAJOR-2). The record does not state that qualification. |
| Attack point: "Task 17 must call `cancelAll()` during shutdown" | **Necessary but insufficient** — `cancelAll()` does not release `awaitGlobal` waiters (MAJOR-3). |
| Deviation: "No mapped row requests a sub-tick entity delay" | **CORRECT.** `FoliaTaskManager` never reaches `EntityScheduler`; the entity row goes through the dispatcher with no delay parameter. The `EntityScheduler.execute` 1-tick floor is genuinely not exercised here. |
| **Omission** | The dropped `getAllocate()` time budget (MAJOR-1) appears in neither Deviations nor Attack Points despite C6 mandating a replacement. |

Status `DONE_WITH_CONCERNS` is the honest classification.

---

## 8. What I verified and could NOT fault

- **Every delay and period value** that can reach a Folia scheduler is in range for the pinned
  26.1.2 validation thresholds, which I extracted from the actual implementations rather than
  assuming. No call shape can throw `IllegalArgumentException`, including the `later(r, 0)` boot
  path that would otherwise break `FaweBukkit.java:86`.
- **Tick↔ms conversion** is exactly 50 ms/tick using `Math.multiplyExact` — overflow-safe, no
  silent wraparound (`:543`).
- **No unit confusion anywhere.** Tick-domain schedulers get ticks; the ms-domain `AsyncScheduler`
  gets ms with an explicit `TimeUnit`. The tests assert the `TimeUnit` argument, not just the
  number.
- **`attach()`'s handle-identity assumption holds** — Folia's scheduled task invokes
  `this.run.accept(this)`, so the consumer receives the same instance the scheduler returned.
- **Id reuse cannot cancel a live wrong task** — `putIfAbsent` plus the semaphore bound makes
  collision with a live registration impossible, and the retry loop provably terminates.
- **Cancellation before handle attachment is closed** across all three windows (submit / attach /
  execute).
- **The base's `syncTasks` / `syncWhenFree` queues are never populated on Folia** — all seven sync
  descriptors are overridden, so no work is stranded behind the removed drain. This was the most
  obvious way to get the strike wrong and it was avoided.
- **`FoliaTaskManager` imports no `RegionScheduler`, `EntityScheduler`, or `BukkitScheduler`** — a
  compile-time guarantee that no `TaskManager` row can reach a region or entity thread.
- **`runUnsafe` fails before the callback**, as §3.5 requires — the `startUnsafe` call sits outside
  the `try` in `TaskManager.java:166`.
- **`sync(Callable)` checked-exception semantics** match the base on both the inline and queued
  paths, and `CallableDispatchException` is fully unwrapped so callers see the original cause
  (`FoliaQueueHandler.java:229-238`; proven at `FoliaQueueHandlerTest.java:103-114`).
- **`DispatchFuture`'s claim/cancel protocol** (`:248-263`) is correct: a cancelled future's
  callback provably never runs, and a completed future cannot be retroactively cancelled.
- **No `BukkitScheduler` and no global sync-drain in the module** (accepted from the orchestrator's
  grep, not re-verified — I checked the *redistribution* instead, §4).
