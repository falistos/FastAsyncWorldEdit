# W0.7 — Compatibility Inventory (spec §7.2 coverage baseline)

Status of this artifact: **corrected per certification finding 3, freeze pending co-signature.**
Read-only enumeration. Task 07 built the item UNIVERSE; settled wave-0 spike dispositions have
now been flowed back into the matching axis-6 items (see §F), replacing their `UNKNOWN` status
and citing their source artifact + section. Items with no settled wave-0 evidence remain
`UNKNOWN` pending their owning spike / runtime question. Co-signature happens in the W0-exit
freeze round, not in this correction.

Evidence anchors are `file:line` at the repo HEAD read on 2026-07-17. Line numbers drift;
the class/method names are the durable key.

## Count summary

| Axis | Table | Items | ID prefix(es) |
|---|---|---|---|
| 1. Player entry points | §A | 31 | INV-CMD, INV-TOOL, INV-BRUSH, INV-CUI, INV-REG |
| 2. Public API entry points (coarse) | §B | 8 | INV-API |
| 3. Adapter-owned state classes | §C | 18 | INV-ADP |
| 4. Persistence surfaces | §D | 15 | INV-PERSIST |
| 5. Lifecycle transitions | §E | 10 | INV-LIFE |
| 6. Degradation candidates | §F | 11 | INV-DEG |
| **Total** | | **93** | |

Axis 1 breakdown (31): 23 command classes (INV-CMD-001…023) + 3 tool items (INV-TOOL-001…003)
+ 1 brush mechanism (INV-BRUSH-001) + 2 CUI channels (INV-CUI-001…002) + 2 registration
mechanisms (INV-REG-001…002).

Of the 93 items, 88 remain `status=UNKNOWN` (universe-only). 5 axis-6 degradation candidates
carry a settled wave-0 disposition flowed back from the spikes (§F): INV-DEG-001 (DISABLE),
INV-DEG-003 (DEGRADE), INV-DEG-005 (GO), INV-DEG-006 (GO), INV-DEG-007 (GO/DISABLE).

## Certification hooks (the §7.3 / §5 / done-condition checks each item must attach to)

Codes referenced in the "cert hook" column. Derived verbatim from spec §7.3 (done
condition 3), §5 persistent-data clause, and done conditions 4 (regression matrix) & 2b
(fail-closed boot).

- **H-STATE** — after save/unload/restart, canonical block states, biomes, block-entity
  NBT, entities, lighting, heightmaps and persisted metadata match the Paper oracle (§7.3).
- **H-CONCURRENT** — overlapping edits, region boundaries, concurrent players (§7.3).
- **H-FAILURE** — cancellation, unload, disable, injected failures produce clean completion
  or explicit partial-failure with a matching undo record (§7.3 + §4d).
- **H-LEAK** — no ticket / listener / task leaks after operation + shutdown (§7.3).
- **H-API** — separately compiled consumer plugin gate exercises the entry point (§7.3 +
  §4c).
- **H-PERSIST** — fixtures from the unported base revision: read/undo/redo/save/restart on
  Folia, then re-read on Paper; format unchanged or signed migration (§5 persistent-data).
- **H-BOOT** — boot/detection order, fail-closed outside certified matrix, no Folia
  classloading on Paper (§2b + done condition 4).
- **H-REGRESSION** — Paper/Spigot behavior-equivalence within frozen budgets on the signed
  regression matrix (done condition 4 + §8 comparison 1).

Multiple hooks per item are normal; the primary hook is listed first.

---

## §A — Axis 1: Player entry points

### A.1 Commands (registered command classes)

Granularity note: FAWE registers command *classes*; individual sub-commands are generated
by the `@Command` annotation processor (`*Registration` builders) at compile time and bound
via `PlatformCommandManager.registerAllCommands`
(`worldedit-core/.../extension/platform/PlatformCommandManager.java:423-581`). One item =
one registered command class (the stable registration site). Per-sub-command granularity is
delegated to the harness scenario list; the generation mechanism is captured as INV-REG-001.

| ID | Item | Evidence anchor | Cert hook | Status |
|---|---|---|---|---|
| INV-CMD-001 | SchematicCommands (`schematic`/`schem`/`/schem`) | PlatformCommandManager.java:449 | H-STATE, H-PERSIST | UNKNOWN |
| INV-CMD-002 | SnapshotCommands (`snapshot`/`snap`) | PlatformCommandManager.java:456 | H-STATE, H-PERSIST | UNKNOWN |
| INV-CMD-003 | SuperPickaxeCommands (`superpickaxe`/`sp`) | PlatformCommandManager.java:465 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-CMD-004 | BrushCommands (brush group root) | PlatformCommandManager.java:480 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-CMD-005 | PaintBrushCommands (`brush` sub-registration) | PlatformCommandManager.java:486 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-CMD-006 | ApplyBrushCommands (`brush` sub-registration) | PlatformCommandManager.java:487 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-CMD-007 | WorldEditCommands (`worldedit`/`we`/`fawe`) | PlatformCommandManager.java:490 | H-BOOT, H-LEAK | UNKNOWN |
| INV-CMD-008 | BiomeCommands | PlatformCommandManager.java:499 | H-STATE | UNKNOWN |
| INV-CMD-009 | ChunkCommands | PlatformCommandManager.java:504 | H-STATE, H-LEAK | UNKNOWN |
| INV-CMD-010 | ClipboardCommands | PlatformCommandManager.java:509 | H-STATE, H-PERSIST | UNKNOWN |
| INV-CMD-011 | GeneralCommands | PlatformCommandManager.java:514 | H-REGRESSION | UNKNOWN |
| INV-CMD-012 | GenerationCommands | PlatformCommandManager.java:520 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-CMD-013 | HistoryCommands | PlatformCommandManager.java:526 | H-FAILURE, H-PERSIST | UNKNOWN |
| INV-CMD-014 | HistorySubCommands (`/history`/`/frb`) | PlatformCommandManager.java:532 | H-FAILURE, H-PERSIST | UNKNOWN |
| INV-CMD-015 | NavigationCommands | PlatformCommandManager.java:540 | H-CONCURRENT | UNKNOWN |
| INV-CMD-016 | RegionCommands | PlatformCommandManager.java:545 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-CMD-017 | ScriptingCommands (CraftScripts) | PlatformCommandManager.java:550 | H-STATE, H-FAILURE | UNKNOWN |
| INV-CMD-018 | SelectionCommands | PlatformCommandManager.java:555 | H-CONCURRENT | UNKNOWN |
| INV-CMD-019 | ExpandCommands (`//expand`) | PlatformCommandManager.java:560 | H-CONCURRENT | UNKNOWN |
| INV-CMD-020 | SnapshotUtilCommands | PlatformCommandManager.java:561 | H-STATE, H-PERSIST | UNKNOWN |
| INV-CMD-021 | ToolCommands (top-level registration) | PlatformCommandManager.java:566 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-CMD-022 | ToolUtilCommands (top-level registration) | PlatformCommandManager.java:571 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-CMD-023 | UtilityCommands (`//fill`, `//regen`, etc.) | PlatformCommandManager.java:576 | H-STATE, H-FAILURE | UNKNOWN |

### A.2 Tool / wand interaction dispatch

Tools are bound via ToolCommands/ToolUtilCommands and dispatched from player interaction
events. All action dispatch is already async (`player.runAction(..., async=true)`), which is
the §4c-relevant path.

| ID | Item | Evidence anchor | Cert hook | Status |
|---|---|---|---|---|
| INV-TOOL-001 | Block interaction dispatch (HIT/OPEN → actPrimary/actSecondary) | PlatformManager.java:413-484 (`handleBlockInteract`) | H-CONCURRENT, H-STATE | UNKNOWN |
| INV-TOOL-002 | Player input dispatch (PRIMARY/SECONDARY trace tools) | PlatformManager.java:500+ (`handlePlayerInput`) | H-CONCURRENT, H-STATE | UNKNOWN |
| INV-TOOL-003 | Tool set (SelectionWand, NavigationWand, BlockDataCyler, BlockReplacer, AreaPickaxe, RecursivePickaxe, SinglePickaxe, FloodFillTool, TreePlanter, FloatingTreeRemover, LongRangeBuildTool, StackTool, QueryTool, DistanceWand, LongRangeBuildTool) | `worldedit-core/.../command/tool/` (dir) | H-STATE, H-CONCURRENT | UNKNOWN |

### A.3 Brushes

Brushes plug into `BrushTool` and execute through the INV-TOOL dispatch path. ~40 brush
types across two dirs; per-brush granularity delegated to harness scenarios (escape hatch:
enumerated as one mechanism item + count).

| ID | Item | Evidence anchor | Cert hook | Status |
|---|---|---|---|---|
| INV-BRUSH-001 | Brush mechanism + type set (~40: `command/tool/brush/` [12] + `fastasyncworldedit/core/command/tool/brush/` [~29]) | `worldedit-core/.../command/tool/brush/`, `.../fastasyncworldedit/core/command/tool/brush/` (dirs) | H-STATE, H-CONCURRENT | UNKNOWN |

### A.4 CUI plugin channels

| ID | Item | Evidence anchor | Cert hook | Status |
|---|---|---|---|---|
| INV-CUI-001 | Incoming CUI channel `worldedit:cui` (CUIChannelListener) | WorldEditPlugin.java:253; CUIChannelListener.java:31 | H-CONCURRENT, H-LEAK | UNKNOWN |
| INV-CUI-002 | Outgoing CUI channel `worldedit:cui` (BukkitPlayer.dispatchCUIEvent → sendPluginMessage) | WorldEditPlugin.java:254; BukkitPlayer.java:330 | H-CONCURRENT | UNKNOWN |

### A.5 Registration mechanisms (escape-hatch items)

Dynamically/compile-time generated surfaces that cannot be statically enumerated per leaf.

| ID | Item | Evidence anchor | Cert hook | Status |
|---|---|---|---|---|
| INV-REG-001 | `@Command` annotation-processor sub-command generation (`*Registration` builders) | PlatformCommandManager.java:357-421 (`registerSubCommands`) | H-REGRESSION | UNKNOWN |
| INV-REG-002 | Bukkit platform command exposure (`platform.registerCommands`) | PlatformCommandManager.java:618 | H-REGRESSION, H-BOOT | UNKNOWN |

---

## §B — Axis 2: Public API entry points (coarse)

Coarse-grained per task 07 scope. The per-method disposition table is owned by **task 06**
(`spikes/w06-api-context-audit.md`, item IDs `APIC-001…`). Each area below references the
task-06 table rather than re-listing methods; the §4c contract is the binding per-method
artifact.

| ID | Area | Evidence anchor | Task-06 ref | Cert hook | Status |
|---|---|---|---|---|---|
| INV-API-001 | FaweAPI (static entry points) | `worldedit-core/.../fastasyncworldedit/core/FaweAPI.java` | APIC (FaweAPI rows) | H-API, H-FAILURE | UNKNOWN |
| INV-API-002 | EditSession public surface | `worldedit-core/.../sk89q/worldedit/EditSession.java` | APIC (EditSession rows) | H-API, H-STATE, H-FAILURE | UNKNOWN |
| INV-API-003 | EditSessionBuilder | `worldedit-core/.../sk89q/worldedit/EditSessionBuilder.java` | APIC (builder rows) | H-API | UNKNOWN |
| INV-API-004 | Extent hierarchy public surface | `worldedit-core/.../sk89q/worldedit/extent/Extent.java` (+ impls) | APIC (Extent rows) | H-API, H-STATE | UNKNOWN |
| INV-API-005 | TaskManager (location-free legacy entry points) | `worldedit-core/.../fastasyncworldedit/core/util/TaskManager.java` | APIC (TaskManager rows) | H-API, H-FAILURE | UNKNOWN |
| INV-API-006 | WorldEdit + SessionManager public methods | `worldedit-core/.../sk89q/worldedit/WorldEdit.java`, `.../session/SessionManager.java` | APIC (WE/SM rows) | H-API, H-CONCURRENT | UNKNOWN |
| INV-API-007 | QueueHandler.sync / async public entry points | `worldedit-core/.../core/queue/implementation/QueueHandler.java:249-376` | APIC (QueueHandler rows) | H-API, H-FAILURE | UNKNOWN |
| INV-API-008 | FaweAPI clipboard/history executor (`getClipboardExecutor`, uuid-keyed IO) | FaweBukkit.java:475-521 | APIC (executor rows) | H-API, H-PERSIST | UNKNOWN |

---

## §C — Axis 3: Adapter-owned state classes

One item per row of the `spikes/recon-adapters-nms.md` §6 Folia thread-ownership danger
table. This IS the C1 ownership-routing checklist. Line anchors are from adapter-26.2 in the
recon (adapter-26.1 is 1–16 lines drift, cosmetic — architecture.md notes 26.1 is the sole
certified adapter; verify exact lines in adapter-26.1 in-task).

| ID | Owned state / danger point | Evidence anchor (per recon §6) | Cert hook | Status |
|---|---|---|---|---|
| INV-ADP-001 | Packet send via `MinecraftServer.execute` (player connections, chunkMap) | PaperweightPlatformAdapter:365 | H-CONCURRENT, H-LEAK | UNKNOWN |
| INV-ADP-002 | Ticket add via `MCUtil.MAIN_EXECUTOR` (`addTicketWithRadius`) | PaperweightPlatformAdapter:330-332 | H-LEAK | UNKNOWN |
| INV-ADP-003 | Chunk fetch (`getChunkAtIfCached/Loaded/getChunk`) — ServerChunkCache | PaperweightPlatformAdapter:299-326 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-ADP-004 | ChunkMap access (`getVisibleChunkIfPresent`/`getPlayers`) | PaperweightPlatformAdapter:335-342, 393-395 | H-CONCURRENT | UNKNOWN |
| INV-ADP-005 | Live section CAS (`setSectionAtomic`) — LevelChunk sections | NMSAdapter:122-166; PaperweightGetBlocks:417,493,558 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-ADP-006 | PalettedContainer lock swap + `clearCounts` (tick counts) | PaperweightPlatformAdapter:235-257, 578-581 | H-STATE | UNKNOWN |
| INV-ADP-007 | Beacon/tile registry (`removeBeacon` → blockEntities map, listeners, ticker) | PaperweightPlatformAdapter:593-606 | H-STATE, H-LEAK | UNKNOWN |
| INV-ADP-008 | Entity slices (lookup/add via moonrise/entityManager) | PaperweightPlatformAdapter:608-628; PaperweightGetBlocks:690-704,322 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-ADP-009 | LightEngine data-layer mutation + relight | PaperweightGetBlocks:169-269,937-968; PaperweightFaweWorldNativeAccess:132-135; PaperweightStarlightRelighter:59 | H-STATE | UNKNOWN |
| INV-ADP-010 | Relighter ticket add/remove (`addTicketAtLevel`/`removeTicketAtLevel`) | PaperweightStarlightRelighter:46,77 | H-LEAK | UNKNOWN |
| INV-ADP-011 | POI manager (`updatePOIOnBlockStateChange`) | PaperweightFaweWorldNativeAccess:236 | H-STATE | UNKNOWN |
| INV-ADP-012 | Chunk broadcast (`blockChanged`/`sendBlockUpdated`) | PaperweightFaweWorldNativeAccess:158,170 | H-CONCURRENT | UNKNOWN |
| INV-ADP-013 | Global tick gating (`MinecraftServer.currentTick`) | PaperweightFaweWorldNativeAccess:64,100,109 | H-CONCURRENT | UNKNOWN |
| INV-ADP-014 | Shared ServerLevel fields (`captureBlockStates` etc., feature/structure gen) | PaperweightFaweAdapter:548-563,587 | H-STATE, H-CONCURRENT | UNKNOWN |
| INV-ADP-015 | Regen: new ServerLevel + worlds-map reflection + `pollTask` (world registry, chunk source) | regen/PaperweightRegen:103,155-208,229,257 | H-STATE, H-FAILURE | UNKNOWN |
| INV-ADP-016 | Global async guard (`AsyncCatcher.enabled` disable) | BukkitQueueHandler:36,56 | H-CONCURRENT | UNKNOWN |
| INV-ADP-017 | Thread-model foundation (`Fawe.isMainThread()`) | Fawe.java:210 | H-CONCURRENT | UNKNOWN |
| INV-ADP-018 | Bukkit global scheduler wrappers | BukkitTaskManager:19-44; BukkitServerInterface:138; WorldEditPlugin:469 | H-LEAK, H-REGRESSION | UNKNOWN |

---

## §D — Axis 4: Persistence surfaces

Per spec §5 persistent-data clause: paths and formats must be preserved; any change needs a
signed, versioned, idempotent, crash-safe migration. All items carry H-PERSIST.

| ID | Surface | Evidence anchor | Cert hook | Status |
|---|---|---|---|---|
| INV-PERSIST-001 | FaweStreamChangeSet on-disk format (VERSION=2, reads v1) | FaweStreamChangeSet.java:47-48,262,278-279 | H-PERSIST | UNKNOWN |
| INV-PERSIST-002 | DiskStorageHistory (per-edit disk changeset files) | history/DiskStorageHistory.java | H-PERSIST, H-FAILURE | UNKNOWN |
| INV-PERSIST-003 | MemoryOptimizedHistory (in-memory changeset) | history/MemoryOptimizedHistory.java | H-FAILURE | UNKNOWN |
| INV-PERSIST-004 | RollbackOptimizedHistory | history/RollbackOptimizedHistory.java | H-PERSIST | UNKNOWN |
| INV-PERSIST-005 | RollbackDatabase (SQLite summary DB) | database/RollbackDatabase.java | H-PERSIST | UNKNOWN |
| INV-PERSIST-006 | DiskOptimizedClipboard on-disk format (VERSION=2, v1/v2 header sizes) | DiskOptimizedClipboard.java:65-69,295-297 | H-PERSIST | UNKNOWN |
| INV-PERSIST-007 | MemoryOptimizedClipboard | extent/clipboard/MemoryOptimizedClipboard.java | H-FAILURE | UNKNOWN |
| INV-PERSIST-008 | CPUOptimizedClipboard | extent/clipboard/CPUOptimizedClipboard.java | H-FAILURE | UNKNOWN |
| INV-PERSIST-009 | Schematic formats: FAST_V3, FAST_V2, MCEDIT, SPONGE_V1/V2/V3, MINECRAFT_STRUCTURE, BROKENENTITY, PNG | BuiltInClipboardFormat.java:67,136,189,238,282,316,346,394,457 | H-PERSIST | UNKNOWN |
| INV-PERSIST-010 | ClipboardFormats registry + IO round-trip | clipboard/io/ClipboardFormats.java, FastSchematicReader/WriterV2/V3 | H-PERSIST | UNKNOWN |
| INV-PERSIST-011 | FAWE Settings (config.yml + config-legacy.yml) | fastasyncworldedit/core/configuration/Settings.java | H-PERSIST, H-BOOT | UNKNOWN |
| INV-PERSIST-012 | Bukkit WorldEdit config (BukkitConfiguration / YAMLConfiguration) | bukkit/BukkitConfiguration.java; util/YAMLConfiguration.java | H-PERSIST, H-BOOT | UNKNOWN |
| INV-PERSIST-013 | Session storage (JSON per-player) | session/storage/JsonFileSessionStore.java:48,97 | H-PERSIST | UNKNOWN |
| INV-PERSIST-014 | Per-player IO ordering executor (clipboard/history write ordering) | FaweBukkit.java:93,142,475-521 (`KeyQueuedExecutorService<UUID>`) | H-PERSIST, H-CONCURRENT | UNKNOWN |
| INV-PERSIST-015 | Message/lang strings (strings.json) | worldedit-core/src/main/resources/lang/strings.json | H-REGRESSION | UNKNOWN |

---

## §E — Axis 5: Lifecycle transitions

Per spec §4d: cancellation, world/chunk unload, ownership migration, plugin disable must
complete cleanly or return explicit partial-failure with matching undo record.

| ID | Transition | Evidence anchor | Cert hook | Status |
|---|---|---|---|---|
| INV-LIFE-001 | Boot + platform detection (Folia-before-Paper, fail-closed) | WorldEditPlugin.java:144,237,259 (`PaperLib.isPaper()`, onEnable) | H-BOOT | UNKNOWN |
| INV-LIFE-002 | Fail-closed outside certified matrix (missing/uncertified Folia adapter) | WorldEditPlugin.java:237-266 (no Folia branch today) | H-BOOT | UNKNOWN |
| INV-LIFE-003 | World load (`WorldLoadEvent`) | FaweBukkit.java:42,235-236 (`onWorldLoad`) | H-BOOT, H-LEAK | UNKNOWN |
| INV-LIFE-004 | World init (`WorldInitEvent`) | WorldEditPlugin.java:706-711 (`WorldInitListener`) | H-BOOT | UNKNOWN |
| INV-LIFE-005 | World unload mid-edit | (no explicit WorldUnloadEvent handler found — mechanism gap) `world.checkLoadedChunk` gating; §4d requirement | H-FAILURE | UNKNOWN |
| INV-LIFE-006 | Chunk unload mid-edit | ChunkListener.java / ChunkListener9.java; SingleThreadQueueExtent chunk-load gating | H-FAILURE, H-STATE | UNKNOWN |
| INV-LIFE-007 | Player quit mid-edit (session idle/unload) | WorldEditListener.java:45 (`PlayerQuitEvent`); RenderListener.java:134 | H-FAILURE, H-PERSIST | UNKNOWN |
| INV-LIFE-008 | Plugin disable mid-edit (session unload, command unregister, config unload) | WorldEditPlugin.java:457-471 (`onDisable`); Fawe.java:264-268 (`Fawe.onDisable`) | H-FAILURE, H-LEAK | UNKNOWN |
| INV-LIFE-009 | Server shutdown drain (outstanding commits/futures/tasks) | WorldEditPlugin.java:469 (`scheduler.cancelTasks`); QueueHandler pools; §8 shutdown-drain budget | H-LEAK, H-FAILURE | UNKNOWN |
| INV-LIFE-010 | Region ownership migration mid-edit (Folia-specific; no current handler) | Folia-only; §4d requirement; no repo anchor (new surface) | H-FAILURE, H-STATE | UNKNOWN |

Escape-hatch flags: INV-LIFE-005 (no `WorldUnloadEvent` handler found statically —
enumerated as a mechanism/gap item) and INV-LIFE-010 (Folia-only surface with no existing
code anchor).

---

## §F — Axis 6: Degradation candidates

Seeded from spec §5 hard cases (the spike disposition table's minimum set: //regen,
snapshots, full relight, cross-region operations, chunk packet resend) plus structural
global-state removals flagged by the recon reports. These are *candidates* — GO/DEGRADE/
DISABLE is decided by the wave-0 spikes (tasks 03/04/05). Per certification finding 3, settled
wave-0 dispositions are now flowed back into the Status column below, each citing its source
artifact + section. Candidates with no settled wave-0 evidence remain UNKNOWN, pending their
owning spike or open runtime question.

| ID | Candidate | Evidence anchor | Owning spike / cert hook | Status |
|---|---|---|---|---|
| INV-DEG-001 | //regen (new ServerLevel, worlds-map reflection, pollTask) | regen/PaperweightRegen.java:103,155-208,257; INV-ADP-015 | task 04 (spike-regen) / H-STATE | **DISABLE** — w04-regen.md §3 (fail-closed; no §1b-compliant path) + amendment-1-draft.md A1.1; scratch-world route negative per w02-pipeline-results.md Leg 6 |
| INV-DEG-002 | Snapshots / backup restore | SnapshotCommands (INV-CMD-002), SnapshotUtilCommands (INV-CMD-020) | spec §5 / H-PERSIST | UNKNOWN |
| INV-DEG-003 | Full relight (Starlight relighter) | PaperweightStarlightRelighter.java; INV-ADP-009/010 | task 03 (spike-lighting) / H-STATE | **DEGRADE → NMSRelighter** (Starlight STOP-as-structured on Folia) — w03-lighting.md §0/§3.5 + amendment-1-draft.md A1.2. Cross-region Starlight salvage (L5) stays gated on open runtime question w03 §6-Q3 (w02 Leg 4) |
| INV-DEG-004 | Cross-region / multi-region operations (not globally atomic unless certified) | §4b/§4d; ParallelQueueExtent multi-chunk apply | spec §4d / H-CONCURRENT, H-FAILURE | UNKNOWN |
| INV-DEG-005 | Chunk packet resend | PaperweightPlatformAdapter:344-391 (`sendChunk`); INV-ADP-001 | task 05 (spike-physics-packets) / H-CONCURRENT | **GO** (mechanism on commit model; send-lock subsumed by region-thread serialization, dispatch via FoliaRegionDispatcher) — w05-physics-packets.md §Outcome/Part 2 §2c-2d + w02-pipeline-results.md Leg 3. Residual OPEN: cross-region `connection.send` (W05-Q1/A2, client-ack pending) |
| INV-DEG-006 | Physics / neighbor updates + BlockPhysicsEvent (cross-chunk/region) | PaperweightFaweWorldNativeAccess:174-222; INV-ADP-011/012 | task 05 (spike-physics-packets) / H-STATE | **GO** (mechanism on commit model; global freeze unnecessary by construction, per-block physics via vanilla setBlockState flag) — w05-physics-packets.md §Outcome/Part 1 §1c + w02-pipeline-results.md Leg 3 (events 0 vs 94). Residual OPEN: cross-region neighbour notification (W05-Q2) |
| INV-DEG-007 | AsyncCatcher/physicsFreeze global suppression removal | BukkitQueueHandler:31-65; INV-ADP-016 | recon §1b / H-CONCURRENT | **GO** — `physicsFreeze`/`AsyncCatcher` NOT ported to the Folia backend (§1b-forbidden global toggles; already orphaned/unnecessary by construction), w05-physics-packets.md §1b/§1c (P6). Tick-limiter (P8) **DISABLE** on Folia (static maps + global TPS race region threads, C6) — w05 §1c + amendment-1-draft.md A1.3 |
| INV-DEG-008 | Global TPS backpressure → per-region signals (AsyncPreloader TPS>18 gate, getAllocate budgeting) | AsyncPreloader.java; QueueHandler.getAllocate:139-152 | spec §8 / H-CONCURRENT | UNKNOWN |
| INV-DEG-009 | Feature/structure generation (captureBlockStates on shared ServerLevel) | PaperweightFaweAdapter:548-563,587; INV-ADP-014 | spec §5 / H-STATE | UNKNOWN |
| INV-DEG-010 | CraftScripts / scripting sandbox (arbitrary async world access) | ScriptingCommands (INV-CMD-017) | spec §5 / H-FAILURE | UNKNOWN |
| INV-DEG-011 | Whole-operation atomicity guarantee (partial-failure semantics on Folia) | §4d; INV-API-002/007 | spec §4d / H-FAILURE | UNKNOWN |

---

## Cross-references & coordination

- **Axis 2 ↔ task 06:** INV-API-* items are pointers into the `APIC-*` per-method table
  (`spikes/w06-api-context-audit.md`). Do not duplicate; the §4c contract is authoritative
  per method.
- **Axis 3 = C1 checklist:** INV-ADP-* is a 1:1 restatement of `recon-adapters-nms.md` §6
  and IS the frozen ownership-routing checklist referenced by architecture.md §3 C1.
- **Axis 6 ↔ tasks 03/04/05:** INV-DEG-001/003/005/006 (+ 007) were the inputs to the wave-0
  spikes; their settled disposition (GO/DEGRADE/DISABLE) has been flowed back into §F Status,
  replacing UNKNOWN, per certification finding 3. INV-DEG-002/004/008/009/010/011 have no
  settled wave-0 disposition and remain UNKNOWN pending their owning spike / runtime question.
- **`Fawe.isMainThread()` requalification (C5):** INV-ADP-017 is the axis-3 anchor; the ~20
  call sites are inventoried in `recon-queue-threading.md` §2, requalified per C5 (not
  re-listed here — that is the C5 requalification record's job).

## Known enumeration limits (escape hatch, per task 07)

1. **Sub-commands** (INV-REG-001): individual `@Command` leaf methods are compile-time
   generated; enumerated at command-class granularity + mechanism item. Full leaf list is a
   harness-scenario concern.
2. **Brushes** (INV-BRUSH-001): ~40 brush types enumerated as one mechanism item + dir
   anchors + count; per-brush certification is a harness-scenario concern.
3. **Adapter line numbers** are from adapter-26.2 (per recon); adapter-26.1 is the certified
   adapter with ≤16-line cosmetic drift — exact 26.1 lines verified in-task.
4. **INV-LIFE-005 / INV-LIFE-010**: no existing static code anchor (missing WorldUnload
   handler; Folia-only ownership migration) — enumerated as gap/new-surface items.
5. **Tool/brush per-type** thread-context depends on the operation each performs; classified
   at dispatch granularity (INV-TOOL-001/002) here.
