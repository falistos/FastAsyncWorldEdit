# Task 04 — SPIKE: //regen feasibility on Folia (W0.4)

- **Wave:** 0
- **Depends on:** none
- **Status:** done (disposition: DISABLE)
- **Executor:** opus (read-only analysis; writes only its report)

## Objective
GO/DEGRADE/DISABLE/STOP disposition for `//regen` on Folia 26.1.x. Deliver
`spikes/w04-regen.md` with evidence.

## Out of scope
No source changes, no server runs. Lighting internals (task 03), packet mechanics (task 05).

## Binding references
- spec §1b, §5 (disposition rules; //regen is a named hard case).
- architecture.md §1, §3 C1/C2.
- Recon anchor: `spikes/recon-adapters-nms.md` §3 (Regen bullet) —
  `worldedit-bukkit/adapters/adapter-26.1/.../regen/PaperweightRegen.java`: builds a whole
  new `ServerLevel` via `QueueHandler.sync`, reflectively edits the Bukkit worlds map,
  drives generation via `chunkSource.pollTask()`, closes the chunk source. Also the core
  entry: `worldedit-core` regen options/commands and `com.sk89q.worldedit.world.World#regenerate`.

## Context & decisions
Answer with code evidence:
1. What exactly does the temp-ServerLevel trick require from the server (main-thread-only
   constructors? global registries? chunk system hooks?), and which requirements break
   under Folia's regionized chunk system.
2. Are there alternative paths: (a) Folia-safe world generation API (does 26.1 expose
   chunk generation without a live ServerLevel — e.g. via the same machinery the vanilla
   generator uses off-region?); (b) generating into a detached buffer with our W0.2
   prepare/commit pipeline for placement; (c) restricting //regen to the global region /
   a dedicated context. For each: §1b compliance, correctness (biomes, structures, NBT),
   complexity class.
3. If nothing is fully compliant: precise DEGRADED (e.g. "regen without structures", or
   "regen only in unloaded chunk areas") or DISABLED wording per spec §5.

## Escape hatch
Same as spec §5: if disposition needs runtime evidence, specify the exact W0.2/harness
experiment; do not guess. Unverifiable Folia-internal claims are marked ASSUMPTION.

## Acceptance criteria
`spikes/w04-regen.md`: requirement inventory (file:line), per-alternative analysis,
disposition + proposed operator-facing wording if DEGRADE/DISABLE, open runtime questions.

---
## Dev record (worker fills this in on completion)

- **Status:** done — disposition **DISABLE** (fail-closed on the Folia backend).
  Contract-changing (§5): requires a signed spec amendment + compatibility-inventory entry
  before dependent work treats regen as final. Not a STOP (no frozen-contract conflict).
- **File List:** `.orchestrate/folia-port/spikes/w04-regen.md` (full report; requirement
  inventory, per-alternative analysis, disposition + operator wording, open runtime
  questions). No source changes (read-only spike).
- **Completion Notes:** FAWE regen requirements R1-R7 (+ vanilla-WE capture path)
  inventoried with file:line. Folia impossibility proven from Folia server source patches
  (cloned, not assumed): runtime `createWorld`/`unloadWorld` throw UnsupportedOperationException;
  `ServerChunkCache` chunk pump asserts region ownership; `MinecraftServer.pollTask` throws;
  chunk read + per-world state are region-thread-only. All alternatives (Folia-safe gen API /
  detached buffer / restricted context) collapse onto one missing capability: off-region,
  world-less chunk generation, absent in 26.1. One lossy DEGRADE (startup-loaded scratch
  world) deferred behind open runtime question Q1.
- **Deviations:** None from task scope. Cloned Folia source (per verify-at-source directive)
  to upgrade Folia-internal claims from ASSUMPTION to VERIFIED; only Q1/Q2 remain runtime-open.
- **Attack points:** (1) Folia patch read at HEAD not pinned 26.1.2 — line numbers may drift,
  but invariants are Folia's founding architecture; re-confirm at the harness gate.
  (2) Is DISABLE too conservative vs the scratch-world DEGRADE? It's blocked on unverified Q1
  and lossy on seed/biome override — adopting now would be guessing (§5 forbids).
  (3) Possible missed lower-level async-gen hook — both 26.1 regen impls build a full
  ServerLevel; worth an adapter-owner second look.
