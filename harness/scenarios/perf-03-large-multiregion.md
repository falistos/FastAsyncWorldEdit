# perf-03 — large multi-region edit (≥ 4 regions) — §4b parallelism showcase

The comparison (2) headline: four (or more) medium edits issued concurrently in areas far
enough apart that Folia can never merge them into one region. On Folia their commits run on
distinct region threads in genuine parallel; on Paper all finalizers serialize on the main
thread. This is where the port must *win*, not merely not regress.

Comparisons: (1) ported Paper vs base `f53400f00` (non-regression on Paper — both serialize);
(2) Folia vs ported Paper (expect speedup).
Primary metrics: aggregate throughput (blocks/s across all regions); number of DISTINCT
region threads that ran FAWE frames; per-region tick impact; wall-clock to all-complete.
Cert hooks: H-CONCURRENT, H-STATE.

## Parameters

- Region anchors: N ≥ 4 areas each `REGION_SEP` blocks apart (default 33600, the SS2-proven
  separation guaranteeing distinct Folia regions — exact minimum is Folia-build-dependent;
  **SLOT**: confirm against the certified 26.1.x region merge radius). Each area gets the
  perf-02 medium footprint.
- Ops issued without waiting between them, so all N are in flight simultaneously.
- `PERF_TRIALS` (default 10), `PERF_WARMUP` (default 2). Run with
  `HARNESS_PERF=1 HARNESS_PERF_JSTACK=1 HARNESS_PERF_INTERVAL=1`.

## Sequence (target directives; extension-contract per perf-index.md)

```
@waitfor Done \(|For help, type "help" 240
@repeat PERF_TRIALS:
  @mark op:perf03:begin
  fawe-perf set cuboid=A block=stone async     # A..D = 4 anchors REGION_SEP apart
  fawe-perf set cuboid=B block=stone async
  fawe-perf set cuboid=C block=stone async
  fawe-perf set cuboid=D block=stone async
  @sleep 1
  tps
  @sleep 1
  tps
  @waitfor Total regions: ([4-9]|[1-9][0-9]+) 60     # ≥ 4 live regions proves separation
  @waitfor FAWE_PERF op=perf03 .*count=4 .*result=ok 180   # aggregate completion line
  @mark op:perf03:end
  fawe-perf undo op=perf03
  @waitfor FAWE_PERF op=perf03-undo .*result=ok 180
tps
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
```

## Gates

- §4b parallelism proof (cmp 2, load-bearing): from `perf-report.sh --require-region-threads N`,
  ≥ N distinct `Folia Region Scheduler Thread`s executed FAWE frames during the op window.
  Fails if all commits serialized on one thread.
- Aggregate throughput speedup: `tput_folia_multiregion / tput_paper_multiregion ≥ FOLIA_MULTIREGION_SPEEDUP`
  — **SLOT** (must be > 1; the §4b promise). Cmp (1) is non-regression only:
  `p99_ported/p99_base ≤ REG_RATIO_MEDIUM` on Paper.
- Per-region tick impact per region ≤ `TICK_IMPACT_MEDIUM_MS`, and no region starves another.
- `Total regions ≥ 4` asserted (separation held).
- scan-logs zero-tolerance gate (always) — no ownership / cross-region violation.
