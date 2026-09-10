# Task 14 — FoliaCommitBroker lanes + DiagnosticSnapshot producer (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** task 11 (OperationCompletion sink), task 12 (FoliaRegionDispatcher +
  ticket counters), task 13 (FoliaBackpressure). Consumed by task 17 (wiring) and wave 2
  (chunk pipeline plugs prepared commits into these lanes).
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: queue pipeline /
  scheduler SPI. **Double-reviewer (concurrency & thread-ownership + chunk write path)** —
  the throughput engine and the G4 diagnostic seam.

## Mandate
Implement the lane/mailbox engine frozen in architecture §3.7: one lane per observed region,
sliced region-sweep drain, weighted deficit-round-robin interleaving, rebind + permit transfer
on split/merge, and the frozen **G4 `DiagnosticSnapshot` producer** (§3.4). Scope is the lane
MACHINERY operating over an abstract commit unit — the concrete prepared-GET/SET adapter
content is **wave 2**; this task builds the structure wave 2 fills.

## In scope
- `FoliaCommitBroker` implementation (folia module; skeleton exists with a
  `(dispatcher, backpressure)` constructor — extend it):
  - One **lane (mailbox) per observed region** (internal `RegionKey`); a large operation does
    not require one scheduling call per chunk.
  - **Sliced region-sweep drain:** each region tick commit up to an adaptive cap of ready
    chunks, then re-arm if backlog remains. At drain time: obtain the actual current region,
    merge mailboxes now resolving to the same region, `ownsChunk` per candidate, process owned
    candidates, **rebind unowned ones** (transfer the permit via `FoliaBackpressure.transfer`,
    retain the global accepted reservation, redispatch as a discovery anchor). If transfer
    cannot settle before the deadline, the plan terminates **without mutation** (base-stamp
    validation is never waived by rebind — F6, enforced in wave 2's commit phases).
  - **Adaptive slice controller (R7):** per-tick commit-time budget, never a fixed chunk count.
    Target owner time 750 µs; "do not begin another chunk" 1.5 ms; initial cap 8; min 1; max 32;
    every 32 slices grow +2 cap / +125 µs (≤32 / ≤1.5 ms) when backlog exists, schedule delay
    ≤ 1 tick + 5 ms, slice p95 < target; halve (floors 1 chunk / 250 µs) when schedule delay
    > 75 ms or a slice exceeds 1.5 ms. The synchronous section/tile portion of one chunk is
    never split. Controls use only that lane's queue depth, observed schedule delay, measured
    runtime — **never global TPS** (C6). Final numbers `[NEEDS-RUNTIME]` (config-driven).
  - **Interleaving (DRR):** Interactive 4 / Normal 2 / Bulk 1 cost units; at most four
    consecutive chunks per operation before the next is considered; same-chunk tickets execute
    in registration order across all operations; disjoint chunks may overtake; no global
    ordering. Finalizer continuations re-enter the same mailbox under the same fairness policy
    but draw from reserved continuation capacity (task 13).
  - **Frozen G4 producer (§3.4):** `DiagnosticSnapshot diagnostics(RegionKey region)` and
    `DiagnosticSnapshot diagnosticsGlobal()`, aggregating `FoliaBackpressure.pressure(...)` +
    the dispatcher's ticket mint/retire counters + the broker's own future/rebind/outstanding
    accounting. Fields exactly per §3.4 (`region, readyChunks, readyBytes, waiters,
    finalizerChains, packetMailboxBytes, scheduledDrains, liveTickets, outstandingFutures,
    outstandingChunks, rebindCount, oldestReadyNanos`). Emit the `FAWE_QUEUE
    depth=/inflight=/outstanding=/region=` line per the exact mapping in §3.4 (depth=readyChunks,
    inflight=scheduledDrains+finalizerChains, outstanding=outstandingChunks,
    region=worldId:observedRegionId or `global`).
- `RegionKey` (folia module): primary author here — define identity/equality (lane grouping,
  never region-ID as proof). Reconcile with task 13's minimal contract (single-writer per
  architecture §5; orchestrator sequences the two).
  - **HARD PRECONDITION (promoted from a reconciliation note by the task-13 admission review,
    2026-07-20 — orchestrator-adopted, do NOT treat as advisory).** `RegionKey` is currently an
    empty class with identity equality, carrying only the comment "derived from live Folia
    ownership at drain time". Task 13's entire accounting is keyed on it via a
    `Map<RegionKey, RegionState>` with `regions.get(...)` / `regions.remove(key, value)`.
    Therefore:
    1. **Value equality is mandatory.** If this task mints a fresh `RegionKey` per drain, every
       `regions.get()` misses, a new `RegionState` is created per attempt, and **every per-region
       cap silently degenerates into a per-attempt cap** — the 256 ready chunks / 64 MiB prepared
       / 64 finalizer chains / 256 waiters bounds stop existing while every counter still reads
       healthy. That is the backpressure gate failing open, invisibly.
    2. **It must be immutable, with a stable hash.** A mutable key makes
       `regions.remove(key, state)` fail silently, so idle-region eviction never reclaims and the
       region map grows without bound on a long-running server.
    3. Two `RegionKey`s must compare equal exactly when they denote the same live region for
       accounting purposes, and that must survive the merge/split rebind path (§3.7) — the very
       events that make region identity change.
    Deliver a test that a `RegionKey` re-derived for the same region equals the original and hits
    the same `RegionState`. If any of the three cannot hold, STOP (NEEDS_CONTEXT): task 13's
    accounting must then be re-keyed, which is an orchestrator decision, not a broker one.

## HARD CONSTRAINT ON THE REJECTION HOOK (added 2026-07-20, from the task-13 re-review)

**Do not wire the backpressure rejection hook to the `NOT_ACCEPTED` sink unconditionally.** The
hook currently fires **indistinguishably** for a `cancel` outcome and a `reject` outcome. Wiring it
to `NOT_ACCEPTED` — the obvious reading — would make a caller-initiated `//cancel` terminalize the
plan *behind* the outcome CAS, **burning the plan sequence of a still-viable operation**. That is
exactly what architecture ruling r14 Q1 forbids: `reject` terminalizes `NOT_ACCEPTED` and forces a
new plan sequence, `cancel` deliberately does not.

Required: the hook must distinguish the two outcomes before terminalizing anything, and only a
`reject` outcome may reach `rejectAdmission(...)`. If the hook's current signature cannot carry
that distinction, that is a coordination point with task 13 — STOP and escalate rather than
guessing, because guessing here silently re-opens a defect the architecture spent a ruling closing.

Related: `transfer` returns false both when the target is genuinely saturated **and** when its
`tryLock` merely missed under contention. §3.7 rebinds on every drain, so lock contention becomes
user-visible plan deferral. **Define the retry contract** — a refusal that means "busy, retry" must
not be treated as "cannot admit, terminate without mutation".

## Out of scope
- Concrete GET snapshot capture / SET section install / finalizer NMS content — that is wave 2
  (the phase order §3.6 executes there). This task drains an abstract "ready commit unit" with a
  placeholder commit action injected by wave 2.
- `FoliaSnapshotCache` internals (wave 2; skeleton stays a shell).
- Backpressure accounting internals (task 13) — call the SPI, do not reimplement.
- G-A2 viewer-near ordering graft (§3.8 — config-gated, `[NEEDS-RUNTIME]`, not wave 1 unless
  separately dispatched).

## Binding references
- architecture.md §3.7 (lanes/slicing/interleaving/GET lifecycle — implement the lane +
  slicing + DRR + rebind parts; GET lifecycle is wave 2), §3.4 (frozen `DiagnosticSnapshot`
  producer + `FAWE_QUEUE` mapping), §3.6 (phase order and terminalization it must respect at
  the boundaries — wave 2 fills phases), §1b (tick threads never wait on FAWE), C6.
- `spikes/w02-pipeline-results.md` (region-sweep ranked first: 4 tasks vs 8/16, ~364 µs max;
  visibility ≈ 1 tick scheduler delay), `spikes/w08-perf-budgets.md` §6b (probe-fillable
  reference numbers; G4 `FAWE_QUEUE` producer wiring).
- In-tree: `FoliaCommitBroker.java` (skeleton), `RegionKey.java`, `FoliaRegionDispatcher.java`,
  `FoliaBackpressure.java`.

## Signature / structural flags
- **Wiring constraint from the task-13 cross-review (2026-07-17):** the backpressure
  rejection hook (task-11 completion-sink wiring) executes inside the backpressure
  completion-drain path — it must NEVER do blocking terminalization work directly; hand off
  to the completion service (§3.6b) instead.
- `DiagnosticSnapshot` **now exists in tree** (added by certification corrective C-F1F2,
  2026-07-17): frozen-shape record beside the broker in `FoliaCommitBroker.java`, plus
  `diagnostics(RegionKey)` / `diagnosticsGlobal()` skeleton methods on the broker. Implement
  the producers against these; do not re-declare the record.
- The broker skeleton constructor is `(FoliaRegionDispatcher, FoliaBackpressure)` — the
  completion sink (task 11) and a wave-2 commit-action injector are additional collaborators;
  extend the constructor/wiring and flag the shape for task 17.
- `liveTickets`/`outstandingFutures` require counters exposed by the dispatcher (task 12). If
  task 12 has not exposed them, flag the missing accessor (NEEDS_CONTEXT) rather than guessing.

## Files to touch
- `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaCommitBroker.java`
  — extend (the `DiagnosticSnapshot` record + skeleton producer methods are already in this
  file). May touch `RegionKey.java` (primary author; coordinate task 13).

## Context & decisions
- Correctness > performance (spec §4b): rebind/transfer that cannot settle terminates WITHOUT
  mutation; the slice timer never splits a chunk's synchronous section.
- Async-first; tick threads never block (spec §1b); continuations use reserved capacity.
- Region IDs are hints, never proof — grouping re-derived from live ownership at drain time.

## Executor constraints
- Folia module compiles at Java 25. No git commits. No FQN inline. English. Gradle always
  `--no-configure-on-demand`. `// Folia port:` markers only where replacing an existing call.

## Escape hatch
If wave-1/wave-2 boundary is unclear for a given phase (what the broker drains vs what the
adapter commits), STOP at that boundary, deliver the lane machinery with a clearly-typed
injection seam for the wave-2 commit action, and report the seam (NEEDS_CONTEXT) — do not build
speculative adapter/NMS content that wave 2 owns.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- Slice controller uses only lane-local signals (no global TPS reference — grep clean, C6).
- DRR fairness + same-chunk registration order hold under a deterministic multi-op test.
- `DiagnosticSnapshot` fields populate correctly; the `FAWE_QUEUE` line matches the §3.4 mapping
  exactly (verified against the harness G4 parser).
- Rebind on simulated split/merge terminates un-transferable plans without mutation.

## Test / verification strategy
Targeted deterministic tests (spec §6): DRR fairness, slice-cap adaptation, rebind/transfer,
diagnostic-line format. Runtime via harness perf-probe + pipeline-probe post-wiring:
`JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
harness/scenario.sh perf-probe --version 26.1.2` and `... pipeline-probe --version 26.1.2`
(G4 `FAWE_QUEUE` emission asserted). Slice numbers `[NEEDS-RUNTIME]` (config-driven).

---
## Dev record (worker fills this in on completion)

- **Status:** DONE_WITH_CONCERNS
- **File List:**
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/RegionKey.java:12`
    — immutable `(worldId, observedRegionId)` value key with stable equality/hash/string form.
  - `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaCommitBroker.java:47`
    — plan-producer registration, region lanes, adaptive slices, DRR, rebind/transfer,
    target-lane sync seams, diagnostics, and rejection handoff.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/FoliaCommitBrokerTest.java:50`
    — 14 deterministic broker tests.
  - `worldedit-bukkit/folia/src/test/java/com/fastasyncworldedit/bukkit/folia/DefaultFoliaBackpressureTest.java:108`
    — re-derived `RegionKey` accounting test.
  - `.orchestrate/folia-port/tasks/14-commit-broker-lanes.md:172` — this record.
- **Deviations:**
  - Numeric slice slots remain injected through `SliceTuning` and retain the architecture's
    initial `[NEEDS-RUNTIME]` values. No runtime freeze was made here.
  - `RegionKey()` remains package-private solely for pre-existing task-13 synthetic fixtures;
    production wiring must use `RegionKey(UUID, long)`.
  - Per-region broker-owned diagnostic counts scan the live plan registry. The drain-expiry
    terminalizer does not: it reads one prebuilt immutable `ChunkTerminalRecord` atomically.
  - Gradle and runtime probes were not run, per the dispatch constraint. Java 25 `javac` compiled
    the targeted sources/tests with `-Werror`; a full Folia-main `javac` pass succeeded with the
    two existing dispatcher-overload and queue-exception serial warnings.
- **Attack points:**
  - Registration and lane publication are deliberately package-private; task 17 must not expose
    `RegionKey` or Folia API types through core/Bukkit signatures.
  - A wave-2 `CommitAction` must run one unsplittable owner section, return `CommitOutcome`, and
    must not throw after its first mutation. It updates the O(1) expiry record through
    `updateDrainTerminal(RegisteredPlan, ChunkTerminalRecord)` at truthful phase boundaries.
  - Rebind success is sent through the injected `NonBlockingHandoff` before target-lane enqueue.
    This prevents an owning stale-lane drain from recursively running a second target-lane slice
    in the same tick without taking the completion service's blocking lifecycle lock.
  - Transfer `RETRY_BUSY` and `TARGET_SATURATED` retry off the owner thread until the permit
    deadline; `PERMIT_CLOSED` makes both initial and continuation deadlines expire immediately.
  - One task-13 regression run missed its existing cancel/delivery race condition; the immediate
    rerun passed all 29 tests. The task-14 suite passed 14/14 five consecutive times.

### Obligation 1 — `RegionKey`

Implemented immutable value equality at `RegionKey.java:12-67`. A key re-derived with the same
world UUID and observed live-region ID equals and hashes like the original. The task-13 test at
`DefaultFoliaBackpressureTest.java:108-127` fills the one-region cap with the first key, retries
with the re-derived key, and requires rejection plus one tracked `RegionState`. Identity equality
would make the second admission succeed and the tracked-region count become two.

### Obligation 2 — plan-producer ownership

Exact caller seam:
`Optional<RegisteredPlan> registerPlan(DefaultOperationCompletion, long chunkKey,
long planSequence, ChunkTarget)` (`FoliaCommitBroker.java:202`). It performs
`tryAcquirePlanProducer` then `DefaultOperationCompletion.register(token)`, binds that exact token
through task 13's `DefaultFoliaBackpressure.BoundAdmission bind(PlanProducerToken)`, and returns
the bound admission view through `RegisteredPlan.admission()`. There is no thread-local or
operation-ID lookup. `cancelPlan(RegisteredPlan, Throwable)` owns operation-level
`CANCELLED_BEFORE_MUTATION`; admission-attempt cancellation is filtered and leaves the plan live.

The owner flush-expiry hook reads the prebuilt terminal record once and claims the producer; its
test requires both the cancellation record and `registeredProducerCount() == 0`. Post-registration
setup failures call `PlanProducerToken.rejectAdmission` and clean broker state.

### Obligation 3 — C6 and task-16 seam

Task 16 must call exactly one of:

- `<T> CompletionStage<T> scheduleSync(ChunkTarget, SyncPriority, RegionCall<T>)`
- `CompletionStage<Void> scheduleSyncTask(ChunkTarget, SyncPriority, RegionTask)`
- `CompletionStage<Void> scheduleSync(EntityTarget, SyncPriority, EntityTask)`
- `CompletionStage<Void> scheduleSyncGlobal(SyncPriority, GlobalTask)`

Use `SyncPriority.NORMAL` for `sync` and `SyncPriority.WHEN_FREE` for `syncWhenFree`. Both enter the
target lane's adaptive, lane-local time budget; NORMAL is polled before queued WHEN_FREE work.
Global work runs directly inside the already-global broker drain. No global TPS signal is read.
Task 17 must inject a `NonBlockingHandoff` whose `execute(Runnable)` only publishes off-owner work
and never waits; the common-pool default is compatibility wiring, not the final lifecycle owner.
The priority test fails if WHEN_FREE overtakes NORMAL. The integrated slice test advances the lane
clock by 200 microseconds inside each commit and requires only four of ten units in the first
750-microsecond slice; deferred or unmeasured actions execute all ten and fail it.

### Obligation 4 — rejection hook

Task 17 wires exactly one
`AdmissionRejectionHandler rejectionHook(OperationCompletionService, AdmissionRejectionObserver)`
(`FoliaCommitBroker.java:416`). The hook drops `CANCELLED`, submits only `REJECTED` to the
completion service, and never terminalizes inline in the backpressure drain. The test invokes both
outcomes, blocks the observer, requires the hook call to return promptly, and requires one observer
call; unconditional cancellation forwarding or direct blocking fails it.

### Obligation 5 — lanes, rebind, diagnostics, and abstract commit producer

Each canonical observed-region key owns one synchronized mailbox. Commit operations use DRR
quanta Interactive 4 / Normal 2 / Bulk 1, with a four-consecutive cap. Same-chunk eligibility is
keyed by broker registration order, not plan sequence. The deterministic test expects the first
round `I,I,I,I,N,N,B`, rejects a run over four, and registers sequence 2 before sequence 1 on the
same chunk; weighting, run-cap, or plan-sequence ordering changes fail separate assertions.

Stale merge lanes migrate admitted units through `NonBlockingHandoff` before any target mutation;
the ten-unit merge test requires zero mutations after the first stale-lane drain and ten rebinds
after settlement. Transfer refusal at its deadline must yield a terminal record with zero
mutations. Continuations re-enter the same operation mailbox; an interactive competitor must run
before the queued normal continuation. The lane-retirement test enqueues while the old scheduled
flag is still held and fails if the post-release wake-up is lost.

`diagnostics(RegionKey)` and `diagnosticsGlobal()` populate all frozen fields from task-13 pressure,
task-12 dispatcher accessors, and broker counters. Tests cover multi-region aggregation, packet
bytes, outstanding plans, rebinds, oldest-ready age, tickets/futures, and cleanup. The exact line is
`FAWE_QUEUE depth=<readyChunks> inflight=<scheduledDrains+finalizerChains>
outstanding=<outstandingChunks> region=<worldId:observedRegionId|global>`; any field mapping or
format change fails exact string equality.

- **Escalation:** NEEDS_CONTEXT — the collaborator contract has no safe abort for a
  `PlanProducerToken` acquired by `tryAcquirePlanProducer` when
  `DefaultOperationCompletion.register(token)` rejects before consuming it (closed admission or
  duplicate registration). `rejectAdmission` is illegal before registration. Task 11/orchestrator
  must add an acquired-token abort or make acquisition+registration atomic; task 14 preserves the
  original registration exception and does not invoke an invalid terminalization. Concrete
  GET/SET/finalizer content remains correctly deferred to wave 2 behind `CommitAction`.
