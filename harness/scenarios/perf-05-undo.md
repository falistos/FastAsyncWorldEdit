# perf-05 — undo of each workload

Undo is a first-class §8 workload (task Context: "undo of each"). It replays a recorded
changeset back onto the world and must resolve its own completion future (§4d) with history
describing exactly what committed. This scenario measures undo cost and correctness for the
small / medium / paste workloads, and the persistence round-trip (H-PERSIST).

Comparisons: (1) ported Paper vs base `f53400f00`; (2) Folia vs ported Paper.
Primary metrics: undo completion p50/p95/p99; RSS/GC during undo; history correctness.
Cert hooks: H-FAILURE, H-PERSIST.

Undo timing for perf-01/02/04 is also captured inline in those scenarios; this file adds the
**cold undo across a restart** case that only H-PERSIST exercises: the on-disk changeset
(`FaweStreamChangeSet` / `DiskStorageHistory`, INV-PERSIST-001/002) written on Folia must be
replayable after save+unload+restart, and the result must remain readable on Paper.

## Parameters

- Reuse perf-02 medium footprint as the edit to undo. `PERF_TRIALS` (default 15).
- `HARNESS_PERF=1`. Fixture: the edit is performed, then the server is restarted, then undo
  is issued (cold-history path). Restart handling is a run.sh capability — **SLOT** (needs a
  `@restart` directive or a two-phase scenario; the current runner boots once).

## Sequence (target; extension-contract per perf-index.md, incl. `@restart`)

```
@waitfor Done \(|For help, type "help" 240
# hot-undo trials (history still in memory / same session)
@repeat PERF_TRIALS:
  fawe-perf set cuboid=0,-64,0:127,127,127 block=stone
  @waitfor FAWE_PERF op=perf05-edit .*result=ok 120
  @mark op:perf05-undo:begin
  fawe-perf undo op=perf05-edit
  @waitfor FAWE_PERF op=perf05-undo .*result=ok 120
  @mark op:perf05-undo:end
# cold-undo across restart (H-PERSIST) — one trial
fawe-perf set cuboid=0,-64,0:127,127,127 block=stone
@waitfor FAWE_PERF op=perf05-cold .*result=ok 120
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
@restart
@waitfor Done \(|For help, type "help" 240
@mark op:perf05-coldundo:begin
fawe-perf undo op=perf05-cold
@waitfor FAWE_PERF op=perf05-coldundo .*result=ok 120
@mark op:perf05-coldundo:end
fawe-perf verify-empty cuboid=0,-64,0:127,127,127
@waitfor FAWE_HARNESS_UNDO_OK 60
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
```

## Gates

- Undo completion p50/p95/p99: Cmp (1) `≤ REG_RATIO_UNDO`, Cmp (2) `≤ FOLIA_RATIO_UNDO` — **SLOTs**.
- Correctness (gate): after undo the region matches pre-edit state exactly (probe oracle);
  cold-undo produces the same result as hot-undo; the post-restart world is then readable on
  Paper (H-PERSIST cross-check, run under comparison harness). Mismatch fails regardless of time.
- RSS/GC during undo within the perf-04 budgets.
- scan-logs zero-tolerance gate (always).
