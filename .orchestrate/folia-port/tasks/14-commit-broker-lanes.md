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

- **Status:** <DONE | DONE_WITH_CONCERNS | BLOCKED>
- **File List:**
- **Deviations:**
- **Attack points:**
- **Escalation:** <AUTHORIZED | BLOCKED | NEEDS_CONTEXT> — <detail>
