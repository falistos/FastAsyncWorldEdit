# Task 08 — Performance & resource budget artifact (W0.8)

- **Wave:** 0
- **Depends on:** 01 (verified); measurement slots filled after 02
- **Status:** todo
- **Executor:** opus (definition; measurements run by orchestrator via harness)

## Objective
The spec §8 budget artifact, ready to freeze at W0.10: workloads, metrics, methodology,
dual baselines — with every §8-mandated dimension defined and measurement procedures
scripted so the numbers can be produced mechanically. Deliver
`spikes/w08-perf-budgets.md` + measurement scenario specs under `harness/scenarios/`.

## Out of scope
- No FAWE/probe source changes; no threshold VALUES for Folia-vs-Paper comparison (2) —
  those get frozen from W0.2 + baseline data at W0.10, not invented.
- Do not run servers (orchestrator does).

## Binding references
- spec §8 VERBATIM — both comparisons, all listed metrics (median/p95/p99 completion,
  throughput, per-region tick impact, queue depth, outstanding futures/tickets/chunks,
  heap/RSS/GC, shutdown drain), bounded ownership-relevant backpressure, no global TPS.
- spec §4b (regionization exploited), §7.3 (certification integration).
- `spikes/w07-compat-inventory.md` certification hooks (attach budget gates to hooks).
- Harness: `harness/run.sh`, scenarios, `ssb2-bots-26.1.jar` actor driver in `harness/lib/`;
  Paper 26.1.2 jar pre-staged in `harness/.cache/` for baseline (1) and (2) reference runs.
- SS2 precedent for gate format: `/Users/falistos/Workspace/forks/SuperiorSkyblock2/harness/perf-report.sh`
  and its frozen-p99 gate pattern (read for format, adapt to FAWE ops).

## Context & decisions
- Workload set must cover at minimum: small edit (≤1 chunk), medium (~64 chunks single
  region), large multi-region (≥4 regions, the §4b parallelism showcase), paste-with-
  entities/tiles, undo of each, sustained editing (backpressure behavior), concurrent
  players editing different regions.
- Comparison (1) — non-Folia regression: ported Paper vs unported base revision
  (`git merge-base` pin it) on Paper 26.1.2. Comparison (2) — Folia backend vs ported
  Paper backend, matched scenarios/hardware. State explicitly which workloads belong to
  which comparison.
- Methodology: warm-up count, trial count, outlier rule, fixed JVM flags, fixed hardware
  statement (this machine), measurement source (server-side timings from probe/plugin logs,
  not client-side).
- Every metric gets: how measured, from what signal, gate form (frozen threshold vs
  ratio-to-baseline), and which certification hook (H-*) it attaches to.

## Escape hatch
If a §8 metric has no honest measurement path in the current harness (e.g. per-region
tick-time granularity), document the gap and the minimal harness extension needed —
don't fake a proxy metric silently.

## Acceptance criteria
`spikes/w08-perf-budgets.md`: complete §8-dimension coverage, workload table, dual-
comparison design with pinned base revision, per-metric measurement+gate spec, hooks
attached; scenario spec files created; explicit list of empty slots awaiting W0.2/baseline
numbers.

---
## Dev record (worker fills this in on completion)

**Status:** done (DRAFT for W0.10 freeze + co-sign).

**File List:**
- `.orchestrate/folia-port/spikes/w08-perf-budgets.md` (new) — the §8 budget artifact.
- `harness/scenarios/perf-index.md` (new) — harness-extension contract + workload↔comparison↔metric map.
- `harness/scenarios/perf-01-small-edit.md` (new)
- `harness/scenarios/perf-02-medium-single-region.md` (new)
- `harness/scenarios/perf-03-large-multiregion.md` (new) — §4b parallelism showcase.
- `harness/scenarios/perf-04-paste-entities-tiles.md` (new)
- `harness/scenarios/perf-05-undo.md` (new)
- `harness/scenarios/perf-06-sustained-backpressure.md` (new)
- `harness/scenarios/perf-07-concurrent-players.md` (new) — §4b scaling showcase.
- `.orchestrate/folia-port/tasks/08-perf-budgets.md` (this Dev record).

No FAWE/probe source, no run.sh/scenario.sh/scan-logs.sh, no gradle files touched. No servers run.

**Completion Notes:**
- All §8 dimensions covered (report §4): median/p95/p99 completion, throughput, per-region
  tick impact, queue depth, outstanding futures/tickets/chunks, heap/RSS/GC, shutdown drain,
  bounded ownership-relevant backpressure, no-global-TPS.
- Dual comparison design frozen (report §1). Base revision pinned:
  `f53400f00bc110d43c585747cf1eb1f4f0cdc023` (= current `git merge-base HEAD upstream/main`;
  HEAD == merge-base because no port code has landed).
- Every metric has how-measured / signal / gate-form (frozen threshold vs ratio) / cert hook
  (H-*). Hooks attached per scenario and summarized in report §7.
- Threshold VALUES left as explicit slots (report §6, ~20 slots) per out-of-scope; filled from
  W0.2 + baseline runs at W0.10.
- Scenario files are SPECS: they encode the target directive sequence + the harness-extension
  contract they depend on (perf-index.md). They are not yet runnable for numbers on the current
  runner — only the scan-logs correctness gate runs today.

**Deviations:**
- None from the task contract. The scenario specs necessarily reference directives/tooling the
  current harness lacks (`@mark`, `fawe-perf` driver, samplers, `perf-report.sh`, `@restart`),
  because measuring §8 requires them and those files are owned by the harness worker. I
  specified the contract rather than implementing it (report §5, perf-index.md). This is the
  documented escape-hatch path, not a redesign.

**Attack points (escalated for review — full list in report §9):**
- G1/G2 are the critical measurement gap: **no §8 number is producible today** — the harness
  has no FAWE driver and no perf/timing machinery at all (the probe drives raw Bukkit setType;
  README + smoke-set defer the player path to W0.2). This is the largest risk to the whole §8
  track and is a hard W0.2 dependency.
- G3 per-region tick-time: only a `tps`-diff proxy is honestly available now (bundled spark
  does not enable on Folia 26.1.2); exact per-commit tick cost needs a FoliaRegionDispatcher
  commit-timer (task 01/02). TICK_IMPACT gates are soft until then.
- G4 queue/outstanding metrics are born from the `FoliaBackpressure` seam — unmeasurable until
  that seam exists (task 01/02).
- perf-06 CallerRunsPolicy-regression detection leans on jstack sampling; recommend a
  deterministic FoliaRegionDispatcher non-owner-commit rejection as the real gate.
- Throughput "blocks" must be defined as *changed* blocks consistently across both comparisons
  or the ratios are meaningless.
