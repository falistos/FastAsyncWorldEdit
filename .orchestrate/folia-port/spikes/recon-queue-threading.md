# Recon — FAWE queue & threading model (Explore agent, 2026-07-17)

## 1. Queue system (`worldedit-core/.../core/queue/`)

Thread-pool topology — all created eagerly in `QueueHandler` (`queue/implementation/QueueHandler.java:50-90`):
- `forkJoinPoolPrimary` (L50-61): CPU-bound work, sized to `Settings.QUEUE.PARALLEL_THREADS`. Custom `FaweForkJoinWorkerThreadFactory`. Receives `ApplyTask` submissions via `submit(Runnable)` L237.
- `forkJoinPoolSecondary` (L67-72): IO/cleanup ("async") tasks. `async(...)` methods L203-228.
- `blockingExecutor` (L77): the work-horse — `FaweCache.newBlockingExecutor` (`FaweCache.java:646-657`), fixed `ThreadPoolExecutor` sized to `PARALLEL_THREADS` with `CallerRunsPolicy`. Chunk writes dispatched via `submit(IQueueChunk)` L387-392 and `submitToBlocking(Callable)` L395.
- `syncTasks` and `syncWhenFree` (L82, L87): two `ConcurrentLinkedQueue<FutureTask>` drained on the server main thread.

Main-thread drain loop: `QueueHandler` implements `Runnable`; constructor (L98-100) registers via `TaskManager.taskManager().repeat(this, 1)` — sync repeating task, 1 tick. `run()` (L108-127) asserts `Fawe.isMainThread()` (L109, throws otherwise) and time-slices sync queues with `operate(...)` (L154-178), budgeting ~50ms/tick minus TPS pressure via `getAllocate()` (L139-152, reads `Fawe.instance().getTimer()`).

Parallel apply pipeline:
- `ParallelQueueExtent` (`queue/implementation/ParallelQueueExtent.java`) entry Extent. `apply(region, filter, full)` (L138-169): if >1 chunk & `PARALLEL_THREADS>1`, wraps work in `ApplyTask`, `handler.submit(...)` onto `forkJoinPoolPrimary`, blocks on `task.join()` (L156-164). Per-thread queue isolation via `FaweThreadUtil` thread-locals (`enter/exit`, `getExtent()` L99-115) — each worker thread gets its own `SingleThreadQueueExtent`.
- `SingleThreadQueueExtent` (`queue/implementation/SingleThreadQueueExtent.java`): chunk map `chunks` (Long2ObjectLinkedOpenHashMap, L54) guarded by `getChunkLock` (ReentrantLock, L56); `lastChunk` AtomicReference cache (L57); `submissions` ConcurrentLinkedQueue of in-flight futures (L55). `getOrCreateChunk` (L320-363) lazily flushes oldest chunk to blocking executor once `chunks.size() > targetSize` AND `queueHandler.isUnderutilized()` (L344). `flush()` (L481-507) drains remaining chunks then `pollSubmissions(0, true)` blocks until done.

Chunk get/set & batching/flush:
- `getOrCreateChunk` → `poolOrCreate` → `ChunkHolder.newInstance()` (`chunk/ChunkHolder.java:42`). `ChunkHolder` is a `Callable`/`Future` (`IQueueChunk<T extends Future<T>>`) holding `chunkExisting` (get) and `chunkSet` (set), applied through an `IBlockDelegate`.
- `submitUnchecked` (SingleThreadQueueExtent L238-268): key threading branch — if `Fawe.isMainThread()` runs `chunk.call()` inline (L258-259); otherwise dispatches to `queueHandler.submit(chunk)` → `blockingExecutor` (L267). Empty chunks short-circuit; `Thread.sleep(5-age)` (L245) throttles freshly-touched chunks.
- `IChunkGet` caches in `ChunkCache` (`chunk/ChunkCache.java`), synchronized `WeakReference` map, one per-world, held in `QueueHandler.chunkGetCache` (`HashMap<World, WeakReference<IChunkCache>>`, L89) guarded by `synchronized (chunkGetCache)` (L405, L513) and `getOrCreateWorldCache` (L402-417).

## 2. Fawe / FaweAPI / TaskManager — single-main-thread assumptions

- `Fawe.isMainThread()` (`core/Fawe.java:210-212`): `instance.thread == Thread.currentThread()`. `thread` captured ONCE in constructor (L102), mutable via `setMainThread()` (L461-463). `getMainThread()` L454. Binary "one main thread" model = core Folia blocker.
- `TaskManager` (`core/util/TaskManager.java`) abstract; Bukkit impl `BukkitTaskManager` (`worldedit-bukkit/.../util/BukkitTaskManager.java`) routes everything to legacy `BukkitScheduler` (`scheduleSyncRepeatingTask`, `runTask`, `runTaskLater`, `runTaskAsynchronously` L18-52) — all throw on Folia. Main-thread convenience methods: `taskNowMain` (L194), `taskWhenFree` (L310), `sync`/`syncWhenFree` (L323-374, gate on `Fawe.isMainThread()` then delegate to `QueueHandler.sync`), `runUnsafe` (L162, passes `Fawe.isMainThread()` as "parallel" flag).
- `TaskManager` holds its own default `ForkJoinPool pool` (L30) via `getPublicForkJoinPool()`.

Other `Fawe.isMainThread()` hotspots: `QueueHandler.run()` L109 + every `sync(...)` variant L331/342/353/363; `SingleThreadQueueExtent.submitUnchecked` L258; `FaweCache.java:159`; `SlowExtent.java:30`; `LazyBaseEntity.java:27`; `AbstractChangeSet.java:415`; `AbstractPlayerActor`; `LocalSession`; `EditSessionBuilder`; `PlatformCommandManager`; `PaperweightFaweWorldNativeAccess.setBlockState` L102 / `flush` L280; `BukkitWorld.checkLoadedChunk` L398; per-adapter `PaperweightPlatformAdapter`/`PaperweightFaweWorldNativeAccess` across ALL version modules.

## 3. Submitting work back to the server thread

Single choke point: `QueueHandler.sync(...)` (L249-370): off-thread callers enqueue a `FutureTask` into `syncTasks`/`syncWhenFree` + `notifySync`; main-thread `run()` loop executes. If already main thread, runs inline.

Key entry points pushing work back to the server thread:
- Chunk write finalizer: `AbstractBukkitGetBlocks.handleCallFinalizer` (`worldedit-bukkit/.../adapter/AbstractBukkitGetBlocks.java:144-183`) — chains NMS `syncTasks` (setLightCorrect, mustNotSave, block-entity/entity placement) into `queueHandler.sync(chain)` (L174), post-callback bounced onto `queueHandler.async(...)`. Built in `PaperweightGetBlocks.internalCall` (L589-763 per adapter).
- Deferred block writes: `PaperweightFaweWorldNativeAccess.flushAsync` (L264) = `taskManager().async(() -> taskManager().sync(runnableVal))`; `flush()` L280-284 inline if main thread else `sync`.
- Chunk GET load: `PaperweightPlatformAdapter.ensureLoaded` → `CompletableFuture.supplyAsync(() -> TaskManager.taskManager().sync(() -> serverLevel.getChunk(...)))` (L291); consumed blocking in `PaperweightGetBlocks` L917. `SingleThreadQueueExtent.addChunkLoad`/`preload` (L371-394) call `world.checkLoadedChunk(...)`.
- World regen: `PaperweightRegen` uses `Fawe.instance().getQueueHandler().sync(...)` to create/remove `ServerLevel` on main thread.
- Entity/actor ops: `AsyncPlayer` (~10 `TaskManager.sync` calls), `WorldWrapper` L262/272, `SimpleWorld.playEffect` L100, `ExtentEntityCopy` L196, `EntityRemover` L151, `SurvivalModeExtent` L102, `NMSRelighter` L906/943, `EditSession` L4103, `UtilityCommands` L723/755.
- Preloader: `AsyncPreloader` (`queue/implementation/preloader/AsyncPreloader.java`) on async scheduler (`laterAsync`, L34/94/126), gated by `timer.getTPS() > 18`, calls `world.checkLoadedChunk` (L130). Created by `FaweBukkit.getPreloader` (`FaweBukkit.java:275-278`).

## 4. Global singletons & shared mutable state

- `Fawe.instance` (static, `Fawe.java:83`) — captured main `thread`, lazily-built `queueHandler` (L270-279, double-checked locking), `FaweTimer timer`, `TextureUtil textures`, `uuidKeyQueuedExecutorService` (`KeyQueuedExecutorService<UUID>`, L142-149) for per-player clipboard/history IO ordering.
- `TaskManager.INSTANCE` / legacy `TaskManager.IMP` (static, L28-29).
- `QueueHandler` — shared pools, `syncTasks`/`syncWhenFree` queues, `chunkGetCache`, `CleanableThreadLocal<IQueueExtent> queuePool` (L90) pooling one `SingleThreadQueueExtent` per FAWE worker thread (`pool()` L430, `create()` L419).
- `ChunkListener.physicsFreeze` (`worldedit-bukkit/.../listener/ChunkListener.java:100`) — static global boolean toggled by `BukkitQueueHandler.startUnsafe/endUnsafe` (`adapter/BukkitQueueHandler.java:33,53`) plus static `AsyncCatcher.enabled` and Timings reflection fields (L18-28). Global physics/async-catcher suppression incompatible with per-region concurrency.
- `WorldEdit.getSessionManager()` → `SessionManager.sessions` (`worldedit-core/.../session/SessionManager.java:77`) — plain `HashMap<UUID, SessionHolder>` under coarse `synchronized` (L99-303). Read from async preloader (`AsyncPreloader.update` L60).
- `QueuePool` (`queue/implementation/QueuePool.java`) — ConcurrentLinkedQueue-backed object pool.

## Ranked main-thread-assumption hotspots for Folia

1. `Fawe.isMainThread()` (`Fawe.java:210`) — ~20 call sites; Folia needs "am I on the owning region's thread for THIS world+chunk?" — requires threading world/position through every check.
2. `QueueHandler.run()` + `sync`/`syncWhenFree` queues (L82-127, 249-376) — one global main-thread drain loop via single sync repeating task. On Folia, sync tasks must route to the region scheduler owning the target chunk. `run()` L109 assertion always fails on Folia.
3. `BukkitTaskManager` (all methods) — legacy `BukkitScheduler` throws on Folia. Needs `GlobalRegionScheduler`/`RegionScheduler`/entity schedulers + per-task location context (current `Runnable`-only signatures don't carry it).
4. `AbstractBukkitGetBlocks.handleCallFinalizer` + `PaperweightFaweWorldNativeAccess.setBlockState/flush` + `PaperweightPlatformAdapter.ensureLoaded` — chunk write/read flush path assumes any `sync` lands on the one thread owning all chunks. Must schedule onto region thread owning `(world, chunkX, chunkZ)`.
5. `QueueHandler` allocation/TPS via `Fawe.instance().getTimer()` and `AsyncPreloader` `getTPS() > 18` gate — global TPS notion; Folia TPS is per-region.
6. `ChunkListener.physicsFreeze` + static `AsyncCatcher.enabled` toggling — global mutable flags flipped around edits; unsafe when multiple regions edit concurrently.
7. `Fawe.thread` capture in constructor / `setMainThread()` — assumes plugin init runs on THE main thread.
8. Coarse shared maps: `QueueHandler.chunkGetCache`, `SessionManager.sessions` — contention/correctness under many region threads.

Note: no Folia awareness anywhere; only `Folia` grep hit is an unrelated string in `TextureUtil.java`.
