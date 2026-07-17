# perf-06 — sustained editing (backpressure behavior)

Saturation test. A long back-to-back stream of medium edits into one region, issued faster
than the region thread can commit, so the in-flight commit queue fills. This is where §8's
backpressure mandate is proven: **bounded resources, ownership-relevant signals, no fabricated
global TPS, and no caller-runs execution for owner-bound work** (§1b liveness invariant).

Note the current Paper path uses `blockingExecutor` with `CallerRunsPolicy`
(recon-queue-threading §1) — on Folia that would run a region-bound commit on the calling
FAWE worker, violating ownership. The Folia backpressure (`FoliaBackpressure`, per-region
bounded in-flight accounting) must reject/queue instead, never caller-run. This scenario is
the gate that catches a regression to caller-runs.

Comparisons: (1) ported Paper vs base `f53400f00` (queue stays bounded, no throughput
collapse); (2) Folia vs ported Paper (bounded per-region in-flight, graceful latency).
Primary metrics: in-flight commits per region (bounded), scheduler queue depth, outstanding
futures/tickets/chunks, throughput under saturation, peak heap/RSS + GC.
Cert hooks: H-CONCURRENT, H-FAILURE.

## Parameters

- `PERF_SUSTAIN_OPS` (default 200) medium edits issued with no inter-op wait, into one region.
- `HARNESS_PERF=1 HARNESS_PERF_JSTACK=1`. Sample `fawe-perf queue-stats` every K ops.
- Backpressure bound `FoliaBackpressure.maxInFlightPerRegion` — its VALUE is a W0.2 tuning
  output (**SLOT**); this scenario asserts the observed in-flight count never exceeds it.

## Sequence (target directives; extension-contract per perf-index.md)

```
@waitfor Done \(|For help, type "help" 240
@mark op:perf06:begin
@repeat PERF_SUSTAIN_OPS:
  fawe-perf set cuboid=0,-64,0:127,127,127 block=stone async
  fawe-perf queue-stats                 # FAWE_QUEUE inflight=<n> depth=<n> outstanding=<n> region=<key>
@waitfor FAWE_PERF op=perf06 .*completed=PERF_SUSTAIN_OPS 600
@mark op:perf06:end
tps
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
```

## Gates

- Bounded in-flight (gate, load-bearing): every `FAWE_QUEUE inflight` sample for a region
  ≤ `maxInFlightPerRegion` — **SLOT**. A single sample above the bound fails the run.
- No caller-runs for owner-bound work (gate): no commit callback frame observed on a
  non-owning thread in `<log>.threads.tsv` (jstack sampler); the FoliaRegionDispatcher choke
  point is the only commit path. Assert via `perf-report.sh` region-thread attribution.
- Queue depth / outstanding chunks bounded and drains to 0 by completion (no leak → H-LEAK).
- Throughput under saturation ≥ `FOLIA_TPUT_SUSTAIN` (cmp 2), no collapse vs Paper (cmp 1) — **SLOTs**.
- Peak RSS − baseline ≤ `HEAP_SUSTAIN_MB`; GC pause p99 ≤ `GC_PAUSE_MS` — **SLOTs**.
- No global-TPS gating in the signal path (design gate, verified by review of §8 signals, not
  a runtime metric): backpressure must key on per-region in-flight/queue, never `getTPS()`.
- scan-logs zero-tolerance gate (always).
