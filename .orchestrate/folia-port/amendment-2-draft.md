# Spec Amendment 2 (W0-exit batch — USER-SIGNED 2026-07-17; Codex co-signature in the re-certification round)

Batch amendment resolving certification-r1 findings F4/F5/F6
(`codex-w0-certification.md`). Applies to spec v3 + Amendment 1. Presented as a single
batch per the no-piecemeal-signature rule.

## A2.1 — Platform targets: Folia 26.1.2 only (resolves F5)

Spec §2 target row "Folia 26.1.1 / 26.1.2" is replaced by:

| Folia 26.1.2 (stable, build ≥ 8) | Sole Folia target, full certification |

- Evidence (verified 2026-07-17, three independent sources): PaperMC Fill v3 API lists no
  Folia 26.1.1 (26.1 family = [26.1.2], builds 6–8); PaperMC Maven `folia-api` has no
  26.1.1 artifact (jumps 1.21.11-R0.1-SNAPSHOT → 26.1.2.build.8-stable); Folia GitHub
  branch `ver/26.1.x` commit "Update to 26.1.2" (4729256c47) bumps `gradle.properties`
  directly from 1.21.11 to 26.1.2 — no 26.1.1 version ever existed in Folia's history.
  (Paper did ship MC 26.1.1; Folia skipped it.)
- Consequential edits: spec §7.3 done-condition 3 and the harness mandate (§6 module 4)
  read "Folia 26.1.2"; `harness/versions.env` drops the 26.1.1 placeholder; the W0.9
  version guard accepts exactly `26.1.2` (already its runtime-verified behavior).
- 26.2 preparation clause (user-directed 2026-07-17): the effort ACTIVELY TRACKS the
  `ver/26.2.x` line — the in-tree `adapter-26.2` stays in the build matrix, the orchestrator
  checks Fill for a published Folia 26.2 stable at every wave gate, and wave-5 certification
  reserves a 26.2 slot. Adding 26.2 as a CERTIFIED target when it publishes remains a new
  signed amendment (scope stays 26.1.2-only until then), but the codebase must not accrue
  decisions that would make that switch harder (flag any 26.1.2-specific assumption in dev
  records).

## A2.2 — A1.1 evidence wording corrected (resolves F6; disposition unchanged)

A1.1's evidence clause "the last DEGRADE option (plugin-declared scratch world loaded at
startup) is closed by runtime evidence `FAWE_HARNESS_SCRATCH_RESULT loaded=false` (Folia
does not load plugin-declared worlds at boot)" is replaced by:

> The `bukkit.yml` generator-declaration route for a scratch world was disproven at
> runtime (`FAWE_HARNESS_SCRATCH_RESULT loaded=false`). Other startup-provisioning routes
> (pre-provisioned level folder + config declaration) were NOT experimentally exercised
> (w04-regen.md §Q1 names them and requires region-scheduled generation/readback evidence
> before any such alternative is considered viable). `//regen` = DISABLE stands on the
> alternative being unproven AND lossy (w04-regen.md §157): it would regenerate into a
> scratch dimension whose seed/dimension parity with the live world is not guaranteed by
> any tested mechanism.

DISABLE disposition, operator-facing behavior, and Paper/Spigot non-impact are unchanged.
Reopen trigger: a wave-4 task MAY re-test the untested provisioning routes; promoting
`//regen` from DISABLE requires a new signed amendment.

## A2.3 — Numeric budget freeze rescheduled to W1-exit (resolves F4)

Spec §8 "Wave 0 must freeze numeric performance/resource budgets before core
implementation" is amended to a two-stage freeze:

1. **W0 (done):** budget STRUCTURE frozen — workloads, metrics, dual-comparison design,
   gate forms, measurement machinery (G1–G8), certification-hook attachment, pinned base
   revision `f53400f00`, plus probe-observed reference numbers (w08 §6b).
2. **W1-exit (new hard gate):** numeric threshold freeze — REG_*/FOLIA_* ratios, tick/
   resource ceilings, `maxInFlightPerRegion` — derived MECHANICALLY from baseline
   distributions (base revision on Paper 26.1.2; ported backend on Paper 26.1.2) once the
   wave-1 FAWE driver exists, per w08 §9.5 (ratio = baseline percentile × (1 + slack),
   slack values co-signed). **No wave-2 task dispatches before the numeric freeze is
   user-signed + co-signed.** Wave-1 implementation tasks carry interim regression guards:
   harness scan gates + probe reference numbers (w08 §6b) as sanity ceilings.

Rationale: the REG_*/FOLIA_* baselines require driving real FAWE operations; the FAWE
driver is itself a wave-1 deliverable (`HARNESS_FAWE_DRIVER_READY` slot). Wave 0 could
only have invented numbers — contrary to the no-invented-thresholds rule the certification
itself endorses (F4: "§6b is accurate and did not invent thresholds").

## Signatures

- User: SIGNED 2026-07-17 — A2.1 (option "26.1.2 + prepare 26.2"), A2.2 and A2.3 (full
  text reviewed before signature).
- Codex co-signer: PENDING (re-certification round mandate includes this batch)
- Consequential edits applied 2026-07-17: spec §1/§2/§2b-adjacent/§6/§7.3/§8 + decision
  log; `harness/versions.env` (26.1.1 placeholder removed); `FoliaSupport`
  CERTIFIED_MINECRAFT_VERSIONS → {"26.1.2"}; `w08-perf-budgets.md` §6 header.
