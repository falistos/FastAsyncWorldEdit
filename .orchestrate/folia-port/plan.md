# FAWE Folia Port — Plan & Status Board

Source of truth for orchestration state. Updated at every transition.

## Phase status

| Phase | Status |
|---|---|
| 0 — Spec | **CLOSED — spec v3 FROZEN** (user-signed; Codex CO-SIGN v3 after r1: 11 findings, r2: 3 residuals, r3: verified). Reviewer thread `019f6ff7-0ac5-7230-aa0c-6aa173b766ad` |
| 1 — Architecture / contracts / decomposition | **CLOSED — [W0-FREEZE] SEALED 2026-07-17**: architecture v3.1 co-signed unconditional; certification r2 = **CERTIFY-WITH-CONDITIONS** (`codex-w0-certification-r2.md`; all conditions applied same day — record sync A2.1/A2.3, count precision); Amendments 1+2 user-signed + Codex co-signed clause-by-clause; inventory frozen (93 items, 5 settled/88 UNKNOWN baseline); numeric budgets → W1-exit gate (A2.3) |
| 2 — Wave 1 (core seams) | **DISPATCHING** (tasks/10-17 approved at the 2026-07-17 gate) |
| 3+ — Waves 2-5 | not started (wave-2 gated on the A2.3 numeric freeze) |

## In-flight work

| Item | Executor | State | Tracking |
|---|---|---|---|
| W0.1 harness bring-up | Codex | **VERIFIED** — boot green, corrective 1 (region-safe smoke via FAWEHarnessProbe) green: exit 0, zero violations. Bot-layer gap closed by pre-staging `ssb2-bots-26.1.jar` (orchestrator-built) | task 01 |
| W0.3 spike lighting | Opus agent | **done → to review** (fillLightNibble GO, skip-light GO, GET reads GO-with-fix, NMSRelighter=DEGRADE target §1b-clean, Starlight STOP-as-structured; 5 runtime Qs → W0.2) | `spikes/w03-lighting.md` |
| W0.4 spike regen | Opus agent | **done → to review** (disposition: DISABLE, source-verified against Folia patches — world creation banned, chunk pump region-owned; one deferred DEGRADE option behind runtime Q1; contract-changing → spec amendment at W0-exit) | `spikes/w04-regen.md` |
| W0.5 spike physics/packets | Opus agent | **done → to review** (GO both mechanisms; physicsFreeze orphaned dead code; send-lock subsumed by commit model; 2 cross-region hazards → W0.2 experiments) | `spikes/w05-physics-packets.md` |
| W0.6 API context audit | Codex (read-only, xhigh, fresh thread) | **done → to review** (382 declarations: 374 PRESERVED / 8 DEGRADED / 0 EXCLUDE; 10 attack points; declared gaps to close at freeze: QueueHandler's 10 public sync/async methods, 24 concrete Extent types' own publics, 4 Fawe UUID-executor methods, stale INV anchor `FaweBukkit.java:475-521`) | `spikes/w06-api-context-audit.md` |
| W0.7 compat inventory | Opus agent | **done → to review** (92 items, 6 axes, 8 cert hooks; attack points declared) | `spikes/w07-compat-inventory.md` |
| W0.9 build plumbing | Sonnet agent | **VERIFIED** (compileJava green, exit 0, pre-existing warnings only; note: scoped gradle invocations need `--no-configure-on-demand`. Pending runtime confirmation via harness: Folia version string + fail-closed path. Corrective 1: folia-api dep removed from bukkit module — JVM25+ metadata; module decision → W0.10) | task 09 |
| W0.2 spike chunk pipeline | Codex impl thread `019f700b` | **DONE — GO recorded** (results doc verified on disk: 323 lines, matrix + ledger + ranking region-sweep>batched>per-chunk + conditional GO with mandatory callback re-dispatch rule = amendment A1.4). Earlier note: consolidation via Opus  (Codex thread cut twice by content-filter false positive on this thread's vocabulary — routing deviation recorded; Codex impl thread reserved for wave-1 code, W0.10a tournament re-examines ranking cross-family). RUN 1 GREEN on Folia 26.1.2, zero violations, restart readback 4096/4096. Key data: prepare/commit model §1b-safe AND fast (commit tasks 364-705µs for 4-16k blocks; visibility ≈ 1 tick scheduler delay); tournament: region-sweep best (4x fewer tasks, equal visibility); ENV: getMinecraftVersion()=="26.1.2" exactly (W0.9 guard correct), PaperLib.isPaper()==true on Folia; scoped neighbor suppression works (0 vs 94 events); relight submits legal from region threads BUT completion callbacks land on non-owner threads (re-hop required in commit contract); SCRATCH loaded=false → //regen DISABLE definitive (W0.4 Q1 closed) | `.codex/w02-iter2.jsonl` |
| W0.8 perf budgets | Opus agent | **done → to review** (structurally freeze-ready; base revision pinned `f53400f00`; ~20 threshold slots + G1-G8 harness perf extensions escalated as hard W0.2 dependency — no §8 number producible until the FAWE driver + timing machinery exist) | `spikes/w08-perf-budgets.md` + 8 scenario specs |
| W0.10b arch co-sign r1 | Codex (fresh, xhigh) | **REJECT — 10 BLOCKING + 1 MAJOR, all accepted by orchestrator** (key: ChunkVersion misses external mutation → base fingerprints required; ticket lexical-extent rule; core/platform placement inversion; QueueHandler binary compat; module decision = dedicated `:worldedit-bukkit:folia` Java-25) | `codex-arch-cosign-r1.md` |
| Architecture v3 revision | Opus (synthesis agent) | **DONE** — v3 on disk, 11 findings applied, judgment rev2, module decision closed | architecture.md v3 |
| APIC gap closure (F9) | Codex (fresh, high) | **DONE** — 271 rows (10+257+4), all PRESERVED; §4c total = 653 declarations | `spikes/w06b-apic-gap-closure.md` |
| G1-G8 harness perf extensions (F10) | Codex impl thread | **VERIFIED** — corrective 3 closed green (actor determinism via `allow-flight=true` + kick wording promoted to fatal scan pattern w/ self-test; Codex thread cut mid-turn, fix verified on disk by orchestrator). Rerun 2026-07-17 17:07: pipeline-probe exit 0, two logs, scan OK both phases, readback 4096/4096. perf-probe green 17:08: full report (region-sweep 65 536 blocks ≈ 1.10 M blocks/s, commit task max 3.3 ms, GC p99 24 ms, queue highwater 4→0). Probe-fillable reference numbers recorded in `w08-perf-budgets.md` §6b; FAWE-driver slots stay wave-1 | `.codex/harness-corrective-3.jsonl`, logs `folia-26.1.2-20260717-1706*/170828` |
| Arch co-sign r2+r3 | Codex (thread `019f7077`) | **CO-SIGN v3.1** (r2: 4 residuals applied verbatim in v3.1; r3: all PASS). Conditional ONLY on the F1 module-graph compile proof to be attached to the freeze record. Harness impl + numeric budgets = wave-1 dispatch gates, not co-sign gates | `codex-arch-cosign-r2.md`, `codex-arch-cosign-r3.md` |
| Compile-proof skeleton | Opus agent | **VERIFIED — GRAPH COMPILE GREEN (exit 0, first pass)**: core SPI + `:worldedit-bukkit:folia` Java-25 module. FD-1 adjudicated: CommitOutcome struck from scope (orchestrator task-writing error; frozen §3.6 ChunkTerminalRecord protocol is the design). F1 condition SATISFIED → arch co-sign now unconditional | compile log `/tmp/graph-compile.log` |
| W0.10 W0-exit freeze + certification | orchestrator + Codex | **CLOSED — r2 CERTIFY-WITH-CONDITIONS, conditions applied** (see phase table). History: r1 REJECT (6 BLOCKING / 1 MAJOR / 1 MINOR; Amendment 1 REJECT-AS-WRITTEN — A1.2–A1.4 supported, A1.1 wording overstates). Correctives dispatched same day: **C-F1F2** (Codex, workspace-write): SPI → v3.1 planSequence/pre-admission registration/NOT_ACCEPTED path + DiagnosticSnapshot on broker; foliaBackend shading config (Paper shadowJar only, reobf clean); FoliaSupport deduped (bukkit main owns detection) — graph compile re-verified green by orchestrator. **C-F3F7** (Opus agent): inventory 92→93 + 5 settled flow-backs (88 UNKNOWN remain), dev records 01/06/09 backfilled evidence-cited. **F8** stale root `spikes/` duplicate archived out of repo. **Packaging proof DONE** (dynamic): Paper jar = 18 Folia-package class files (16 backend-module + detector pair) + 44 core SPI class files; Spigot reobf jar = detector pair ONLY, zero backend classes; single FoliaSupport. C-F1F2 transcription re-verified verbatim by orchestrator against architecture §3.6/G4. Build-env note: old 1.21.x adapters need a local JDK-21 init-script (codebook 1.0.14 vs Java-25 classes; pre-existing, recorded in task 09). Remaining: amendment-2 signature (user) → re-certification round (co-signs A1+A2) → close + commits | `codex-w0-certification.md`, `.codex/c-w0cert-skeleton.*` |

Done earlier: recon reports (3) in `spikes/`; spec co-signature threads r1-r3 (`codex-spec-review-r*.md`), reviewer thread `019f6ff7-0ac5-7230-aa0c-6aa173b766ad`.
Note: **Folia targets RESOLVED by Amendment A2.1 (user-signed + co-signed 2026-07-17)**: 26.1.2 sole certified target (26.1.1 never published — three-source proof in `amendment-2-draft.md`). A2.1 preparation clause operationalized: (1) the orchestrator checks Fill for a published Folia 26.2 stable AT EVERY WAVE GATE (`curl -s https://fill.papermc.io/v3/projects/folia | jq .versions` — currently 26.1 family only); (2) `adapter-26.2` stays in the build matrix; (3) wave-5 certification reserves a 26.2 slot; (4) 26.1.2-specific assumptions must be flagged in dev records; (5) certifying 26.2 requires a new signed amendment.

## Assurance plan (armed modules — spec §6)

All modules armed (assurance=max). Named risk: silent chunk/world corruption + deadlock.
1. Codex spec co-signature — adversarial rounds until convergence (round 1 running).
2. Cross-review (fresh Codex thread) on every delivery touching queue pipeline / adapters / scheduler SPI / history persistence.
3. Double-reviewer on critical domains: concurrency & thread-ownership, chunk write path, data persistence.
4. Runtime harness (adapted from SS2 harness) = wave-0 deliverable; gates scripted and committed.
5. Go/no-go spikes in wave 0: regionized chunk write path, lighting, //regen, API thread-context semantics.
6. Wave-closure certification reviews on waves with intersecting perimeters.
7. Multi-session REPRISE/handoff machinery.
8. Codex CLI workers for HEAVY/XL tasks (persistent implementation thread, ids recorded here).

## Model routing (user-directed, 2026-07-17)

- **Codex CLI (majority)**: development/implementation tasks — persistent implementation thread for continuity; fresh threads for reviews.
- **Opus**: the rest of development, hard design, tricky debugging, adversarial reviews (other family vs Codex).
- **Sonnet**: only very simple tasks (mechanical sweeps, boilerplate, config).
- Orchestrator stays on the strongest model; cross-review keeps implementer and reviewer in different model families.

## Decision log

- 2026-07-17 Fork renamed: `falistos/FastAsyncWorldEdit` (old 2022 repo preserved as `-legacy`). Local: `/Users/falistos/Workspace/forks/FastAsyncWorldEdit`.
- 2026-07-17 Strategy: dual-platform in-tree single jar. REJECTED: divergent Folia-only fork (loses upstream fixes + Paper test surface).
- 2026-07-17 Targets: Folia 26.1.1 + 26.1.2 only. AMENDED pre-signature: Canvas 26.2 dropped by user (Folia fork, no extra API expected; revisit on concrete need).
- 2026-07-17 API guarantee for third-party plugins: YES. REJECTED: player-commands-first.
- 2026-07-17 Degradation policy: documented degradation. REJECTED: strict all-or-nothing.
- 2026-07-17 Assurance: MAX, all modules armed. REJECTED: intermediate (threading bugs invisible to static review).
- 2026-07-17 SS2 integration scenarios: OUT of scope (FAWE standalone).
- 2026-07-17 Spec amended §4b (user-directed): priority order = correctness of world operations first, performance second-but-critical with explicit budgets vs Paper baseline; regionization exploited as a parallelism opportunity.
- 2026-07-17 Routing (user-directed): Codex majority for development + Opus; Sonnet only for very simple tasks.
- 2026-07-17 (user-directed): "best possible architecture — ultra performant AND perfectly safe". Armed: chunk-pipeline design tournament (W0.10a) + architecture.md adversarial co-signature (W0.10b). W0.2 spike must measure competing commit strategies (per-chunk / batched-neighbor / region-sweep), not validate a single design.

## Commit plan (user-approved 2026-07-17, execute on re-certification CERTIFY)

Conventional Commits, no attribution footers, no production-process context in messages.
1. `chore: ignore harness runtime artifacts and session logs` — .gitignore additions
   (`harness/.cache/`, `harness/servers/`, `harness/lib/*.jar`, `.codex/`).
2. `test(harness): add Folia runtime harness with scenario and perf gates` — harness/
   scripts, scenarios, probe sources, lib scripts, versions.env, adapter-jdk21 init script.
   Logs stay untracked per `harness/.gitignore` (runtime captures, regenerable; certification
   reports quote the key lines inline). NOTE: upstream root `.gitignore` ignores `*.sh` —
   `!harness/**/*.sh` re-includes the harness scripts.
3. `build: add dedicated Java 25 folia module` — settings.gradle.kts include,
   gradle/libs.versions.toml folia-api pin, worldedit-bukkit/folia/build.gradle.kts.
4. `feat(core): add Folia port task SPI skeleton` — worldedit-core util/task/* (19 files) +
   QueueHandler visibility change.
5. `feat(bukkit): add Folia detection with fail-closed certified matrix` — FoliaSupport,
   UnsupportedFoliaVersionException, WorldEditPlugin onLoad block, plugin.yml.
6. `feat(bukkit): add Folia backend service skeletons` — worldedit-bukkit/folia/src classes.
7. `build(bukkit): bundle the folia backend into the Paper artifact only` —
   worldedit-bukkit/build.gradle.kts foliaBackend configuration.
8. `docs: add Folia port specification, architecture and wave-0 records` —
   .orchestrate/folia-port/ (spec, amendments, architecture, plan, spikes, tasks,
   certification reports).

## Future triggers

- Architecture v3.1 CO-SIGNED + compile proof green → freeze record ready; wave-0 certification review next (fresh reviewer, cross-seam sampling, includes explicit Amendment-1 co-signature).
- Amendment 1 USER-SIGNED 2026-07-17 (regen DISABLE, relight DEGRADE, tick-limiter DISABLE, callback re-dispatch rule). Codex co-sign pending in arch round `codex-arch-cosign-r1`.

- W0-exit spec amendment round (single batch, user signature + Codex): //regen DISABLE (W0.4), relight DEGRADE to NMSRelighter + Starlight STOP-as-structured (W0.3), tick-limiter disable on Folia (W0.5), any further spike outcomes. Never piecemeal signatures.
- W0.2 probe build gotcha: ChunkPos.x/z are record accessors x()/z() in 26.1 mappings (orchestrator fixed). Codex turn-1 was killed by a content filter (false positive) at the last file write — use neutral measurement vocabulary in Codex prompts on this thread.
- FAWE-jar in-matrix boot leg deliberately skipped: out-of-matrix abort is deterministic code → wave-3 deterministic tests own it.
- Next W0.2 iteration prompt must include: implement the perf-extension contract from `harness/scenarios/perf-index.md` (G1-G8 — @mark, samplers, perf-report, FoliaRegionDispatcher commit-timer hook in the probe) so §8 numbers become producible.
- W0.10 architecture input: dedicated Gradle module for the Folia backend (Java 25 toolchain, SS2 `Hooks/Folia` precedent) vs in-module — forced by folia-api JVM25+ metadata; decide at architecture co-sign.

- When Codex spec review r1 lands → adjudicate findings; if amendments: amend spec + re-sign (user) + Codex round 2. Repeat until CO-SIGN. NOTE: r1 started against the pre-§4b spec (amendment landed mid-run) — explicitly surface §4b (priority order) to Codex in the next round.
- When EditSession recon lands → write `spikes/recon-editsession-paths.md`, then draft `architecture.md` (needs: spec co-signed OR at least round-1 findings integrated).
- Phase 1 gate: present wave plan + frozen contracts + assurance plan to user before any dispatch.

## Verified facts (for architecture)

- folia-api artifact used by the SS2 prod stack: `dev.folia:folia-api:26.1.2.build.8-stable` (compileOnly), Java 25 toolchain required at runtime for MC 26.x (source: SS2 `Hooks/Folia/build.gradle:18`).

## Waves

Dependency rule: a task starts only when its deps are `verified`. Routing per user
directive (Codex majority + Opus; Sonnet only trivial). Wave 0 closes with a certification
review + the [W0-FREEZE] contract co-signature (exact SPI signatures, §4c disposition
table, inventory, budgets) — nothing in wave 1+ dispatches before that.

### Wave 0 — De-risk & foundations (all tasks independent unless noted)

| ID | Task | Executor | Notes |
|---|---|---|---|
| W0.1 | Harness bring-up: Folia 26.1.1+26.1.2 server automation (adapt SS2 harness), smoke scenario (//set + block verify), watchdog/thread-violation detection | Codex (persistent impl thread) | exclusive: harness/, ports 25601-2 |
| W0.2 | SPIKE go/no-go: regionized chunk write path prototype on adapter-26.1 (detached prepare → region commit → finalizers), perf measurement vs Paper | Codex (impl thread, after W0.1 usable) | THE architectural bet |
| W0.3 | SPIKE: lighting under regionization (starlight relight per-region) | Opus | GO/DEGRADE/DISABLE/STOP |
| W0.4 | SPIKE: //regen feasibility on Folia | Opus | GO/DEGRADE/DISABLE/STOP |
| W0.5 | SPIKE: physics suppression + chunk packet resend without global toggles | Opus | feeds C1 |
| W0.6 | API thread-context audit → §4c disposition table draft (every public entry point) | Codex (fresh thread — audit, not impl) | [W0-FREEZE] input |
| W0.7 | Compatibility inventory draft (coverage baseline per spec §7.2) | Opus | co-signed at W0 exit |
| W0.8 | Perf budget artifact: workloads, metrics, dual baselines (spec §8), Paper baseline measurements | Opus (definition) + harness runs | needs W0.1 |
| W0.9 | Build plumbing: folia-api dep (pinned), FoliaSupport detection, fail-closed bootstrap skeleton, plugin.yml | Sonnet | small, well-specified |
| W0.10a | **JUDGED — synthesis in progress** (3 proposals delivered; consensus: sliced region-sweep. Ruling: skeleton=C RegionTicket/choke-point/typed-sink, engine=B region lanes, grafts=A owner-inline+viewer-near+packet-at-commit config-gated. Synthesis worker rewriting architecture.md v2 + judgment.md) — chunk-pipeline design tournament (user-directed 07-17): ≥3 independent proposals (Codex + Opus, imposed angles: latency-first / multi-region-throughput-first / safety-simplicity-first), judged against W0.2 harness measurements + §1b compliance; winner synthesized (grafting runners-up ideas) into architecture.md | orchestrator judges; mixed families propose | after W0.2 |
| W0.10b | architecture.md adversarial co-signature (same regime as spec: Codex rounds until convergence) + [W0-FREEZE] contracts finalized, co-signed (user + Codex); certification review of wave | orchestrator + Codex | gate for wave 1 |

### Wave 1 — Core seams (after W0.10)

Task files DRAFTED (2026-07-17, `tasks/10-17`, pending gate approval):
10 thread-context resolver + Bukkit backend → 11 completion protocol → 12 ticket
authority/dispatcher → 13 backpressure (Demand) → 14 commit-broker lanes + G4 diagnostics →
15 C5 requalification (~20 isMainThread sites, per-site record) → 16 FoliaTaskManager/
FoliaQueueHandler → 17 bootstrap wiring (closure task, includes A2.1 consequential edit of
the FoliaSupport certified set). Deps: 10 and 11/13 are roots; 17 last. Codex majority;
cross-review armed per delivery; double-reviewer on concurrency/ownership perimeters.
Drafts 11/14 re-synced after corrective C-F1F2 (planSequence + DiagnosticSnapshot now in tree).

### Wave 2 — Chunk pipeline (after wave 1 verified)

GET snapshot path, SET commit path, finalizers (tiles/entities/POI/light/packets) on
adapter-26.1 per C1/C2; history write-task requalification; per-region backpressure live.

### Wave 3 — Operations surface

EditSession/extents/command dispatch requalification, clipboard, brushes, preloader,
session manager concurrency, deterministic tests (scheduler routing, failure injection,
persistence round-trips).

### Wave 4 — Features & degradations

//regen, snapshots, remaining inventory items per spike outcomes; degradations documented
and amended into the signed contract where required.

### Wave 5 — Certification & delivery

Full gate suite (Folia 26.1.2 per A2.1; reserved slot for Folia 26.2 if published and
amended in by then), regression matrix (Paper/Spigot), perf budget
verification (dual baselines), consumer-plugin API gate, FOLIA.md, port report.
