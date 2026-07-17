# Task 05 — SPIKE: physics suppression & chunk packet resend without global toggles (W0.5)

- **Wave:** 0
- **Depends on:** none
- **Status:** todo
- **Executor:** opus (read-only analysis; writes only its report)

## Objective
Design evidence for two C1-critical mechanisms that today rely on forbidden global state:
(1) physics/updates suppression during edits (currently static `ChunkListener.physicsFreeze`
+ `AsyncCatcher.enabled` disable), and (2) chunk packet resend to viewers (currently
`MinecraftServer.execute` + chunkMap reads). Deliver `spikes/w05-physics-packets.md`.

## Out of scope
No source changes, no server runs. Lighting (03), regen (04).

## Binding references
- spec §1b (AsyncCatcher/TickThread must never be disabled/bypassed — hard bar).
- architecture.md §1, §3 C1/C6.
- Recon anchors: `spikes/recon-adapters-nms.md` §5 (BukkitQueueHandler.startUnsafe/endUnsafe),
  §3 (sendChunk), `spikes/recon-queue-threading.md` §4 (physicsFreeze);
  `worldedit-bukkit/.../listener/ChunkListener.java` (what events physicsFreeze actually
  suppresses and why FAWE needs it), `NMSAdapter.beginChunkPacketSend/endChunkPacketSend`
  + `FaweBukkitWorld.getWorldSendingChunksMap` (the send-lock protocol).

## Context & decisions
Questions:
1. Physics: enumerate exactly which update classes FAWE suppresses (physics events, shape
   updates, light checks, neighbor notifications) and during which pipeline phases. On the
   new prepare/commit model, which suppressions become unnecessary by construction (commit
   happens on the owning region thread — vanilla flags like `Block.UPDATE_*` /
   `setBlockState` flags may suffice)? Which still need per-region or per-chunk scoped
   suppression, and what mechanism can scope them (per-chunk keys checked by the listener,
   region-local state)? Global mutable flags are forbidden.
2. Packet resend: on Folia, which thread owns `chunkMap`/player connection sends for a
   chunk's viewers, and is a region-scheduled `sendChunk` per chunk sufficient? What does
   the send-lock protocol (`beginChunkPacketSend`) protect against, and does the commit
   model subsume it?
3. Interaction with Folia's own async-safety checks: which vanilla/Paper methods called
   during commit still perform TickThread assertions worth pre-verifying.

## Escape hatch
Runtime-dependent points → name the exact W0.2 experiment. Unverifiable Folia internals →
ASSUMPTION, listed at top.

## Acceptance criteria
`spikes/w05-physics-packets.md`: suppression inventory (file:line, purpose), per-item
disposition on the commit model (unnecessary / vanilla-flag / scoped mechanism + design),
packet-send design for the commit pipeline, list of runtime questions for W0.2.

---
## Dev record (worker fills this in on completion)

**Status:** DONE — outcome GO for both mechanisms; no BLOCKED, no contract conflict.

**File List:**
- `spikes/w05-physics-packets.md` (new) — the spike report.
- `tasks/05-spike-physics-packets.md` (this Dev record).
No source changes (read-only spike, as scoped).

**Completion Notes:**
- Physics suppression: verified by whole-tree grep that the global-freeze path
  (`ChunkListener.physicsFreeze` + `AsyncCatcher.enabled` disable) is **orphaned** — its only
  entry points (`TaskManager.runUnsafe`, deprecated `QueueHandler.startSet/endSet`) have zero
  in-tree callers, and `itemFreeze` is never set true. It is also **unnecessary by construction**:
  the bulk path swaps whole `LevelChunkSection`s via raw CAS (`setSectionAtomic`), firing no
  vanilla events. Disposition: do not port the global flag; `AsyncCatcher` disable REMOVED on
  Folia (forbidden by §1b); per-block physics already governed by the vanilla `setBlockState`
  flag; tick-limiter (`@Deprecated`) disabled on the Folia backend (static maps + global TPS are
  racy/C6). Scoped per-chunk suppression is designed but not currently needed.
- Packet resend: the `ChunkSendLock` protocol guards an off-thread-writer vs. main-thread-builder
  race that **cannot occur** once section swap + packet build/send both run on the chunk's owning
  region thread → send lock is subsumed and dropped on Folia. `MinecraftServer.execute` →
  `FoliaRegionDispatcher` region task on `(world,cx,cz)`; packet built there, viewers enumerated
  there. Full design + pseudocode in report §2d.
- Part 3 tabulates every commit-time NMS call with a TickThread assertion and its owning region.
- Two genuine cross-region hazards isolated: (a) `connection.send` to a foreign-region viewer;
  (b) neighbour notification across a region boundary. Both are W0.2 experiments, not blockers.

**Deviations:** none. Stayed read-only; only wrote the report + this record.

**Attack points (for adversarial review):**
- The "orphaned physicsFreeze" claim rests on a grep for `runUnsafe`/`startSet`/`endSet` callers;
  `runUnsafe` is public API, so a third-party consumer is possible (covered by W0.2 Q5). Re-run
  the grep if the pipeline changes.
- A1–A5 are unverified Folia internals (region ownership of chunkMap, cross-region
  `connection.send`, packet-build assertions, event dispatch thread, RegionScheduler API). If any
  fails, the send design falls back to per-viewer entity-scheduler dispatch — noted inline.
- Cross-region neighbour notification (Part 3 / W0.2 Q2) may force a documented degradation of the
  EVENTS side-effect at region boundaries — flagged, not resolved here.
- `removeBeacon` currently mutates the live chunk on the worker thread (not a syncTask); flagged
  as a C2 prepare/commit-split concern that overlaps the adapter task, not owned by this spike.
