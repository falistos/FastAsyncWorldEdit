# perf-07 — concurrent players editing different regions

Multiple actors, each running its own edit stream in its own region simultaneously. Unlike
perf-03 (one console driver fanning N ops), this exercises the *player-path* under real
concurrency: independent sessions, selections, histories, and completion callbacks, each
routed to a different owning region. The §4b throughput-scaling showcase from the player
surface, and the concurrency-correctness gate (no cross-session/cross-region interference).

Comparisons: (1) ported Paper vs base `f53400f00` (non-regression; Paper serializes);
(2) Folia vs ported Paper (throughput scales with player/region count).
Primary metrics: aggregate throughput vs player count; distinct region threads with FAWE
frames; per-region tick impact; per-session completion p95/p99.
Cert hooks: H-CONCURRENT.

## Parameters

- `PERF_PLAYERS` (default 4) actors, each REGION_SEP apart (perf-03 separation).
- **Requires the player-actor driver** (perf-index.md contract option (b), the
  `ssb2-bots-26.1.jar` path) — this is the one scenario that cannot be satisfied by a console
  `fawe-perf` probe command alone. **SLOT**: blocked on the W0.2 actor driver.
- `HARNESS_PERF=1 HARNESS_PERF_JSTACK=1 HARNESS_PERF_INTERVAL=1`.
- Sweep `PERF_PLAYERS ∈ {1,2,4,8}` to produce the scaling curve.

## Sequence (target; driver + directives per perf-index.md contract)

```
@waitfor Done \(|For help, type "help" 240
# each bot: join, //pos1 A, //pos2 A', //set stone, repeat; A_i are REGION_SEP apart
@bots spawn PERF_PLAYERS regionsep=REGION_SEP
@mark op:perf07:begin
@bots run edit=set block=stone footprint=medium loops=10
@sleep 2
tps
@sleep 2
tps
@waitfor Total regions: (PERF_PLAYERS|[1-9][0-9]+) 60
@waitfor FAWE_PERF op=perf07 .*players=PERF_PLAYERS .*result=ok 600
@mark op:perf07:end
tps
@bots despawn
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
```

## Gates

- Scaling (cmp 2, load-bearing): aggregate throughput at P players / throughput at 1 player
  ≥ `FOLIA_SCALING(P)` — **SLOT** (super-linear-ish up to region/core count is the §4b promise;
  Paper stays flat). Distinct region threads with FAWE frames ≥ min(P, regions) via
  `perf-report.sh --require-region-threads`.
- Per-session completion p95/p99 within the perf-02 single-op budgets (no starvation).
- Correctness (gate): each session's edit is exactly applied in its own region; no
  cross-region block/entity leakage; each history is independent and undoable.
- Cmp (1) non-regression on Paper: `p99_ported/p99_base ≤ REG_RATIO_MEDIUM` at P=4.
- scan-logs zero-tolerance gate (always).
