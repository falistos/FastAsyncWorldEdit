# Spec Amendment 1 (USER-SIGNED 2026-07-17 — Codex co-signature in the re-certification round)

> NOTE (2026-07-17): A1.1's evidence clause is CORRECTED by Amendment A2.2
> (`amendment-2-draft.md`, user-signed) — only the `bukkit.yml` route was disproven at
> runtime; the DISABLE disposition stands on the alternative being unproven and lossy.
> Read A1.1 below through that correction.

Batch amendment from wave-0 spike dispositions. Applies to spec v3.

## A1.1 — //regen: DISABLE on Folia (from W0.4, closed by W0.2 SCRATCH_RESULT)

Spec §5 inventory disposition: `//regen` = DISABLED on the Folia backend.
- Evidence: Folia forbids runtime world creation (`CraftServer.createWorld` throws
  `UnsupportedOperationException`); the temp-ServerLevel + `pollTask` pump is region-owned;
  the last DEGRADE option (plugin-declared scratch world loaded at startup) is closed by
  runtime evidence `FAWE_HARNESS_SCRATCH_RESULT loaded=false` (Folia does not load
  plugin-declared worlds at boot).
- Operator-facing behavior: command fails fast with a clear message on Folia; unchanged on
  Paper/Spigot. Wording per spikes/w04-regen.md.

## A1.2 — Relight: DEGRADE to NMSRelighter on Folia (from W0.3, supported by W0.2)

- Starlight-based `PaperweightStarlightRelighter` is STOP-as-structured on Folia (global
  main-thread scheduling; 1024-chunk multi-region batches; completion callbacks on
  non-owner threads — confirmed at runtime by `FAWE_HARNESS_RELIGHT_CHUNK_CALLBACK
  owner=false thread=Paper_Common_Worker`).
- Folia backend routes relight to FAWE's detached `NMSRelighter` (worldedit-core, §1b-clean
  by construction) + the `fillLightNibble` commit path (runtime-verified legal from commit
  context: `FAWE_HARNESS_QUEUE_SECTION_OK owner=true`).
- Documented degradation: relight quality/throughput may differ from Starlight; §8 budget
  slot gates whether this holds or a Folia-safe Starlight salvage becomes wave-4 work
  (contract clause L5 of spikes/w03-lighting.md).
- Conditional: if NMSRelighter blows the frozen §8 budget, this amendment's DEGRADE is
  re-opened (recorded trigger).

## A1.3 — Tick-limiter: DISABLE on Folia (from W0.5)

The deprecated stack-depth tick-limiter (static maps + global TPS) is disabled on the
Folia backend (racy under region threads, C6 violation). Paper behavior unchanged.

## A1.4 — Commit-contract addition (from W0.2 runtime evidence)

Added to architecture C1 (wording): completion callbacks of any server-async facility
(chunk load futures, relight callbacks) are UNTRUSTED thread contexts — every consumer
re-dispatches to the owning context before touching live state or FAWE shared state.
(Runtime evidence: relight callbacks on `Paper_Common_Worker`, `owner=false`.)

## Signatures

- User: SIGNED 2026-07-17
- Codex co-signer: PENDING (present with the W0.10b architecture co-signature round)
