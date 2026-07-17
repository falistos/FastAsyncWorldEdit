# Recon — FAWE Bukkit/NMS adapter layer (Explore agent, 2026-07-17)

Executive conclusion: zero Folia awareness (no `Folia`/`RegionScheduler`/`TickThread`/`isFolia` hits). Every synchronization primitive assumes a single global main thread. The adapter deliberately writes chunk sections off-thread (bypassing the server) using its own lock protocol, bouncing only unavoidable server-touching bits (packet send, setBlockState, ticket add) onto "the main thread". The async-write model is not inherently incompatible with Folia, but every "main thread" hop must become a region-scheduled hop keyed on the target chunk, and several direct reads/writes of server-owned structures happen with no thread hop at all.

adapter-26.1 and adapter-26.2 are essentially identical (normalized diffs 1-16 lines, cosmetic/signature drift). Line refs below from adapter-26.2, package `...adapter.impl.fawe.v26_2`.

## 1. Adapter file inventory (adapter-26.2)

FAWE layer (`impl/fawe/v26_2/`): `PaperweightPlatformAdapter.java` (632 L, static NMS bridge: section construction, chunk load/ticket, packet send, entity/tile/beacon manipulation); `PaperweightGetBlocks.java` (1069 L, async chunk GET/SET engine); `PaperweightGetBlocks_Copy.java` (291 L, history snapshot); `PaperweightFaweWorldNativeAccess.java` (297 L, per-block setBlockState path w/ neighbor/POI/lighting side effects); `PaperweightFaweAdapter.java` (908 L, BukkitImplAdapter); `PaperweightStarlightRelighter.java` (81 L) + factory; `PaperweightPostProcessor`, `PaperweightChunkAccessProxy`, `PaperweightLevelProxy`, `PaperweightPlacementStateProcessor`, `PaperweightFaweMutableBlockPlaceContext`, `FaweBlockStateListPopulator`, `PaperweightMapChunkUtil`, `LinOps/LinValueInput/LinValueOutput`, `regen/PaperweightRegen.java`.

Vanilla-WE layer (`impl/v26_2/`): `PaperweightAdapter.java`, `PaperweightServerLevelDelegateProxy`, `PaperweightWorldNativeAccess`, `StaticRefraction`, `PaperweightDataConverters`, `PaperweightFakePlayer`, etc.

Shared bases (worldedit-bukkit, `com.fastasyncworldedit.bukkit.adapter`): `NMSAdapter.java` (setSectionAtomic, beginChunkPacketSend/endChunkPacketSend, palette building, ChunkSendLock protocol); `StarlightRelighter.java` (version-agnostic relight batching); `AbstractBukkitGetBlocks.java`.

## 2. Direct chunk-section read/WRITE (bypassing the server)

- `PaperweightGetBlocks.internalCall(...)` (L331-770) core write path: `synchronized (nmsChunk)`, grabs `nmsChunk.getSections()` (L381-382), per-layer swaps `LevelChunkSection` via `PaperweightPlatformAdapter.setSectionAtomic(...)` (L417, 493, 558).
- `setSectionAtomic` → `NMSAdapter.setSectionAtomic` (`NMSAdapter.java:122-166`): raw `ReflectionUtils.compareAndSet(sections, expected, value, layer)` on the LIVE chunk's section array, guarded only by FAWE's own `StampedLock` per (world,chunk) in `FaweBukkitWorld.getWorldSendingChunksMap`. When `Fawe.isMainThread()` skips the lock entirely (L133-135).
- Off-thread section building: `PaperweightPlatformAdapter.newChunkSection(...)` (L400-492), reflective `PalettedContainer.unpack`.
- `clearCounts(section)` (L578-581) reflectively zeroes `tickingFluidCount`/`tickingBlockCount` on the live section.
- `applyLock(section)` (L235-257): on Spigot reflectively replaces the section's `PalettedContainer` `ThreadingDetector` lock with a `DelegateSemaphore` (fields `threadingDetector`/`lock`, L160-163). On Paper: no-op thread-local semaphore.
- Section reads: `PaperweightGetBlocks.update(...)` (L818-885) reflectively reads `fieldData`→`fieldStorage`(BitStorage)→`fieldPalette` from live `PalettedContainer` under `applyLock`. `getSections(force)` (L895-913) copies live section array under ReentrantReadWriteLock.

Folia danger: all touch live chunk internals; legal only from the owning region thread (or fully detached). The DelegateSemaphore/StampedLock scheme does not coordinate with Folia's region locks — the owning region may tick that chunk concurrently.

## 3. Chunk system interaction (PaperweightPlatformAdapter)

- `ensureLoaded(serverLevel, x, z)` (L259-293): Paper path `getChunkAtAsync(x,z,true,true)` then addTicket; fallback `CompletableFuture.supplyAsync(() -> TaskManager.sync(() -> serverLevel.getChunk(x,z)))` — global-main hop.
- `getChunkImmediatelyAsync(...)` (L299-326): `ServerChunkCache.getChunkAtIfCachedImmediately`/`getChunkAtIfLoadedImmediately`/`getChunk` — region-thread-owned in Folia.
- `addTicket(...)` (L328-333): `io.papermc.paper.util.MCUtil.MAIN_EXECUTOR.execute(() -> addTicketWithRadius(...))` — global main thread, no valid Folia target. Must become region scheduler for (x,z).
- `getPlayerChunk(...)` (L335-342): reflective `ChunkMap.getVisibleChunkIfPresent`.
- `sendChunk(...)` (L344-391): reads LevelChunk from chunk source, then `MinecraftServer.getServer().execute(() -> ... nearbyPlayers(...).forEach(p -> p.connection.send(packet)))` (L365); reads `chunkMap.getPlayers` (L394). Must be region-scheduled.
- Relighter tickets: `PaperweightStarlightRelighter.chunkLoadFuture` (L44-51) `getChunkAtAsync` + `addTicketAtLevel(FAWE_TICKET,...)`; `postProcessChunks` (L69-79) `removeTicketAtLevel`. `StarlightRelighter.postProcessCallback` (`StarlightRelighter.java:75-85`) runs via `TaskManager.task(...)` "on main thread".
- Regen: `regen/PaperweightRegen.java` builds a whole new `ServerLevel` via `QueueHandler.sync(...)` (L155-208), mutates global Bukkit worlds map by reflection (`removeWorldFromWorldsMap`, L257), drives generation with `freshWorld.getChunkSource().pollTask()` (L103), `getChunkSource().close(false)` (L229). Deeply main-thread/single-owner bound.
- Feature/structure gen: `PaperweightFaweAdapter.generateFeature/generateStructure` (L564+) wrap `serverLevel.captureBlockStates` mutation + `configuredFeature.place(...)`/`structure.generate(...)` in `TaskManager.sync(...)` (L575, 612). `preCaptureStates/postCaptureBlockStates` (L548-563) flip `captureTreeGeneration/captureBlockStates`, clear `capturedBlockStates` — shared mutable ServerLevel fields.
- `PaperweightFaweAdapter.getBlock/getFullBlock` (L285-316): `handle.getChunk(x>>4, z>>4)` synchronously, NO thread hop — throws on Folia off region thread.

## 4. Per-block write path — PaperweightFaweWorldNativeAccess

- `setBlockState(...)` (L96-117): if `Fawe.isMainThread()` direct `levelChunk.setBlockState(...)`; else queues `CachedChange`, gated on `MinecraftServer.currentTick` (L100/109, global counter — per-region in Folia), calls `flushAsync`. `flushAsync` (L239-264) / `flush` (L266-286) apply via `TaskManager.sync(...)`, call `sendChunk(...)`.
- Light: `updateLightingForBlock` (L132-135) → `getLightEngine().checkBlock(pos)`.
- Neighbors/physics: `notifyNeighbors` (L174-194) `updateNeighborsAt`/`handleNeighborChanged`; `updateNeighbors` (L196-222) fires `BlockPhysicsEvent`, `updateIndirectNeighbourShapes`. Cross-chunk/region.
- POI: `onBlockStateChange` (L230-237) → `updatePOIOnBlockStateChange`.
- Broadcast: `markBlockChanged` (L167-172) → `ServerChunkCache.blockChanged(pos)`; `notifyBlockUpdate` (L151-160) → `sendBlockUpdated`.
- Tiles: `updateTileEntity` (L137-148) → `getBlockEntity(pos).loadWithComponents(...)`.

## 4b. Tile/beacon/entity manipulation

- `internalCall` removes block entities via `nmsChunk.removeBlockEntity(...)` (L371); beacons on Paper via `removeBeacon(tile, nmsChunk)` (L368) → (`PlatformAdapter:593-606`) mutates `levelChunk.blockEntities` map directly, reflectively invokes `removeGameEventListener` + `removeBlockEntityTicker`, sets BE `remove` field. Tile writes deferred into syncTasks calling `nmsWorld.getBlockEntity`/`removeBlockEntity`/`loadWithComponents` under `synchronized(nmsWorld)` (L716-744).
- Entities: `getEntities(chunk)` (L608-620): Paper `moonrise$getEntityLookup().getChunk(...).getAllEntities()`; Spigot reflective `ServerLevel.entityManager` (`PersistentEntitySectionManager`, fields L164-165, 625-628). Add/remove in syncTasks: `entity.discard()` (L322-324), `moonrise$getEntityLookup().addNewEntity(...)` (L690), `getEntitySectionManager(nmsWorld).addNewEntity(...)` (L697), `nmsWorld.addFreshEntity(...)` (L704), `nmsWorld.getEntities().get(uuid)` (L629). Run in syncTasks but touch region-owned entity slices.

## 4c. Light-engine state

Direct `LevelLightEngine` mutation off-owner: `removeSectionLighting` (L169-196), `getSkyLight`/`getEmittedLight` (L219-269), `fillLightNibble` (L937-968) — read `getLayerListener(...).getDataLayerData(...)`, mutate `DataLayer` byte arrays under `synchronized(dataLayer)`, call `queueSectionData(...)`. Relight: `PaperweightStarlightRelighter.invokeRelight` (L53-63) → `starlight$serverRelightChunks(...)`.

## 5. Bootstrap, listeners, scheduler, platform detection

- `WorldEditPlugin.java`: PaperLib-based detection only (`PaperLib.isPaper()`, L144-168, 259), no Folia branch. Listeners (L253-266): `CUIChannelListener`, `WorldEditListener`, Paper-only `AsyncTabCompleteListener`, `WorldInitListener`. Shutdown: `getServer().getScheduler().cancelTasks(this)` (L469).
- `FaweBukkit.java`: registers `BrushListener`, Paper-only `RenderListener` (L71-76), delayed `ChunkListener9` (L98), `registerEvents` (L95) via `TaskManager.task/later`. `getQueueHandler()` → `new BukkitQueueHandler()` (L108-109); `getTaskManager()` → `new BukkitTaskManager(plugin)` (L174-175).
- `BukkitTaskManager.java` wraps legacy global scheduler (L19-44). `BukkitServerInterface.java:138` `scheduleSyncRepeatingTask`. `BukkitBlockCommandSender.java:199` `callSyncMethod`.
- AsyncCatcher subversion: `BukkitQueueHandler.startUnsafe/endUnsafe` (L31-65) reflectively flips `org.spigotmc.AsyncCatcher.enabled` and toggles `ChunkListener.physicsFreeze`. On Folia, `TickThread.ensureTickThread` checks are not one global flag — cannot be disabled this way.

## 6. Consolidated Folia thread-ownership danger table

| Danger point | Location | Owned state |
|---|---|---|
| `MinecraftServer.getServer().execute` packet send | PlatformAdapter:365 | player connections, chunkMap |
| `MCUtil.MAIN_EXECUTOR.execute(addTicketWithRadius)` | PlatformAdapter:330-332 | ticket system |
| `getChunkAtIfCached/Loaded/getChunk` | PlatformAdapter:299-326 | ServerChunkCache |
| `chunkMap.getVisibleChunkIfPresent`/`getPlayers` | PlatformAdapter:335-342, 393-395 | ChunkMap |
| `setSectionAtomic` CAS on live sections | NMSAdapter:122-166; GetBlocks:417,493,558 | LevelChunk sections |
| lock swap + `clearCounts` | PlatformAdapter:235-257, 578-581 | PalettedContainer, tick counts |
| `removeBeacon` → blockEntities map, game-event listeners, ticker | PlatformAdapter:593-606 | tile registry |
| entity lookup/add via moonrise/entityManager | PlatformAdapter:608-628; GetBlocks:690-704,322 | entity slices |
| LightEngine data-layer mutation + relight | GetBlocks:169-269,937-968; NativeAccess:132-135; Relighter:59 | light engine |
| `addTicketAtLevel`/`removeTicketAtLevel` | Relighter:46,77 | ticket system |
| `updatePOIOnBlockStateChange` | NativeAccess:236 | POI manager |
| `blockChanged`/`sendBlockUpdated` | NativeAccess:158,170 | chunk broadcast |
| `MinecraftServer.currentTick` gating | NativeAccess:64,100,109 | global tick counter |
| `captureBlockStates` etc. | FaweAdapter:548-563,587 | shared ServerLevel fields |
| new ServerLevel + worlds-map reflection + pollTask | regen/PaperweightRegen:103,155-208,257,229 | world registry, chunk source |
| `AsyncCatcher.enabled` global disable | BukkitQueueHandler:36,56 | global async guard |
| `Fawe.isMainThread()` | Fawe.java:210 | thread model foundation |
| Bukkit global scheduler wrappers | BukkitTaskManager:19-44; BukkitServerInterface:138; WorldEditPlugin:469 | scheduling |

Two structural pivots: (1) replace `Fawe.isMainThread()` + `TaskManager.sync`/`QueueHandler.sync`/`MAIN_EXECUTOR`/`MinecraftServer.execute` with per-chunk region dispatch (`getRegionScheduler().execute(world, cx, cz, ...)`); (2) reconcile the AsyncCatcher-disable + `setSectionAtomic` off-thread-write model with Folia's region ownership — suppressing the thread check no longer makes concurrent access safe. Platform detection (`WorldEditPlugin.java:144`, `FaweBukkit.java`) is the natural entry point for a Folia branch.
