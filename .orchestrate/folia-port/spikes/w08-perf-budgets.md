# W0.8 — Performance & resource budget artifact (spec §8)

Status: **STRUCTURE FROZEN at W0 (certification r2, 2026-07-17); numeric thresholds freeze
at the W1-exit gate per Amendment A2.3.** This defines every §8-mandated dimension,
the dual-comparison design, the measurement + gate spec per metric, the certification-hook
attachment, and the scenario spec files — with threshold VALUES left as explicit empty slots
to be filled from W0.2 + baseline data (task 08 out-of-scope: no threshold values invented).

Binding: spec §8 VERBATIM, §4b (regionization exploited), §7.3 (certification integration),
`w07-compat-inventory.md` cert hooks (H-*), architecture.md §3 contracts C6 (global signals)
and the `FoliaBackpressure` / `FoliaRegionDispatcher` seams.

Scenario specs: `harness/scenarios/perf-index.md` + `perf-01…07-*.md` (companion deliverable).

---

## 1. The two comparisons (spec §8 verbatim requirement)

§8 mandates two **distinct** comparisons with **separately named thresholds**. Mixing them
is the trap §8 exists to prevent ("Paper baseline in §4b refers to comparison (2) and does
not satisfy comparison (1)").

### Comparison (1) — Non-Folia regression

- **What:** ported Paper/Spigot code paths vs the **unported base revision**, both on Paper.
- **Base-revision pin:** `f53400f00bc110d43c585747cf1eb1f4f0cdc023`
  ("Address gradle deprecations (#3592)"). This is exactly `git merge-base HEAD upstream/main`
  today — no port code has landed yet, so HEAD == the merge-base. The pin is the **base**, and
  port commits are descendants of it, so it stays fixed as work lands. Re-verify at W0.10 with
  `git merge-base <ported-Paper-tip> upstream/main`; if upstream advanced under the port,
  re-pin to the recorded merge-base and record the move.
- **Runtime:** Paper 26.1.2 (`harness/.cache/paper-26.1.2-74.jar`, pre-staged).
- **Threshold family:** `REG_*` (ratio-to-baseline). Non-regression shape: ported must be
  ≤ baseline within a slack ratio; throughput ≥ baseline within a slack ratio.
- **Workloads:** perf-01, perf-02, perf-04, perf-05 (single-region + persistence). perf-03 /
  perf-07 run on Paper only as non-regression (Paper serializes; no speedup expected). The
  authoritative workload/runtime list is the **signed regression matrix** (done condition 4);
  this artifact defers to it and only supplies the perf shape.

### Comparison (2) — Folia performance

- **What:** ported **Folia** backend vs ported **Paper/Mojang** backend, matched hardware,
  config, scenarios. ("Paper baseline" in §4b = this comparison.)
- **Runtime:** Folia 26.1.2 (`.cache/folia-26.1.2-8.jar`, build 8, pinned) vs Paper 26.1.2
  (A2.1: 26.1.2 is the sole certified Folia target; 26.1.1 was never published).
- **Threshold family:** `FOLIA_*`. Shape is mostly non-regression (Folia ≤ Paper within a
  ratio) **except** the §4b showcases perf-03 and perf-07, which must show a **speedup > 1**
  (`FOLIA_MULTIREGION_SPEEDUP`, `FOLIA_SCALING(P)`) — regionization exploited, not endured.

Both comparisons share the same scenario definitions and methodology; only the two runtimes
under test and the threshold family differ.

---

## 2. Workload set (task Context minimum, mapped to scenarios)

| # | Workload | Scenario | Size / geometry | In cmp |
|---|---|---|---|---|
| W-SMALL | small edit ≤ 1 chunk | perf-01 | 16³ cuboid, 1 chunk, 1 region | 1, 2 |
| W-MED | medium ~64 chunks, single region | perf-02 | 8×8 chunks full height (~3.1M blocks), 1 region | 1, 2 |
| W-MULTI | large multi-region ≥ 4 regions | perf-03 | 4× W-MED footprints, REGION_SEP apart | 1(nr), 2(showcase) |
| W-PASTE | paste with entities + tiles | perf-04 | schematic w/ chests/signs/spawners/entities | 1, 2 |
| W-UNDO | undo of each | perf-05 (+ inline in 01/02/04) | replay of the above + cold-undo across restart | 1, 2 |
| W-SUST | sustained editing (backpressure) | perf-06 | 200 W-MED ops back-to-back, 1 region | 1, 2 |
| W-CONC | concurrent players, different regions | perf-07 | P∈{1,2,4,8} actors, 1 region each | 1(nr), 2(showcase) |

"nr" = non-regression only. Exact block counts / region separation are provisional until the
W0.2 driver fixes what it can actually drive; treated as slots (§6).

---

## 3. Methodology (fixed)

- **Hardware (this machine, fixed statement):** Apple M5 Pro, 18 cores (18 logical), 48 GiB
  RAM, arm64, macOS (Darwin 27.0.0). Single-machine; no networked client (server-side timing
  only). All comparison runs execute on this same machine to keep (1) and (2) matched.
- **JVM:** Temurin 25.0.3 (JDK 25 LTS; Minecraft 26.x requires JDK 25+). Server flags fixed by
  `harness/run.sh`: `-Xms2G -Xmx2G` (`HARNESS_HEAP=2G` default), `-DPaper.IgnoreJavaVersion=false`,
  `-Dcom.mojang.eula.agree=true`, `--nogui`. GC logging flag `-Xlog:gc*:file=<log>.gc.log:tags,time,level`
  to be added by the harness worker (needed for the GC metric; see §5 G5).
- **Config:** flat world (`level-type=minecraft:flat`), `view-distance=4`,
  `simulation-distance=4`, `max-players=4` (raise for perf-07 P=8), online-mode off. FAWE
  `Settings.QUEUE.PARALLEL_THREADS` **fixed and recorded per run** (default = auto; pin an
  explicit value so (1)/(2) match) — **SLOT VALUE**.
- **Warm-up / trials / outliers:**
  - Warm-up: `PERF_WARMUP` untimed ops before timing (per scenario default 2–5) to let JIT +
    chunk load settle. Warm-up ops are discarded.
  - Trials: `PERF_TRIALS` timed repetitions (per scenario default 10–30). Larger for cheap
    ops (perf-01: 30), smaller for expensive (perf-03/06: 10).
  - Outlier rule: report median/p95/p99 over all trials (percentiles are outlier-robust; no
    trimming). Additionally flag any trial > 3× median for manual inspection, but it is **not**
    dropped from the percentile computation — honest tails matter for a perf tool.
  - **Determinism guard:** identical world seed + fixture + reset-between-trials (undo) so each
    trial starts from the same state.
- **Measurement source:** **server-side only** (§8: "server-side timings from probe/plugin
  logs, not client-side"). Completion time = the FAWE operation-completion future (§4d
  completion point) timestamped server-side and emitted as a `FAWE_PERF` log line; cross-checked
  against the harness-clock `@mark` interval. RSS/GC/threads from server-process samplers.
- **Aggregation:** one `perf-report.sh` JSON per run (adapted from SS2); percentiles computed
  from the `FAWE_PERF elapsed_ms` population. A metric whose source did not run is **absent**,
  never proxied.

---

## 4. Per-metric measurement + gate spec (every §8 metric)

Gate form is either **frozen threshold** (absolute, e.g. ms) or **ratio-to-baseline** (vs the
comparison's reference). Values are slots (§6).

| §8 metric | How measured / signal | Gate form | Threshold slot(s) | Cert hook |
|---|---|---|---|---|
| **Median completion** | `FAWE_PERF elapsed_ms` p50; xcheck `@mark` interval | ratio-to-baseline | REG_RATIO_*, FOLIA_RATIO_* | H-STATE, H-REGRESSION |
| **p95 completion** | same, p95 | ratio-to-baseline | " | " |
| **p99 completion** | same, p99 (the headline gate, SS2 frozen-p99 pattern) | ratio-to-baseline | " | " |
| **Throughput** | `blocks / elapsed_ms · 1000`, aggregated | ratio-to-baseline (≥) | REG_TPUT_*, FOLIA_TPUT_*, FOLIA_MULTIREGION_SPEEDUP, FOLIA_SCALING(P) | H-CONCURRENT |
| **Per-region tick impact** | Folia `tps`/`mspt` console block sampled during op minus idle mspt for the owning region | frozen threshold (ms) | TICK_IMPACT_SMALL/MEDIUM_MS | H-CONCURRENT (see G3 gap) |
| **Scheduler queue depth** | `FAWE_QUEUE depth=` (QueueHandler sync queues + region scheduler depth) | frozen threshold (bounded) | QUEUE_DEPTH_MAX | H-CONCURRENT (see G4 gap) |
| **Outstanding futures/tickets/chunks** | `FAWE_QUEUE inflight=/outstanding=` (SingleThreadQueueExtent.submissions, FoliaBackpressure in-flight, ticket count) | frozen threshold; must drain to 0 | OUTSTANDING_MAX | H-LEAK, H-CONCURRENT (G4) |
| **Peak heap / RSS** | `ps -o rss` sampler (`HARNESS_PERF=1`) → `<log>.perf.tsv`; peak − baseline | frozen threshold (MB) | HEAP_PASTE_MB, HEAP_SUSTAIN_MB | H-LEAK (see G5) |
| **GC** | `-Xlog:gc` → `<log>.gc.log`; pause p99 + alloc rate | frozen threshold (ms) | GC_PAUSE_MS | H-LEAK (see G5) |
| **Shutdown drain time** | `@mark op:stop:begin` → process exit (last sampler tick / "Closing Server") | frozen threshold (ms) | SHUTDOWN_DRAIN_MS | H-LEAK, H-FAILURE |
| **Backpressure bounded / ownership-relevant** | `FAWE_QUEUE inflight` per region ≤ bound; no commit frame on non-owning thread (`<log>.threads.tsv`); no `getTPS()` in signal path | gate (load-bearing) + design review | maxInFlightPerRegion | H-CONCURRENT, H-FAILURE |

**§8 backpressure clause (explicit):** "Folia backpressure must use bounded resources and
ownership-relevant signals, not a fabricated global TPS." Enforced by perf-06 in two ways:
(a) runtime — observed per-region in-flight never exceeds `maxInFlightPerRegion`, and no
commit callback runs on a non-owning thread (catches a regression to the current
`CallerRunsPolicy` on `blockingExecutor`, recon-queue-threading §1, which is illegal for
owner-bound work under §1b); (b) design gate — the backpressure signal must key on per-region
queue/in-flight, never the global `FaweTimer.getTPS()` gate that C6/INV-DEG-008 removes.

---

## 5. Escape hatch — honest measurement gaps in the current harness

Per the task escape hatch: where a §8 metric has no honest measurement path in the current
harness, document the gap + the minimal extension. **The current FAWE harness has no perf
machinery at all** — `run.sh` `feed_scenario` supports only `@sleep/@waitfor/@expect/
@with-plugin/<line>`; there is no `@mark`, no sampler, no `perf-report.sh`, and the probe
drives raw Bukkit `setType`, not FAWE. SS2's harness has all of this; the extensions below
are ports of proven SS2 mechanisms. None of these files are owned by task 08 (run.sh /
scenario.sh / scan-logs.sh / probe belong to the harness worker); this section specifies
what they must add.

- **G1 — no operation driver that exercises FAWE (blocker for *every* completion/throughput
  number).** The probe does a fixed 4×4×4 Bukkit fill; there is no path that runs an
  EditSession, and no player actor (README + smoke-set.md both defer this to W0.2). Minimal
  extension: either a probe `fawe-perf <op>` console command driving FaweAPI on a synthetic
  actor and emitting `FAWE_PERF op= elapsed_ms= blocks= region= result=`, or the
  `ssb2-bots-26.1.jar` actor wired to issue `//` commands with FAWE emitting `FAWE_PERF` at
  the §4d completion point. **perf-07 (player-path) strictly needs the actor driver.** This is
  the W0.2 dependency the task already flags as "measurement slots filled after 02".

- **G2 — no timing marks / no `perf-report.sh`.** Completion percentiles need a clock around
  the op. Minimal: `@mark` directive → `<log>.marks.tsv` (SS2 has it verbatim), plus a
  FAWE `perf-report.sh` adapted from `SuperiorSkyblock2/harness/perf-report.sh` (swap SSB2
  Profiler parsing for `FAWE_PERF` line aggregation → p50/p95/p99/throughput). Server-clock
  `FAWE_PERF` is the primary signal; the mark interval is the cross-check.

- **G3 — per-region tick-time granularity (the exact example §8/task call out).** Folia's
  `tps` console command reports per-region mspt, but only for regions that currently exist,
  at coarse (say-line) granularity, and it cannot *attribute* an mspt delta to a specific
  FAWE commit. Honest options, both documented as proxies: (i) sample `tps` during the op
  window and diff against an idle baseline (SS2 scenarios 23/25 pattern) — a bounded proxy,
  not exact per-commit cost; (ii) the accurate path is a probe/FoliaRegionDispatcher hook that
  times the region-commit callback itself and emits per-region commit duration. Note: bundled
  **spark does not enable on Folia 26.1.2** (SS2 finding), so spark's region profiler is not
  available — do not assume it. Recommendation: ship (i) as the harness proxy now; treat exact
  per-region tick impact as needing (ii) (a FoliaRegionDispatcher instrumentation point, a
  task 01/02 deliverable). **Do not fake an exact number from the `tps` proxy.**

- **G4 — queue depth / outstanding futures/tickets/chunks.** These are FAWE-internal
  (`QueueHandler` sync-queue sizes, `SingleThreadQueueExtent.submissions`, `ChunkCache`) and
  Folia-internal (region scheduler queue depth, chunk ticket counts) and are not exposed by
  any current signal. Minimal extension: a `fawe-perf queue-stats` diagnostic emitting
  `FAWE_QUEUE depth= inflight= outstanding= region=`, fed by the `FoliaBackpressure`
  per-region accounting (architecture.md §2, "§8 signals") — i.e. this metric is *born from*
  the backpressure seam and cannot be measured until that seam exists (task 01/02). Slot until
  then.

- **G5 — heap/RSS + GC.** No sampler today. Minimal: `ps -o rss` sampler under `HARNESS_PERF=1`
  → `<log>.perf.tsv` (SS2 has it), and `-Xlog:gc*` on the JVM line → `<log>.gc.log`. Both are
  `run.sh` edits (harness worker). RSS is a coarse process-level upper bound (fine for a
  regression/ratio gate); precise heap occupancy would need JFR — out of scope, RSS is the
  honest available signal.

- **G6 — shutdown drain.** Needs `@mark op:stop:begin` + a last-tick/exit signal
  (SS2 computes `op:stop:begin → last RSS sampler tick`, an upper bound to the sampler
  interval). Depends on G2 + G5. The §8 "shutdown drain time" metric is otherwise
  unmeasurable in the current harness.

- **G7 — 26.1.1 runtime.** `versions.env` `FOLIA_26_1_1_*` are `UNSTAGED`. Comparison (2) must
  run on both patch versions (§7.3); the 26.1.1 numbers are blocked until the orchestrator
  stages that certified build. This is a staging gap, not a measurement-design gap.

- **G8 — cross-restart undo (`@restart`).** perf-05's H-PERSIST cold-undo needs the runner to
  boot → stop → boot the same server dir and continue the scenario; the current runner boots
  once. Minimal: a `@restart` directive (or a two-phase scenario harness) preserving
  `servers/<v>/` between phases.

Summary: **no §8 number is producible today.** G1+G2 are the critical path to any completion/
throughput figure; G3–G6 gate the resource/tick metrics; all are additive harness/probe work
already scoped to the harness worker + W0.2. This artifact's structure is complete and freezes
cleanly; only the numeric slots and these extensions remain.

---

## 6. Empty slots (numeric freeze rescheduled to the W1-exit gate by Amendment A2.3, 2026-07-17)

Nothing here disposes a threshold value (task out-of-scope). Slots:

**Threshold values — Comparison (1) `REG_*`:**
- `REG_RATIO_SMALL`, `REG_RATIO_MEDIUM`, `REG_RATIO_PASTE`, `REG_RATIO_UNDO` (p50/p95/p99
  ceiling ratios, ported/base).
- `REG_TPUT_MEDIUM` (throughput floor ratio, ported/base).

**Threshold values — Comparison (2) `FOLIA_*`:**
- `FOLIA_RATIO_SMALL/MEDIUM/PASTE/UNDO` (Folia/Paper completion ceilings).
- `FOLIA_TPUT_MEDIUM`, `FOLIA_TPUT_SUSTAIN` (throughput floors).
- `FOLIA_MULTIREGION_SPEEDUP` (> 1; perf-03), `FOLIA_SCALING(P)` for P∈{2,4,8} (perf-07).

**Resource / tick thresholds (both comparisons, frozen absolute):**
- `TICK_IMPACT_SMALL_MS`, `TICK_IMPACT_MEDIUM_MS`.
- `QUEUE_DEPTH_MAX`, `OUTSTANDING_MAX`.
- `HEAP_PASTE_MB`, `HEAP_SUSTAIN_MB`, `GC_PAUSE_MS`.
- `SHUTDOWN_DRAIN_MS`.
- `HISTORY_PERSIST_RETRIES` (+ backoff) — bounded retry policy of the §3.6b persistence
  settlement (added by the 2026-07-17 adjudication).
- `maxInFlightPerRegion` (FoliaBackpressure bound; a W0.2 tuning output that this artifact
  then gates against).

**Workload parameters (pending W0.2 driver capability):**
- Exact block counts per workload, `REGION_SEP` minimum (Folia 26.1.x region merge radius),
  `PARALLEL_THREADS` pinned value, perf-04 fixture entity/tile counts, `PERF_*` trial counts
  if tuned from observed variance.

**Runtime staging:**
- ~~`FOLIA_26_1_1_*` build/jar/sha (G7)~~ — CLOSED by A2.1 (26.1.1 never published;
  26.1.2-only). G7 becomes: stage a `FOLIA_26_2_*` block when Folia 26.2 stable publishes
  (per-wave check, A2.1 preparation clause).

All slots are populated from: **W0.2** (what the driver can drive + `maxInFlightPerRegion`
tuning + fixture) and the **baseline reference runs** (base `f53400f00` on Paper for `REG_*`;
ported Paper backend for `FOLIA_*`) that the orchestrator executes. Threshold values are set
by observing the baseline distributions, then choosing ratios/ceilings with slack, and
co-signing at W0.10.

---

## 6b. Probe-observed reference numbers (W0 closure run, 2026-07-17)

Source: `harness/scenario.sh perf-probe --without-plugin --version 26.1.2`, log
`harness/logs/folia-26.1.2-20260717-170828.log`, report `harness/lib/perf-report.pl`
(schema `fawe-harness-perf-v1`). These are **observations that inform threshold-setting at
freeze**, not thresholds (task 08 out-of-scope rule preserved). Probe path = detached
prepare → owner-region commit (region-sweep, 16 chunks/group, 4 groups); NOT the FAWE
EditSession path.

| Slot informed | Observation | Notes |
|---|---|---|
| `QUEUE_DEPTH_MAX` | max_depth_highwater = 4 | drains to 0 after op |
| `OUTSTANDING_MAX` | max_inflight_highwater = 2; outstanding drains to 0 | |
| `GC_PAUSE_MS` | p99 = 24.0 ms, max = 24.0 ms (15 samples, total 64.7 ms) | JVM unified GC log |
| `SHUTDOWN_DRAIN_MS` | upper bound 2961 ms (process-exit observation) | |
| `TICK_IMPACT_*` (G3 proxy) | commit task max 3.3 ms; region commit_max_us 53.4–58.6 ms vs baseline_max_us 50.9–52.8 ms (delta ≈ +3–7 ms worst-tick); rolling-5s tick_avg deltas all negative (regions idle post-op), TPS 20.0 stable on all 4 groups | commit-timer hook (G3-ii) live in probe |
| throughput reference (probe path) | 65 536 blocks / 59.5 ms ≈ 1.10 M blocks/s, single op, ok=1 | denominator = probe-driven set blocks (all changed) |
| RSS | peak 1.80 GB (server process, 6 samples) | context for `HEAP_*` ceilings only |
| schedule delay | max_schedule_delay_us = 55.8 ms | prepare→commit scheduling latency, worst case |

All `REG_*`/`FOLIA_*` ratio and throughput-floor slots, `TICK_IMPACT_*` final values,
`HEAP_*`, `maxInFlightPerRegion`, and workload parameters remain **wave-1** (need the real
FAWE driver + baseline reference runs), as designed.

**Runtime staging (G7) — resolved finding:** `FOLIA_26_1_1_*` is **unsatisfiable**. Verified
2026-07-17 on two sources: PaperMC Fill API (`/v3/projects/folia` → 26.1 family = ["26.1.2"]
only) and PaperMC Maven (`folia-api` jumps 1.21.11-R0.1-SNAPSHOT → 26.1.2.build.8-stable).
Folia never published a 26.1.1 build. Spec target amendment required (user signature, batched
at W0-exit); until then dual-version certification is scoped to 26.1.2.

---

## 7. Certification-hook attachment (spec §7.3 integration)

Each scenario's gates attach to the `w07-compat-inventory.md` H-* hooks so the §8 budget
suite is part of certification, not a separate track:

- **H-REGRESSION** ← every cmp-(1) gate (all `REG_*`). The §8 comparison-(1) is the
  quantitative half of done-condition 4's regression matrix.
- **H-CONCURRENT** ← throughput, per-region tick, queue depth, §4b speedup gates (perf-02/03/
  06/07); the parallelism and no-starvation claims.
- **H-STATE** ← the correctness oracle inside every timed op (a fast-but-wrong edit fails).
- **H-PERSIST** ← perf-04/05 undo + cold-undo-across-restart round-trip.
- **H-FAILURE** ← perf-06 backpressure (no caller-runs, bounded) + shutdown drain clean
  completion.
- **H-LEAK** ← outstanding-drains-to-0, RSS/GC bounded, shutdown-drain, no ticket/task leak.

The perf suite runs **after** the functional certification scenarios in a wave-closure run,
on the same pinned runtimes (Folia 26.1.2 per A2.1; Paper 26.1.2 for the baselines), and
its `perf-report.sh` JSON + pass/fail is a wave-certification artifact.

---

## 8. Acceptance-criteria checklist (self-audit)

- [x] Complete §8-dimension coverage: median/p95/p99 completion, throughput, per-region tick,
  queue depth, outstanding futures/tickets/chunks, heap/RSS/GC, shutdown drain, bounded
  ownership-relevant backpressure, no global TPS — all in §4.
- [x] Workload table (§2) covering the task-mandated minimum set.
- [x] Dual-comparison design with pinned base revision (§1; base = `f53400f00…`).
- [x] Per-metric measurement + gate + hook (§4, §7).
- [x] Scenario spec files created (`harness/scenarios/perf-index.md`, `perf-01…07-*.md`).
- [x] Explicit empty-slot list awaiting W0.2 / baseline numbers (§6).
- [x] Escape hatch: measurement gaps + minimal harness extensions documented, no faked proxy
  (§5).

## 9. Attack points (for adversarial review)

1. **G3 per-region tick proxy** is the softest metric — the `tps`-diff proxy may not isolate a
   single commit's cost; challenge whether TICK_IMPACT gates are meaningful without the
   FoliaRegionDispatcher commit-timer (G3-ii).
2. **Base-revision pin** assumes HEAD stays == merge-base until port commits land; if the
   orchestrator rebases the port onto a newer upstream, `REG_*` baselines must be re-run
   against the new merge-base — verify the re-pin discipline in §1.
3. **CallerRunsPolicy regression detection** (perf-06) depends entirely on the jstack sampler
   catching a short-lived non-owning commit frame — a sampling gap could miss it; consider a
   deterministic assertion (FoliaRegionDispatcher rejecting non-owner commits) as the real
   gate, with perf-06 as corroboration.
4. **Throughput denominator** (`blocks`) — air/no-op blocks and short-circuited empty chunks
   (SingleThreadQueueExtent) inflate blocks/s; define "blocks" as *changed* blocks consistently
   across (1)/(2) or the ratio is meaningless.
5. **Slot count** — 20+ threshold slots is a lot to freeze at W0.10 from limited baseline runs;
   risk of arbitrary ratios. Recommend deriving each ratio mechanically from the baseline
   distribution (e.g. p99 ceiling = base_p99 · (1 + slack)) rather than hand-picking.
