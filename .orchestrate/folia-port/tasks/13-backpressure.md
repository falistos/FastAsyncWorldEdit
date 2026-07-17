# Task 13 — FoliaBackpressure: full Demand admission model (Wave 1)

STATUS: DRAFT — not dispatched; pending wave-0 certification + user gate approval

- **Wave:** 1
- **Depends on:** none structurally (uses folia-module-internal `RegionKey`). Consumed by
  tasks 14, 16, 17.
- **Status:** todo
- **Executor:** Codex (persistent implementation thread)
- **Cross-review:** ARMED — reviewer family Opus (fresh thread). Perimeter: queue pipeline /
  scheduler SPI. Double-reviewer (concurrency & thread-ownership) — admission cycles and
  caller-runs are the liveness failure modes (spec §1b).

## Mandate
Implement the per-region bounded admission model frozen in architecture §3.4 (F4): the full
Proposal-B SPI restored, with the three r1 corrections — owner-thread nonblocking `tryAcquire`,
reserved continuation capacity (`acquireContinuation`), and permit transfer on region
merge/split (`transfer`). This is the sole gate that prevents unbounded prepared work and the
liveness violations (owner-bound caller-runs, finalizer continuations queuing behind the commits
they unblock).

## In scope
- `FoliaBackpressure` implementation (folia module; the interface + `Demand`/`Limits`/`Pressure`/
  `Permit`/`Priority`/`Stage` records are already frozen in tree — create the impl class):
  - `acquire(region, demand, cancellationSignal)`: completes on a FAWE executor; **never called
    from a tick thread**; cancellation propagates.
  - `tryAcquire(region, demand)`: owner-thread admission — immediate, nonblocking, returns
    `Optional.empty()` instead of ever waiting; the ONLY admission entry legal from a tick
    thread (G-A1 uses it exclusively).
  - `acquireContinuation(region, demand, cancellationSignal)`: draws from a **reserved
    per-region continuation budget carved out of (not added to) the finalizer capacity**; never
    queues behind the commits it unblocks.
  - `transfer(permit, actualRegion)`: move accounting to the actual current region before any
    mutation under a stale-region permit; returns false when the target cannot admit now (plan
    defers/fails before mutation — never mutates under stale accounting).
  - `Permit` lifecycle: READY → SCHEDULED → COMMITTING → FINALIZING → close(); stages advance
    only in declared order (invalid transition terminates that chunk plan exceptionally);
    `close()` idempotent, releases all remaining accounting.
  - `pressure(region)` snapshot; `recordScheduleDelay` / `recordSlice` EWMA feeds; `limits()`;
    `stopAccepting(reason)`.
- Initial certification values (config-backed, `[NEEDS-RUNTIME]`, architecture §3.4): 256 ready
  chunks/region · 64 MiB prepared SET/region · 64 finalizer chains/region · 256 waiters/region ·
  65,536 global ready chunks · `min(1 GiB, maxHeap/4)` global prepared bytes · 4,096 global
  finalizer chains. Per-region limits do NOT replace the global byte cap; global caps bound
  process resources only, never a per-region health signal.
- **Saturation behavior** (architecture §3.4 / Proposal B §5.3): no owner-bound task on the
  submitting thread; worker pipeline stops producing for a saturated region; bounded async
  waiters; legacy sync A/W callers await with bounded deadline and no locks; R/E/G callers that
  would need to wait are rejected before accepting mutations; full waiter queue or expired
  deadline → rejection before acceptance (terminalized `NOT_ACCEPTED` per §3.6, via the task-11
  completion sink — expose the hook, do not wire it here). **No caller-runs for owner-bound
  work, ever.**

## Out of scope
- The `DiagnosticSnapshot` producer (§3.4): it lives on `FoliaCommitBroker` and aggregates
  `pressure()` + dispatcher counters + broker accounting — that is task 14. Here, just make
  `pressure()` return the frozen `Pressure` fields it needs.
- Lane drain / DRR / slice controller (task 14). Backpressure only accounts; the broker drains.
- Wiring `NOT_ACCEPTED` terminalization into `OperationCompletion` (task 14 does the wiring;
  task 11 owns the sink).

## Binding references
- architecture.md §3.4 (the entire frozen SPI + corrections + certification values + saturation
  + the frozen `DiagnosticSnapshot` note), §1b (liveness invariant: no caller-runs for
  owner-bound work; acyclic bounded cancellable worker→owner waits only), §3.7 (`recordSlice`/
  `recordScheduleDelay` feed the adaptive slice controller), C6 (per-region signals replace
  global TPS).
- spec §8 (bounded resources + ownership-relevant signals, not fabricated global TPS),
  §4b (correctness > performance).
- In-tree frozen: `FoliaBackpressure.java` (interface + all records), `RegionKey.java`.

## Signature / structural flags
- `Permit.region()` returns `RegionKey` (folia-internal, F2-legal). `RegionKey` shape is not
  frozen — task 14 (broker) is its primary author; coordinate so both tasks agree on its
  identity/equality contract. If you need `RegionKey` identity semantics before task 14 exists,
  define the minimal contract and flag it for task-14 reconciliation.
- The reserved continuation budget has **no separate `Limits` field** by design (carved out of
  `maxFinalizersPerRegion`); do not add one — implement the carve-out internally.

## Files to touch
- `worldedit-bukkit/folia/src/main/java/com/fastasyncworldedit/bukkit/folia/` — CREATE the
  `FoliaBackpressure` impl. May touch `RegionKey.java` for the identity contract (coordinate
  with task 14 — single-writer per architecture §5; propose diff, orchestrator sequences).

## Context & decisions
- Correctness > performance (spec §4b): the admission gate must never admit work it cannot
  bound; a rejected-before-acceptance plan must terminalize cleanly (no silent loss).
- Async-first: `acquire`/`acquireContinuation` return `CompletionStage`; only FAWE workers ever
  await, never tick threads (spec §1b).
- Fail-fast internal on invalid stage transitions.

## Executor constraints
- Folia module compiles at Java 25. No git commits. No FQN inline. English. Gradle always
  `--no-configure-on-demand`.

## Escape hatch
If `RegionKey` identity cannot be settled without task 14, deliver the accounting logic against
a minimal locally-specified `RegionKey` contract and flag the reconciliation (NEEDS_CONTEXT)
rather than guessing the broker's lane-grouping semantics.

## Acceptance criteria
- Graph compile proof green:
  `./gradlew :worldedit-core:compileJava :worldedit-bukkit:folia:compileJava
  :worldedit-bukkit:compileJava --no-configure-on-demand`
- `tryAcquire` provably never blocks (returns `Optional`, no await path).
- Stage machine rejects out-of-order transitions; `close()` idempotent.
- Continuation budget is carved from finalizer capacity, not additive (assert in a test).
- No caller-runs path for owner-bound work exists (grep + review).

## Test / verification strategy
Targeted deterministic tests (spec §6): admission saturation → `NOT_ACCEPTED`; `tryAcquire`
non-blocking; continuation reservation; `transfer` refusal path (no mutation under stale
accounting); stage-order enforcement. Runtime pressure signals confirmed by harness perf-probe
post-wiring:
`JAVA_HOME_RUNTIME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
harness/scenario.sh perf-probe --without-plugin --version 26.1.2`. Numeric budget values remain
`[NEEDS-RUNTIME]` until the W1-exit numeric freeze (Amendment A2.3) — record which slots you
left config-driven.

---
## Dev record (worker fills this in on completion)

- **Status:** <DONE | DONE_WITH_CONCERNS | BLOCKED>
- **File List:**
- **Deviations:**
- **Attack points:**
- **Escalation:** <AUTHORIZED | BLOCKED | NEEDS_CONTEXT> — <detail>
