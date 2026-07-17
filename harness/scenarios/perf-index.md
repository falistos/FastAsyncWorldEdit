# perf-* — §8 performance & resource measurement scenarios (index + harness contract)

These `perf-*` scenario specs produce the spec §8 budget numbers mechanically. They are the
measurement companions to `.orchestrate/folia-port/spikes/w08-perf-budgets.md`, which owns
the methodology, the two-comparison design, and the threshold slots.

Status: **SPEC**. The current harness (`run.sh` `feed_scenario`) supports only
`@sleep / @waitfor / @expect / @with-plugin / <raw console line>`. Every metric beyond
"the op completed without a log violation" needs the harness extension described below.
This file freezes the *contract* those scenarios require; the harness worker implements it
(run.sh / probe are not owned by this task). Until then, the `perf-*` files are executable
only for their scan-logs correctness gate, not for numbers.

## Harness-extension contract (owned by the harness/probe worker, specified here)

The scenarios below reference these capabilities. Each is additive to the existing runner.

- **`@mark <label>`** — append `epoch_ms<TAB>iso<TAB>label` to `<log>.marks.tsv`. Same
  directive SS2's harness already has (`SuperiorSkyblock2/harness/run.sh`). Gives the
  harness-clock interval between two marks.
- **FAWE actor/driver** — a server-side entry point that runs a *real FAWE operation*
  (EditSession pipeline, not a raw Bukkit `setType` like the current probe) and emits one
  structured server-clock line per op:
  `FAWE_PERF op=<id> elapsed_ms=<n> blocks=<n> region=<key> result=<ok|partial|fail>`.
  Server-side timing is mandatory per §8 methodology ("server-side timings … not
  client-side"). Two acceptable implementations, both W0.2 deliverables:
  (a) extend the harness probe with a `fawe-perf <op> <args...>` console command that drives
      FaweAPI/EditSession on a synthetic console actor and times the completion future; or
  (b) wire the prestaged `harness/lib/ssb2-bots-26.1.jar` actor to issue `//` commands and
      have FAWE itself emit the `FAWE_PERF` line at operation-completion (§4d completion
      point). Player-path coverage requires (b); (a) is enough for single-actor completion
      time + throughput.
- **RSS/GC sampler** (`HARNESS_PERF=1`) — `ps -o rss` sampler → `<log>.perf.tsv`
  (SS2 pattern), plus `-Xlog:gc*:file=<log>.gc.log:tags,time,level` added to the server JVM
  line so GC pauses/allocation are parsable server-side.
- **Region-thread attribution sampler** (`HARNESS_PERF_JSTACK=1`) — jstack sampler →
  `<log>.threads.tsv` recording threads running `com.fastasyncworldedit` /
  `com.sk89q.worldedit` frames, matched against `Folia Region Scheduler Thread`
  (SS2 `perf-report.sh --require-region-threads`). This is how the §4b parallelism claim is
  proven (distinct region threads ran FAWE frames concurrently).
- **`perf-report.sh`** — adapted from `SuperiorSkyblock2/harness/perf-report.sh`: aggregates
  marks, `FAWE_PERF` lines (p50/p95/p99/throughput), RSS, GC, `tps` blocks, and region-thread
  distinctness into one JSON per run. Nothing is invented: a metric whose source did not run
  is absent. This is the file the orchestrator runs to emit the numbers.

If a scenario is run before its contract capability exists, it degrades to correctness-only
(scan-logs) and the perf JSON simply omits the missing section — never a fabricated proxy.

## Workload → comparison → primary metrics map

Comparison (1) = non-Folia regression: ported Paper vs unported base `f53400f00…` on Paper
26.1.2. Comparison (2) = Folia backend vs ported Paper backend, matched hardware/scenarios.

| Scenario | Workload | Cmp (1) | Cmp (2) | Primary §8 metrics | Cert hooks |
|---|---|---|---|---|---|
| perf-01 | small edit (≤1 chunk) | yes | yes | completion p50/p95/p99, per-region tick impact | H-REGRESSION, H-STATE |
| perf-02 | medium (~64 chunks, 1 region) | yes | yes | completion, throughput, tick impact, queue depth | H-REGRESSION, H-STATE, H-CONCURRENT |
| perf-03 | large multi-region (≥4 regions) | yes | yes (§4b showcase) | throughput, distinct region threads, tick impact/region | H-CONCURRENT, H-STATE |
| perf-04 | paste w/ entities+tiles + undo | yes | yes | completion, outstanding futures/tickets, heap/RSS/GC | H-STATE, H-PERSIST, H-FAILURE |
| perf-05 | undo of each workload | yes | yes | completion, history correctness, RSS/GC | H-FAILURE, H-PERSIST |
| perf-06 | sustained editing (backpressure) | yes | yes | bounded in-flight commits/region, queue depth, no caller-runs, heap/RSS/GC, throughput under saturation | H-CONCURRENT, H-FAILURE |
| perf-07 | concurrent players, different regions | partial | yes (§4b showcase) | throughput scaling, distinct region threads, per-region tick impact | H-CONCURRENT |
| (all) | shutdown drain | yes | yes | shutdown drain time, leak check | H-LEAK, H-FAILURE |

Multi-region (perf-03) and concurrent-players (perf-07) are the §4b true-parallelism
showcases: comparison (2) expects a *speedup* on Folia (distinct region threads run
concurrently) where Paper serializes all finalizers on the main thread — not merely
non-regression. All other workloads are non-regression-shaped in both comparisons.

## Fixed run parameters (shared; frozen values live in the report)

- Hardware: single machine — Apple M5 Pro, 18 cores, 48 GiB, arm64, macOS (Darwin 27).
- JVM: Temurin 25.0.3, `-Xms2G -Xmx2G` (harness default `HARNESS_HEAP`), server flat world.
- Warm-up / trial / outlier counts, and `Settings.QUEUE.PARALLEL_THREADS`: see report §
  "Methodology" — passed to scenarios via env, not hard-coded per file.
