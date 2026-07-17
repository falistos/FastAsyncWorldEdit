# perf-04 — paste with entities + tile entities

The heaviest correctness-and-cost path: a clipboard containing block-entities (chests with
item NBT, signs, spawners) and entities (armor stands, mobs) pasted with `-e`. Exercises the
detached entity-NBT / tile-NBT GET and the region-thread commit of block-entities + entity
placement (INV-ADP-007/008). Followed by undo, which must restore/remove exactly what was
placed (§4d history correctness).

Comparisons: (1) ported Paper vs base `f53400f00`; (2) Folia vs ported Paper.
Primary metrics: completion p50/p95/p99; outstanding futures/tickets/chunks during placement;
peak heap/RSS + GC (entity/tile NBT is allocation-heavy).
Cert hooks: H-STATE, H-PERSIST, H-FAILURE.

## Parameters

- Fixture: a prestaged schematic `perf04-entities-tiles.schem` under a harness fixtures dir
  (**SLOT**: fixture creation is a W0.2 deliverable — exact entity/tile counts recorded at
  build time, like the SS2 `w21` fixture). Must include: ≥ 8 chests with distinct item NBT,
  ≥ 4 signs, ≥ 2 spawners, ≥ 16 entities (armor stands + NoAI mobs), some straddling a chunk
  boundary within the target region.
- Op: `//schem load perf04` → `//paste -e` at a fixed origin → verify counts (probe oracle) →
  `//undo` → verify removal.
- `PERF_TRIALS` (default 15), `PERF_WARMUP` (default 3). Run with `HARNESS_PERF=1`.

## Sequence (target directives; extension-contract per perf-index.md)

```
@waitfor Done \(|For help, type "help" 240
@repeat PERF_TRIALS:
  @mark op:perf04:begin
  fawe-perf paste schem=perf04-entities-tiles origin=0,80,0 entities=true
  @waitfor FAWE_PERF op=perf04 .*result=ok 120
  @mark op:perf04:end
  fawe-perf queue-stats
  fawe-perf verify-counts schem=perf04-entities-tiles origin=0,80,0   # SLOT: probe oracle
  @waitfor FAWE_HARNESS_PASTE_OK 60
  @mark op:perf04-undo:begin
  fawe-perf undo op=perf04
  @waitfor FAWE_PERF op=perf04-undo .*result=ok 120
  @mark op:perf04-undo:end
  fawe-perf verify-empty origin=0,80,0
  @waitfor FAWE_HARNESS_UNDO_OK 60
@mark op:stop:begin
stop
@waitfor (Stopping the server|Stopping server|Closing Server) 180
```

## Gates

- Completion p50/p95/p99 (paste and undo separately):
  - Cmp (1): `≤ REG_RATIO_PASTE` — **SLOT**. Cmp (2): `≤ FOLIA_RATIO_PASTE` — **SLOT**.
- Correctness (gate, not budget): pasted block/tile/entity counts and NBT match the fixture
  oracle; undo restores exactly (zero residual). Any mismatch fails the run regardless of time.
- Outstanding futures/tickets/chunks during placement bounded (`FAWE_QUEUE`) — **SLOT**.
- Peak RSS − baseline ≤ `HEAP_PASTE_MB`; GC pause p99 ≤ `GC_PAUSE_MS` — **SLOTs**
  (RSS from `HARNESS_PERF=1` sampler; GC from `-Xlog:gc` — see report escape hatch G5).
- scan-logs zero-tolerance gate (always).
