# perf-01 — small edit (≤ 1 chunk)

Baseline latency floor. A single-chunk cuboid `//set`, small enough that the whole edit
lands in one Folia region and one chunk-write commit. Measures the fixed per-operation
overhead (dispatch, GET snapshot, prepare, one region commit, finalizers, completion).

Comparisons: (1) ported Paper vs base `f53400f00`; (2) Folia vs ported Paper.
Primary metrics: completion p50/p95/p99; per-region tick-time impact of the commit.
Cert hooks: H-REGRESSION (cmp 1), H-STATE, H-CONCURRENT.

## Parameters (env; `SLOT` = frozen from W0.2 driver capability + baseline)

- `PERF_TRIALS` (default 30), `PERF_WARMUP` (default 5) — see report methodology.
- Selection: cuboid `(0,80,0)`–`(15,95,15)` in `fawe-harness` = 1 chunk column, 16³ = 4096
  blocks. Well within the probe's currently-owned region `(chunkX=0,chunkZ=0)`.
- Op: `//set stone` then verify via the region-owner oracle (probe read-back), then reset.

## Sequence (target directives; needs harness-extension contract in perf-index.md)

```
@waitfor Done \(|For help, type "help" 240
# warm-up (untimed)
@repeat PERF_WARMUP: fawe-perf set cuboid=0,80,0:15,95,15 block=stone
# timed trials
@repeat PERF_TRIALS:
  @mark op:perf01:begin
  fawe-perf set cuboid=0,80,0:15,95,15 block=stone
  @waitfor FAWE_PERF op=perf01 .*result=ok 30
  @mark op:perf01:end
  fawe-perf undo op=perf01            # reset state between trials
  @waitfor FAWE_PERF op=perf01-undo .*result=ok 30
# per-region tick sample while a final op is in flight (see report escape hatch)
tps
@sleep 1
tps
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
```

Note: `@repeat` and the `fawe-perf` driver are extension-contract items (perf-index.md);
this file is a spec, not yet a runnable `.md` for the current runner.

## Gates

- Completion p50/p95/p99 from `FAWE_PERF elapsed_ms` (server clock) cross-checked against the
  `op:perf01:begin→end` mark interval (harness clock). Gate form:
  - Cmp (1): ratio-to-baseline, `p99_ported / p99_base ≤ REG_RATIO_SMALL` — **SLOT**.
  - Cmp (2): ratio-to-Paper-backend, `p99_folia / p99_paper ≤ FOLIA_RATIO_SMALL` — **SLOT**.
- Per-region tick impact: `tps` mspt for the owning region during the op minus idle mspt
  ≤ `TICK_IMPACT_SMALL_MS` — **SLOT** (see report escape hatch: `tps`-sampling proxy).
- scan-logs zero-tolerance gate on the run log (always).
