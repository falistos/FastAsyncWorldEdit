# perf-02 — medium edit (~64 chunks, single region)

The bread-and-butter FAWE workload: a multi-chunk edit that engages the parallel apply
pipeline (`ParallelQueueExtent`, multiple `SingleThreadQueueExtent` workers) but stays inside
one Folia region, so every commit lands on one region thread. Isolates throughput and
queue-depth behavior from the multi-region parallelism showcase (perf-03).

Comparisons: (1) ported Paper vs base `f53400f00`; (2) Folia vs ported Paper.
Primary metrics: completion p50/p95/p99; throughput (blocks/s); per-region tick impact;
scheduler queue depth / outstanding chunks.
Cert hooks: H-REGRESSION (cmp 1), H-STATE, H-CONCURRENT.

## Parameters

- Footprint: cuboid `(0,-64,0)`–`(127,127,127)` = 8×8 = 64 chunk columns, full height.
  Block count ≈ 64 · 16·16·192 ≈ 3.1M blocks. Confined to one contiguous area → one region.
- Op: `//set stone` (or `//replace air stone`), then `//undo` to reset between trials.
- `PERF_TRIALS` (default 20), `PERF_WARMUP` (default 3).

## Sequence (target directives; extension-contract per perf-index.md)

```
@waitfor Done \(|For help, type "help" 240
@repeat PERF_WARMUP: fawe-perf set cuboid=0,-64,0:127,127,127 block=stone ; fawe-perf undo op=perf02
@repeat PERF_TRIALS:
  @mark op:perf02:begin
  fawe-perf set cuboid=0,-64,0:127,127,127 block=stone
  @waitfor FAWE_PERF op=perf02 .*result=ok 120
  @mark op:perf02:end
  fawe-perf queue-stats            # emits FAWE_QUEUE depth/inflight/outstanding (SLOT: needs instrumentation)
  fawe-perf undo op=perf02
  @waitfor FAWE_PERF op=perf02-undo .*result=ok 120
tps
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
```

## Gates

- Completion p50/p95/p99 and throughput = `blocks / elapsed_ms · 1000`.
  - Cmp (1): `p99_ported/p99_base ≤ REG_RATIO_MEDIUM`, `tput_ported/tput_base ≥ REG_TPUT_MEDIUM` — **SLOTs**.
  - Cmp (2): `p99_folia/p99_paper ≤ FOLIA_RATIO_MEDIUM`, `tput_folia/tput_paper ≥ FOLIA_TPUT_MEDIUM` — **SLOTs**.
- Queue depth / outstanding chunks & futures from `FAWE_QUEUE` line: bounded, must not grow
  unboundedly across trials; `outstanding_chunks ≤ QUEUE_DEPTH_MAX` — **SLOT** (needs the
  §8 queue/backpressure instrumentation; see report escape hatch G4).
- Per-region tick impact ≤ `TICK_IMPACT_MEDIUM_MS` — **SLOT**.
- scan-logs zero-tolerance gate (always).
