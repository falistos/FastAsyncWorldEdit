# Task 03 — SPIKE: lighting under regionization (W0.3)

- **Wave:** 0
- **Depends on:** none
- **Status:** todo
- **Executor:** opus (read-only analysis; writes only its report)

## Objective
GO/DEGRADE/DISABLE/STOP disposition for FAWE lighting on Folia 26.1.x: can FAWE's relight
and light-data manipulation be made §1b-compliant (owning-region execution only), and at
what cost? Deliver `spikes/w03-lighting.md` with evidence and a recommended contract.

## Out of scope
No source changes. No server runs. Do not analyze //regen (task 04) or packet resend
mechanics beyond lighting's own needs (task 05).

## Binding references
- spec §1b (ownership/liveness invariants — the compliance bar), §5 (spike disposition
  rules: evidence + pass/fail + GO/DEGRADE/DISABLE/STOP).
- architecture.md §1 (prepare/commit model), §3 C1/C2.
- Recon anchors (verify, then go deeper): `spikes/recon-adapters-nms.md` §4c (light-engine
  mutation points), `PaperweightStarlightRelighter` (adapter-26.1), core relight packages
  (`com.fastasyncworldedit.core.extent.processor.lighting`, NMSRelighter), and the light
  finalizers in `PaperweightGetBlocks` (fillLightNibble, removeSectionLighting).

## Context & decisions
Key questions to answer with code evidence:
1. Which lighting paths mutate live LevelLightEngine state off-thread today, and can each
   become a region-scheduled commit (per-chunk granularity) without cross-region light
   propagation races? Light propagates across chunk borders — establish what starlight's
   `serverRelightChunks` needs (whole-region? neighbor radius?) and whether Folia exposes a
   safe entry (does Folia itself relight on region threads? cite Folia sources/API if you
   can find them locally; mark unverifiable externals as ASSUMPTION).
2. FAWE modes: full relight vs `fillLightNibble` direct data injection vs skip-light — which
   survive, which degrade, per mode.
3. Cost model: relight scheduled per owning region — expected serialization points.

## Escape hatch
If the disposition genuinely depends on runtime behavior no static analysis can settle,
say exactly what experiment W0.2's prototype must run to decide it — do not guess.

## Acceptance criteria
`spikes/w03-lighting.md`: per-path inventory (file:line), disposition table per lighting
mode with GO/DEGRADE/DISABLE/STOP + evidence, proposed frozen contract wording for the
lighting portion of the chunk-commit pipeline, open runtime questions for W0.2.

---
## Dev record (worker fills this in on completion)

**Status:** DONE_WITH_CONCERNS

**File List:**
- `.orchestrate/folia-port/spikes/w03-lighting.md` (new — the spike report)
- `.orchestrate/folia-port/tasks/03-spike-lighting.md` (this Dev record)

No source changes. No server runs. Read-only analysis only.

**Completion Notes:**
Per-path dispositions delivered with file:line evidence and a proposed frozen contract
(L1–L5). Summary:
- **fillLightNibble** commit-path injection: **GO** — per-chunk, single-section, runs inside
  `internalCall` → owning-region under the W0.2 commit contract; no cross-region reach.
- **skip-light** (`MODE=0` / `SideEffect.LIGHTING` off): **GO** — no relighter, no off-thread
  live-light work.
- **GET-side light reads** (`getSkyLight`/`getEmittedLight`): **GO with fix** — they hide a
  `queueSectionData` write during a read; must be region-captured in the GET snapshot.
- **NMSRelighter** (FAWE's core Java engine): **GO / recommended DEGRADE target** — fully
  detached (worldedit-core, zero NMS), computes over GET snapshots, writes detached SET,
  materializes via fillLightNibble. §1b-clean. Slower/lower-quality than starlight → §8 + §5.
- **PaperweightStarlightRelighter** (today's Folia default via `PaperLib.isPaper()`): **STOP
  as-structured** — global main-thread scheduling (`TaskManager` throws on Folia), 1024-chunk
  multi-region batches, ticket/packet ops on arbitrary completion threads. Recommendation:
  DEGRADE to NMSRelighter for wave 0; starlight salvage is a gated future option (L5).

Recommended contract: Folia backend routes relight to NMSRelighter (documented degradation),
keeps the fillLightNibble commit path. Verified `RegionScheduler.execute(plugin, world, cx,
cz, Runnable)` exists in the pinned `folia-api-26.1.2.build.8-stable.jar`; folia-api exposes
**no** relight API (starlight is a moonrise internal, off the API surface).

**Deviations:** None. No frozen contract contradicted; no redesign proposed. The report
stays within §1b/§5/§4b/§4d and architecture §1/§3 as given. Two ASSUMPTIONs flagged in the
report: (1) `PaperLib.isPaper()==true` on Folia (near-certain, drives factory default);
(2) moonrise starlight internals unverifiable statically — deferred to W0.2 as runtime
questions, not guessed.

**Attack points (for adversarial review):**
- The GO for fillLightNibble is *conditional on W0.2* proving `internalCall`/finalizer runs on
  the owning region. If W0.2's commit contract lands differently, re-check L1.
- The starlight STOP could be challenged as premature DISABLE. I deliberately left it as a
  gated conditional-GO (L5) rather than DISABLE-forever, because the cross-region correctness
  question (§6 Q3) is genuinely undecided — a reviewer may argue for a harder DISABLE, or
  conversely that the salvage deserves wave-0 effort. Both are policy calls above a spike.
- NMSRelighter perf may blow the §8 Folia budget (§6 Q5). If it does, the DEGRADE recommendation
  is undermined and starlight salvage or a new relighter becomes mandatory — the whole
  disposition then hinges on that one number.
- `getSkyLight`/`getEmittedLight` hidden `queueSectionData` write (§3.3) is easy to miss and
  would silently violate §1b if treated as a pure read; worth a second pair of eyes.
