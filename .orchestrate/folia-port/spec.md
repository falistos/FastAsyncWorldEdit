# FAWE Folia Port — Specification (v3 — FROZEN. User-signed 2026-07-17; Codex CO-SIGN v3 after 3 adversarial rounds. Amendable only by recorded amendment + both signatures.)

Revision history:
- v1 signed by user 2026-07-17; amended §4b (priority order) by user direction same day.
- Codex adversarial review r1 (`codex-spec-review-r1.md`): CO-SIGN WITH AMENDMENTS, findings 1-10 mandatory + 1 editorial. All accepted and integrated below (§1b, §4c, §4d, §2b, §5, §6, §7, §8). v2 signed by user 2026-07-17.
- Codex r2 (`codex-spec-review-r2.md`): fidelity audit passed; 3 residual amendments (R2-1 §4b atomicity vs §4d, R2-2 dual perf baselines in §8, R2-3 count fix) — applied verbatim in v3. These refine user-signed provisions without changing any decision; user informed with veto option.

## 1. Goal

Port FastAsyncWorldEdit to Folia for production use: the existing artifacts, with the
Paper/Mojang artifact running unchanged on Paper and correctly, safely and fast on Folia
26.1.2 (A2.1: Folia never published 26.1.1). "Production" means: no silent world corruption, no deadlocks, no
regression beyond frozen budgets on Paper, and honest, documented behavior for anything
that cannot be identical on Folia.

## 1b. Folia safety invariants (FROZEN — Codex r1 F1/F2)

**Ownership invariant:** no FAWE worker, global-scheduler callback, or non-owning region
thread may directly read or mutate live region-owned Minecraft state. Off-thread work may
use detached data only; every live read, commit, and finalizer must execute through the
owning world/chunk/entity scheduler with ownership valid at execution time.
AsyncCatcher/TickThread checks must never be disabled or bypassed. Any exception requires
a signed upstream-supported contract and a dedicated certification gate.

**Liveness invariant:** a Folia tick thread must never block on work that can transitively
require any Folia scheduler. FAWE executors must not wait for region/entity/global
callbacks while holding chunk, session, queue, or history locks. Backpressure must not use
caller-runs execution for owner-bound work. Every allowed wait must have a documented
acyclic wait-for path, bounded timeout, cancellation propagation, and thread-dump assertion.

## 2. Platform targets

| Platform | Status |
|---|---|
| Paper / Spigot (all versions FAWE upstream supports) | Behavior-equivalent within frozen budgets — regression matrix is a hard gate |
| Folia 26.1.2 (stable, build ≥ 8) | Sole certified Folia target (Amendment A2.1, signed 2026-07-17: 26.1.1 was never published by Folia — verified Fill API + Maven + `ver/26.1.x` history). Active 26.2 tracking per A2.1's preparation clause; certifying 26.2 requires a new signed amendment |

CanvasMC is explicitly out of scope: it is a Folia fork and the port should need no
Canvas-specific API. Revisit only if a concrete need appears mid-effort.

- Backend selected at boot; Folia detection precedes Paper detection; Folia classes never classloaded elsewhere.
- `folia-supported: true` in plugin.yml.
- Java/Gradle toolchain: follow the existing FAWE build (no toolchain bumps unless required by folia-api).

## 2b. Packaging & adapter matrix (FROZEN — Codex r1 F7)

- "Single jar" means **no Folia-only artifact**: the existing Paper/Mojang artifact contains
  both the Paper and Folia backends; the existing reobfuscated Bukkit artifact remains
  Spigot-only. No artifact consolidation or packaging redesign.
- Only **adapter-26.1** is Folia-certified, on Folia 26.1.2 (A2.1). adapter-26.2 and
  all adapter-1_21* remain Paper/Spigot-only (adapter-26.2 stays in the matrix per A2.1's
  26.2 preparation clause).
- Any Folia version outside the certified matrix, failed detection, or missing Folia adapter
  must **fail closed** before listeners, executors, or world access start (clear operator
  message, plugin does not half-start).
- Certification locks exact server builds, JDK, plugin artifacts, and checksums; dynamic
  version-range dependencies are not certification identities.

## 3. Strategy

**Dual-platform in-tree** (same model as the SuperiorSkyblock2 port):
- Changes confined behind seams (scheduler SPI, platform abstraction); upstream merges must stay realistic.
- On non-Folia runtimes: observable public behavior, configuration, events, exceptions,
  persistence formats, and results remain equivalent, within frozen quantitative budgets
  (replaces "byte-for-byte" — Codex r1 F8). The port adds a backend; it does not rewrite the core for everyone.
- Upstream sync policy: the fork tracks `upstream/main`; port code lives in clearly-bounded new packages/modules plus minimal, marked touch-points in existing files.

## 4. Scope

**In scope:**
- The full player-facing surface: commands, brushes, wands, selections, clipboard,
  schematic IO, undo/redo, history, masks/patterns/transforms, CUI.
- The FAWE async pipeline (queue system, parallel extents, chunk write path, lighting)
  made correct under regionized threading per §1b.
- The public API surface (FaweAPI, EditSession, extents, TaskManager) for third-party
  plugins, governed by the API thread-context contract (§4c).
- The compatibility inventory (§7.2) — frozen and co-signed in wave 0.
- Runtime harness (adapted from the SS2 harness) — wave-0 deliverable (§6).
- Targeted deterministic unit/component/stress tests where they provide coverage the
  runtime harness cannot reliably replace (scheduler routing, failure injection,
  persistence round-trips) — Codex r1 F10.
- Operator documentation (FOLIA.md equivalent).

**Out of scope:**
- worldedit-fabric / worldedit-forge / worldedit-sponge / worldedit-cli (untouched).
- SS2+FAWE integration scenarios (FAWE is treated standalone).
- Folia support in any adapter other than adapter-26.1 (§2b).
- New features unrelated to the port.

## 4b. Priority order (user-directed amendment, 2026-07-17)

The two non-negotiable qualities, in order:
1. **Correctness of accepted world operations** — for CERTIFIED-identical features, every
   committed block/entity/tile/light mutation matches the Paper oracle; no accepted mutation
   is silently lost. Each owning-region commit is internally consistent and never exposes
   torn chunk/section state. Multi-region operations are not globally atomic unless
   separately certified; their visibility, partial-failure, and history semantics are
   governed by §4d and the compatibility inventory. DEGRADED and DISABLED features follow
   §5. Correctness beats throughput whenever correctness and throughput conflict.
2. **Performance** — FAWE's reason to exist is speed; a port that is safe but slow is a
   failure. Folia-side performance gets explicit budgets (§8) measured against the Paper
   FAWE baseline on identical hardware/scenarios. Regionization should be exploited as an
   opportunity (true parallelism across regions), not merely endured.

## 4c. API thread-context contract (FROZEN process — Codex r1 F3)

Before implementation decomposition, freeze and co-sign an API thread-context contract.
For every supported FaweAPI, EditSession, Extent, and TaskManager entry point, record:
binary/source compatibility; legal caller contexts (region, entity, global, async, FAWE
worker); required target context; blocking/return behavior; completion point; callback
thread; cancellation; and error propagation. "Caller constraints" excludes only Bukkit
access performed directly by the caller; FAWE remains responsible for its internal routing.
Every location-free legacy method must be explicitly PRESERVED with deterministic context
derivation, DEGRADED with a deterministic failure, or excluded by a signed spec amendment —
never routed arbitrarily or allowed to block a tick thread.

## 4d. Operation semantics: success, partial failure, history (FROZEN — Codex r1 F4)

An operation may report success only after every accepted world mutation and required
server-owned finalizer has succeeded. Every asynchronous failure must reach the actor/API
completion exactly once. History must describe exactly the mutations that committed and
must be usable only after its configured persistence boundary. Cancellation, timeout,
world/chunk unload, ownership migration, and plugin disable must either complete cleanly
or return an explicit partial-failure result with a valid matching undo record;
whole-operation atomicity must not be implied unless certified.

## 5. Compatibility policy — documented degradation

- Everything portable is ported identically.
- A feature proven non-portable at 100% is either cleanly disabled with a clear operator
  message, or degraded with documented behavior. **Never silent semi-functioning.**
- Every degradation is recorded in the compatibility inventory with its reason and the
  evidence (spike result or upstream constraint). Any degradation changes the signed
  contract (spec amendment, both signatures).
- Expected hard cases settled by spikes, not assumption. The spike disposition table
  includes at minimum: //regen, snapshots, full relight, cross-region operations,
  chunk packet resend (Codex r1 F10).
- Every spike defines evidence and pass/fail criteria plus GO, DEGRADE, DISABLE, or STOP
  outcomes; STOP or contract-changing outcomes require spec amendment and both signatures
  before dependent work continues.

**Persistent data (Codex r1 F6):** the port must preserve upstream config, history,
clipboard, schematic, and summary-database paths and formats. Gates must start from
fixtures produced by the unported base revision, verify read/undo/redo/save/restart on
Folia, then verify the resulting state remains readable on Paper. Any format change
requires a separately signed, versioned, idempotent, crash-safe migration with backup,
rollback/downgrade policy, and failure recovery; "no compatibility shims" does not waive
persistent-data compatibility.

## 6. Assurance plan — MAX (all modules armed)

Named risk: **silent chunk/world corruption and server deadlock — expensive, irreversible,
invisible to code review.**

| Module | Perimeter |
|---|---|
| Spec co-signature by second model family (Codex) | this spec + frozen contracts (§1b, §4c, §4d, §7.2 inventory, §8 budgets) — adversarial rounds until convergence |
| Adversarial cross-review (other model family, fresh thread) | every delivery touching: queue pipeline, adapters/NMS, scheduler SPI, history persistence, **harness/oracle, API contract, bootstrap/detection, lifecycle, global-state removal, compatibility inventory** (Codex r1 F10) |
| Double-reviewer | critical domains: concurrency/thread-ownership, chunk write path, data persistence |
| Runtime harness as wave-0 deliverable | Folia server automation (26.1.2 per A2.1), FAWE operation scenarios, verification per §7.3, perf gates per §8; the harness and oracle themselves receive independent review |
| Targeted deterministic tests | scheduler routing, failure injection, persistence round-trips — where the harness cannot deterministically cover |
| Go/no-go spikes in earliest wave | regionized chunk write path; lighting; //regen; snapshots; cross-region ops; packet resend; API thread-context semantics |
| Wave-closure certification review | every wave with intersecting perimeters (queue ↔ adapter ↔ scheduler) |
| Multi-session machinery | REPRISE/handoff files — the effort will span many sessions |
| Heterogeneous workers (Codex CLI) | majority of development (user-directed) + deep audits |

## 7. Done conditions

1. Full Gradle build green with all modules.
2. **Compatibility inventory (§7.2):** frozen and co-signed in wave 0 BEFORE dependent task
   decomposition. Its coverage baseline enumerates every public/player entry point, caller
   context, adapter-owned state class, persistence surface, lifecycle transition, and
   degradation. At the end: every item CERTIFIED-identical, DEGRADED-documented, or
   DISABLED-documented; zero UNKNOWN. (Codex r1 F5)
3. **Certification (§7.3):** harness gate suite green on Folia 26.1.2 (A2.1).
   Certification compares canonical block states, biomes, block-entity NBT, entities,
   lighting/heightmaps and relevant persisted metadata **after save, unload, and restart**;
   asserts no ticket/listener/task leaks; and exercises overlapping edits, region
   boundaries, concurrent players, cancellation, unload, disable, and injected failures.
   The public-API gate uses a separately compiled consumer plugin.
4. **Regression matrix (Codex r1 F8):** a signed matrix enumerates both existing artifacts
   and every supported adapter/runtime, with boot/linkage/no-Folia-classloading checks and
   functional command/API/edit/undo/history scenarios; no listed Paper or Spigot target may
   remain untested.
5. **Reviews (Codex r1 F10):** release requires zero open BLOCKING or MAJOR findings;
   accepted MINOR findings require recorded owner disposition. Certification reviews per
   wave done.
6. Performance within the frozen budgets of §8.
7. Operator doc (FOLIA.md) and port report delivered.

## 8. Performance & resource budgets (FROZEN process — Codex r1 F9; freeze schedule amended by A2.3)

Two-stage freeze per Amendment A2.3 (signed 2026-07-17): wave 0 freezes the budget
STRUCTURE (workloads, metrics, gate forms, measurement machinery, pinned base revision,
probe-observed reference numbers); the NUMERIC thresholds are frozen at the W1-exit gate,
derived mechanically from baseline distributions once the wave-1 FAWE driver exists
(ratio = baseline percentile × (1 + slack), slack values co-signed). No wave-2 task
dispatches before the numeric freeze is user-signed + co-signed. Wave-1 tasks carry
interim guards (harness scan gates + w08 §6b reference ceilings). The budget dimensions
remain as originally frozen:
workloads and edit sizes; region counts and concurrency; hardware/JVM/config; warm-up and
trials; Paper baseline; median/p95/p99 completion time and throughput; per-region tick-time
impact; scheduler queue depth; outstanding futures/tickets/chunks; peak heap/RSS and GC;
and shutdown drain time. Folia backpressure must use bounded resources and
ownership-relevant signals, not a fabricated global TPS. "No degraded performance" means
"no regression beyond the frozen budgets."

The budget artifact defines two distinct comparisons with separately named thresholds:
1. **Non-Folia regression:** ported Paper/Spigot paths versus the unported base revision on
   the workloads and runtimes designated by the signed regression matrix.
2. **Folia performance:** the ported Folia backend versus the ported Paper/Mojang backend on
   matched hardware, configuration, and scenarios.

"Paper baseline" in §4b refers to comparison (2) and does not satisfy comparison (1).

## 9. Constraints & conventions

- Code, comments, config in English. Match FAWE upstream style and conventions.
- Conventional Commits, no attribution footers, no fabrication context in messages.
- Tests: only those defined by the assurance plan (§6) — the harness, its gates, and the
  targeted deterministic tests. No cosmetic coverage.
- Async-first, fail-fast internally, graceful operator-facing handling.
- All orchestration and project-control artifacts live in `.orchestrate/folia-port/`
  (board updated at every transition). (Codex r1 F11)

## 10. Decision log (spec-level)

- Fork strategy: dual-platform in-tree. Rejected: divergent Folia-only fork (loses upstream fixes, loses Paper test surface).
- Targets: Folia 26.1.1 + 26.1.2 only. AMENDED pre-signature: Canvas 26.2 dropped by user decision — Canvas is a Folia fork, no extra API expected; revisit on concrete need.
- API guarantee: yes, per §4c contract. Rejected: player-commands-first (a prod server consumes FAWE from other plugins).
- Degradation policy: documented degradation. Rejected: strict all-or-nothing (could force Folia patches out of scope).
- Naming: fork renamed to falistos/FastAsyncWorldEdit; old 2022 repo preserved as FastAsyncWorldEdit-legacy.
- v2: all Codex r1 findings accepted (5 BLOCKING, 5 MAJOR, 1 MINOR/editorial). Deviation from user's global "no tests" preference: targeted deterministic tests admitted, justified by assurance=max — surfaced to user at re-signature.
- Amendment 2 (2026-07-17, user-signed; see `amendment-2-draft.md`): A2.1 targets → Folia 26.1.2-only with active 26.2 preparation (26.1.1 never published — three-source proof); A2.2 A1.1 evidence wording corrected (bukkit.yml route only; DISABLE stands); A2.3 numeric budget freeze rescheduled to W1-exit (see §8).
- Routing (user): Codex majority for development + Opus; Sonnet only for very simple tasks.
