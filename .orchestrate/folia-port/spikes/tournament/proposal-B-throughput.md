# FAWE Folia proposal — region-lane sweep pipeline

## 0. Decision

The Folia backend uses a detached GET/prepare pipeline followed by dynamically discovered, per-region commit lanes:

```text
owning region: capture immutable GET snapshot
        ↓
FAWE workers: prepare sections, diffs, NBT, light and finalizer plans
        ↓
owning region lane: sliced region sweep over ready chunk commits
        ↓
ordered finalizers: sections → tiles → entities → POI/neighbours → light → packets
        ↓
history persistence and exactly-once operation completion
```

The commit strategy is **sliced region-sweep**. W0.2 measured, for 16 chunks across four observed regions:

| Strategy | Tasks | Maximum task cost | Visibility |
|---|---:|---:|---:|
| Region-sweep | **4** | **364 µs** | 51.321 ms |
| Batched-neighbor | 8 | 705 µs | 51.322 ms |
| Per-chunk | 16 | 551 µs | 54.964 ms |

All three were dominated by approximately one region tick of scheduling delay. Region-sweep therefore provides the best measured scheduler efficiency without sacrificing visibility. The 16-chunk result is not a large-scale performance budget, so large sweeps are time- and count-sliced.

The following rules are structural:

- Live state is accessed only through the target chunk, entity, or global scheduler, with ownership checked at execution time.
- Region and entity threads never wait for FAWE workers or another scheduler.
- FAWE workers may wait only through the bounded worker-to-owner path, without holding queue, chunk, session, or history locks.
- Work for different regions proceeds independently.
- Operations touching several regions are not globally atomic.
- Concurrent mutations of the same chunk are sequenced; mutations of different chunks are free to overtake one another.
- Region identities are performance hints, never proof of ownership. Every live-state action revalidates ownership.
- No owner-bound task uses caller-runs execution.

## 1. Exact execution interfaces

### 1.1 Platform-neutral task targets

`worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/FaweTaskTarget.java`:

```java
package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;

import java.util.Objects;

public sealed interface FaweTaskTarget
        permits FaweTaskTarget.ChunkOwner,
        FaweTaskTarget.EntityOwner,
        FaweTaskTarget.Singleton {

    record ChunkOwner(World world, int chunkX, int chunkZ) implements FaweTaskTarget {

        public ChunkOwner {
            Objects.requireNonNull(world, "world");
        }
    }

    record EntityOwner(Entity entity) implements FaweTaskTarget {

        public EntityOwner {
            Objects.requireNonNull(entity, "entity");
        }
    }

    enum Singleton implements FaweTaskTarget {
        /**
         * Legal only while already executing on a tick context. Never schedulable.
         */
        CURRENT_TICK,

        /**
         * Global-region work containing no chunk- or entity-owned access.
         */
        GLOBAL,

        /**
         * Detached work containing no live server access.
         */
        ASYNC
    }
}
```

`CURRENT_TICK` exists only to preserve documented location-free legacy behavior. New internal live-state call sites must use `ChunkOwner` or `EntityOwner`.

### 1.2 `FaweThreadContext`

`worldedit-core/src/main/java/com/fastasyncworldedit/core/util/task/FaweThreadContext.java`:

```java
package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;

public interface FaweThreadContext {

    /**
     * True for any region, entity, or global tick context.
     */
    boolean isTickThread();

    /**
     * True only when the current thread owns this chunk now.
     */
    boolean ownsChunk(World world, int chunkX, int chunkZ);

    /**
     * True only when the current thread owns this entity now.
     */
    boolean ownsEntity(Entity entity);

    /**
     * True only for the global-region context.
     */
    boolean isGlobalContext();

    /**
     * True only for an executor owned and marked by FAWE.
     */
    boolean isFaweWorker();

    default boolean owns(FaweTaskTarget target) {
        if (target instanceof FaweTaskTarget.ChunkOwner chunk) {
            return ownsChunk(chunk.world(), chunk.chunkX(), chunk.chunkZ());
        }
        if (target instanceof FaweTaskTarget.EntityOwner entity) {
            return ownsEntity(entity.entity());
        }
        return switch ((FaweTaskTarget.Singleton) target) {
            case CURRENT_TICK -> isTickThread();
            case GLOBAL -> isGlobalContext();
            case ASYNC -> isFaweWorker();
        };
    }
}
```

Semantics:

- On Paper/Spigot, `isTickThread`, `ownsChunk`, `ownsEntity`, and `isGlobalContext` all reduce to the old main-thread identity.
- On Folia, chunk/entity ownership uses the Folia ownership queries after unwrapping WorldEdit objects.
- `isFaweWorker` uses an explicit marker installed by FAWE’s thread factories, not a thread-name comparison.
- `Fawe.instance().getThreadContext()` is the sole accessor. `Fawe.isMainThread()` remains only as a deprecated Paper compatibility method and has no Folia call sites.

### 1.3 `FoliaRegionDispatcher`

`worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaRegionDispatcher.java`:

```java
package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.FaweTaskTarget;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.sk89q.worldedit.world.World;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public interface FoliaRegionDispatcher extends AutoCloseable {

    enum TaskKind {
        SNAPSHOT_CAPTURE,
        COMMIT,
        FINALIZER,
        PACKET,
        LEGACY_GLOBAL,
        ASYNC
    }

    /**
     * An observed Folia region identity. It is a grouping hint, not an
     * ownership capability, and can become stale after a split or merge.
     */
    record RegionKey(UUID worldId, long observedRegionId) {
    }

    interface TaskHandle {

        boolean cancel();

        boolean isCancelled();

        boolean isDone();
    }

    FaweThreadContext threadContext();

    /**
     * Runs inline only if the current thread already owns the target.
     * Otherwise dispatches through the corresponding Folia scheduler.
     *
     * The returned future is completed through the FAWE completion executor,
     * so dispatcher internals never run arbitrary continuations on a tick
     * thread.
     */
    <T> CompletableFuture<T> submit(
            FaweTaskTarget target,
            TaskKind kind,
            Callable<? extends T> task
    );

    default CompletableFuture<Void> execute(
            FaweTaskTarget target,
            TaskKind kind,
            Runnable task
    ) {
        return submit(target, kind, () -> {
            task.run();
            return null;
        });
    }

    /**
     * periodTicks == 0 creates a one-shot task.
     * ASYNC converts legacy ticks to 50-millisecond periods.
     */
    TaskHandle schedule(
            FaweTaskTarget target,
            TaskKind kind,
            long initialDelayTicks,
            long periodTicks,
            Runnable task
    );

    /**
     * Legal only while currently executing in a region owning part of world.
     */
    RegionKey currentRegionKey(World world);

    /**
     * Returns detached metadata learned during an earlier owner callback.
     * The result must be revalidated when used.
     */
    Optional<RegionKey> lastRegionHint(World world, int chunkX, int chunkZ);

    /**
     * Stops new submission. Already-running callbacks may finish.
     */
    void stopAccepting(Throwable reason);

    @Override
    void close();
}
```

The adapter is the only module that calls `RegionScheduler`, `EntityScheduler`, `GlobalRegionScheduler`, or `AsyncScheduler`.

Implementation rules:

- `ChunkOwner` uses `RegionScheduler`.
- `EntityOwner` uses the entity scheduler and turns entity retirement into an exceptional completion.
- `GLOBAL` uses `GlobalRegionScheduler`.
- `ASYNC` uses `AsyncScheduler` or a FAWE executor and may not access live state.
- `CURRENT_TICK` runs only if `isTickThread()` is already true; otherwise it is rejected.
- `currentRegionKey` reads the current region identity only from an owner callback. No live region object escapes the adapter.
- A stored `RegionKey` is used for queue grouping and measurements only. `ownsChunk` or `ownsEntity` remains the execution-time authority.
- Cancellation before execution cancels the scheduled task where the scheduler supports it. Once execution begins, cancellation is cooperative.

### 1.4 `FoliaBackpressure`

`worldedit-bukkit/src/main/java/com/fastasyncworldedit/bukkit/folia/FoliaBackpressure.java`:

```java
package com.fastasyncworldedit.bukkit.folia;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface FoliaBackpressure {

    enum Priority {
        INTERACTIVE,
        NORMAL,
        BULK
    }

    enum Stage {
        READY,
        SCHEDULED,
        COMMITTING,
        FINALIZING
    }

    record Demand(
            UUID operationId,
            int chunks,
            long preparedBytes,
            int finalizerChains,
            Priority priority,
            boolean previouslyAccepted,
            long deadlineNanos
    ) {

        public Demand {
            if (chunks <= 0) {
                throw new IllegalArgumentException("chunks must be positive");
            }
            if (preparedBytes < 0 || finalizerChains < 0) {
                throw new IllegalArgumentException("negative demand");
            }
        }
    }

    record Limits(
            int maxReadyChunksPerRegion,
            long maxReadyBytesPerRegion,
            int maxFinalizersPerRegion,
            int maxWaitersPerRegion,
            int maxGlobalReadyChunks,
            long maxGlobalReadyBytes,
            int maxGlobalFinalizers
    ) {
    }

    record Pressure(
            int readyChunks,
            long readyBytes,
            int finalizerChains,
            int waiters,
            int scheduledDrains,
            long oldestReadyNanos,
            long scheduleDelayEwmaNanos,
            long sliceRuntimeEwmaNanos
    ) {
    }

    interface Permit extends AutoCloseable {

        FoliaRegionDispatcher.RegionKey region();

        Demand demand();

        Stage stage();

        /**
         * Stages may advance only in the declared order.
         */
        void enter(Stage next);

        /**
         * Idempotent. Releases all remaining accounting.
         */
        @Override
        void close();
    }

    Limits limits();

    /**
     * Completes on a FAWE executor. The cancellation signal completes when
     * the operation is cancelled.
     */
    CompletionStage<Permit> acquire(
            FoliaRegionDispatcher.RegionKey region,
            Demand demand,
            CompletionStage<?> cancellationSignal
    );

    Pressure pressure(FoliaRegionDispatcher.RegionKey region);

    void recordScheduleDelay(
            FoliaRegionDispatcher.RegionKey region,
            long delayNanos
    );

    void recordSlice(
            FoliaRegionDispatcher.RegionKey region,
            int chunks,
            long runtimeNanos
    );

    void stopAccepting(Throwable reason);
}
```

A permit progresses as follows:

```text
READY      prepared bytes and one ready chunk are accounted
SCHEDULED  a region drain has accepted the item
COMMITTING owner mutation is executing
FINALIZING ready bytes/chunk are released; finalizer capacity remains
close()    finalizer capacity is released
```

Invalid transitions are implementation errors and terminate that chunk plan exceptionally.

Initial certification values are:

- 256 ready chunks per observed region.
- 64 MiB of prepared SET data per observed region.
- 64 concurrent finalizer chains per observed region.
- 256 lightweight admission waiters per observed region.
- 65,536 globally ready chunks.
- `min(1 GiB, maxHeap / 4)` globally prepared SET bytes.
- 4,096 globally active finalizer chains.

These values are configuration-backed and must be frozen or revised by the §8 workload measurements. Per-region limits do not replace the global byte cap.

### 1.5 Context-carrying `QueueHandler.sync`

The new internal interface on `QueueHandler` is:

```java
public enum SyncPriority {
    IMMEDIATE,
    WHEN_FREE
}

public final CompletableFuture<Void> sync(
        FaweTaskTarget target,
        Runnable task
) {
    return dispatchSync(target, SyncPriority.IMMEDIATE, () -> {
        task.run();
        return null;
    });
}

public final <T> CompletableFuture<T> sync(
        FaweTaskTarget target,
        Callable<T> task
) {
    return dispatchSync(target, SyncPriority.IMMEDIATE, task);
}

public final CompletableFuture<Void> syncWhenFree(
        FaweTaskTarget target,
        Runnable task
) {
    return dispatchSync(target, SyncPriority.WHEN_FREE, () -> {
        task.run();
        return null;
    });
}

public final <T> CompletableFuture<T> syncWhenFree(
        FaweTaskTarget target,
        Callable<T> task
) {
    return dispatchSync(target, SyncPriority.WHEN_FREE, task);
}

protected <T> CompletableFuture<T> dispatchSync(
        FaweTaskTarget target,
        SyncPriority priority,
        Callable<T> task
) {
    // Default Paper/Spigot adapter bridges to the existing main-thread queue.
    // FoliaQueueHandler overrides this and delegates to FoliaRegionDispatcher.
    throw new UnsupportedOperationException();
}
```

The existing three `sync`, four `syncWhenFree`, and three `async` public overloads retain their descriptors:

- `async` remains detached FAWE work and is never used for live state.
- A location-free legacy `sync` called from a tick context runs in that current context.
- A location-free legacy `sync` called from A/W derives `GLOBAL`.
- Internal lambdas that contain a world, chunk, player, or entity migrate to explicit targets. They may not use the location-free fallback.
- `runUnsafe` is retained for binary compatibility but throws before invoking the callback on Folia.
- The Folia `QueueHandler` has no global one-tick drain loop.

`QueueHandler.sync(target, ...)` never waits. A synchronous `TaskManager` wrapper may await its future only from A/W, using the operation deadline or the configured owner-wait timeout, while a lock tracker confirms that no chunk, session, queue, or history lock is held. Calls from R/E/G that cannot run locally are rejected before side effects.

## 2. GET snapshot lifecycle

### 2.1 Snapshot contents

A `FoliaChunkSnapshot` is immutable detached data containing a requested profile:

```text
BLOCKS:
  copied section palettes/block ordinals
  heightmaps required by the operation
  biome data if requested

BLOCKS_LIGHT:
  BLOCKS plus copied block- and sky-light nibbles

FULL:
  BLOCKS_LIGHT plus copied tile NBT, entity NBT/UUID/location,
  scheduled metadata required by history, and POI-facing inputs
```

No snapshot contains a `LevelChunk`, `LevelChunkSection`, live palette, live NBT collection, entity handle, light-engine object, viewer collection, or other live server reference.

### 2.2 Capture sequence

For a mutating operation and chunk `K`:

1. Register a `ChunkTicket` with the per-chunk sequencer.
2. Wait asynchronously for the preceding mutation ticket for `K` to reach a terminal state.
3. Read the current monotonic `ChunkVersion(K)`.
4. Reserve snapshot bytes from the global and per-operation snapshot budgets.
5. Single-flight the operation-local cache lookup by `(K, version, profile)`.
6. Dispatch capture with `ChunkOwner(world, cx, cz)`.
7. On the owner:
   - revalidate ownership;
   - copy section, biome, heightmap and NBT data;
   - capture light nibbles there, including any lazy `queueSectionData` creation;
   - capture entity state locally or fan out through entity schedulers where required;
   - obtain the current `RegionKey` and update the detached region-hint cache.
8. Complete the snapshot future on the FAWE completion executor.
9. Workers acquire a reference-counted `SnapshotLease`.

Read-only operations omit the mutation ticket but still use an operation-local cache. Consequently, a new operation does not reuse an old snapshot after unrelated server activity.

### 2.3 Cache policy

Snapshots are strongly referenced while cached or leased. Weak references are not used as resource management.

Initial limits:

- Global snapshot memory: `min(512 MiB, maxHeap / 8)`.
- Per-operation snapshot memory: `min(128 MiB, globalSnapshotLimit / 4)`.
- Maximum cached entries per operation: 4,096.
- Maximum single snapshot: 16 MiB of detached encoded data.

Entries with zero leases are evicted by weighted LRU. If all entries are leased, new capture is suspended at the worker-side budget rather than allocating beyond the limit. A snapshot exceeding the single-entry limit fails before any SET mutation is accepted.

Profiles avoid routinely capturing entities, tiles, or light for operations that do not use them. A superset profile may satisfy a subset request; a subset is never silently widened after capture.

### 2.4 Invalidation

`ChunkVersion(K)` is incremented on the owner thread when the first live mutation for a commit becomes effective. At that point:

- Every operation cache entry for an older version is removed from lookup.
- Existing leases remain valid immutable point-in-time data until closed.
- Any later lookup must capture the new version.
- A world unload invalidates all entries for that world and completes in-progress captures exceptionally.
- Operation termination closes its cache and releases all unleased memory.

A later mutating operation cannot capture before the preceding same-chunk ticket terminates. This prevents two FAWE operations from preparing whole-section replacements from the same base and silently overwriting one another.

The global cache retains only bounded region hints and version metadata, not snapshot contents. Region hints are capped at 262,144 entries and use access-order eviction.

## 3. SET commit flow

### 3.1 Prepared plan

Workers produce one `PreparedChunkCommit` per chunk containing:

- Operation ID and chunk ticket.
- World/chunk target and last observed region hint.
- Base `ChunkVersion`.
- Complete detached section replacements.
- Before/after history delta.
- Ordered tile operations.
- Ordered entity operations, including UUID and target ownership information.
- POI and neighbor-update plans.
- Detached block/sky-light data.
- Packet intent.
- Estimated bytes and per-phase work counts.
- Cancellation/deadline information.

Before a plan becomes accepted:

1. Its undo payload is written to the configured history boundary as `PREPARED`.
2. A global prepared-data reservation exists.
3. `FoliaBackpressure.acquire` grants a region permit.

A prepared plan waiting for admission is not yet an accepted world mutation.

### 3.2 Dynamic region lanes

The commit broker maintains a mailbox per observed `RegionKey`. The key normally comes from the owner-side GET capture, so a large operation does not need one commit scheduling call per chunk.

Each mailbox has:

- One scheduled-drain flag.
- A per-operation subqueue.
- Deficit-round-robin state.
- Region-local pressure and timing measurements.
- An anchor chunk used for the next scheduler dispatch.

When a drain runs:

1. Obtain the actual current `RegionKey`.
2. Merge mailboxes that now resolve to the same actual region.
3. For every candidate, call `ownsChunk`.
4. Process owned candidates.
5. For an unowned candidate, perform no live access. Release its old per-region permit, retain its global accepted reservation, and redispatch that chunk as a discovery anchor. On its actual owner, acquire the replacement regional permit before mutation.
6. When backlog remains, schedule one successor drain.

A region split therefore causes stale candidates to be rebound. A merge may briefly produce two sequential drain callbacks in the same actual region; the second observes the merged scheduled flag and becomes a no-op. Correctness never depends on the observed region ID.

Cold GETs without a hint use a bounded speculative-discovery window. Owner callbacks absorb all pending spatially indexed requests for chunks they currently own. This may schedule more discovery tasks during a cold sparse workload, but sustained edits reuse owner observations.

### 3.3 Slice granularity

A region drain starts with:

- Target owner time: 750 µs.
- “Do not begin another chunk” threshold: 1.5 ms.
- Initial chunk cap: 8.
- Minimum cap: 1.
- Maximum cap: 32.

The drain checks elapsed time after every complete chunk phase transition. It never splits the synchronous section/tile portion of one chunk merely to meet the timer.

Every 32 slices:

- If backlog exists, schedule delay remains within one tick plus 5 ms, and slice p95 remains below target, the cap grows by two up to 32 and target time grows by 125 µs up to 1.5 ms.
- If schedule delay exceeds 75 ms or a slice exceeds 1.5 ms, the cap and target are halved, with floors of one chunk and 250 µs.
- Interactive priority may cause an earlier yield but may not bypass same-chunk ordering.

These controls use only that region lane’s queue depth, observed scheduling delay, and measured execution time. They do not use global TPS or `MinecraftServer.currentTick`.

At thousands of chunks, scheduler task count therefore scales approximately with:

```text
sum over regions ceil(region-ready-chunks / adaptive-slice-size)
```

rather than one task per chunk. Only one drain is scheduled at a time for each observed region lane.

### 3.4 Interleaving many operations

Each region mailbox applies weighted deficit round-robin:

| Priority | Quantum |
|---|---:|
| Interactive | 4 cost units |
| Normal | 2 |
| Bulk | 1 |

Cost is the larger of one and the plan’s predicted owner microseconds divided by the current median chunk cost. A queue may consume at most four consecutive chunks before the next operation is considered. This preserves locality without allowing one very large edit to monopolize a region.

Rules:

- Different regions run concurrently.
- Disjoint chunks in the same region interleave by the regional scheduler and mailbox policy.
- Same-chunk tickets execute in registration order across all operations.
- A later operation may finish a disjoint chunk before an earlier operation finishes elsewhere. No global ordering is implied.
- Cancellation removes unaccepted plans immediately. Accepted plans already in a live mutation phase finish the consistency-required phases before becoming terminal.
- Finalizer continuations re-enter the same mailbox and participate in the same fairness policy.

## 4. Ordered finalizer state machine

Every accepted chunk follows this order:

```text
SECTIONS
  ↓
TILES
  ↓
ENTITIES
  ↓
POI_AND_NEIGHBOURS
  ↓
LIGHT
  ↓
PACKETS
  ↓
HISTORY_COMMITTED
  ↓
CHUNK_COMPLETE
```

### 4.1 Sections

On the chunk owner:

1. Validate all section indices and expected base version before the first assignment.
2. Install the prepared section replacements.
3. If an assignment unexpectedly fails, restore already replaced sections before returning from the callback where possible.
4. Increment the chunk version and invalidate GET entries as soon as the first mutation becomes effective.
5. Record the applied section bitmap.

Region-thread serialization replaces the Folia-side chunk send lock. The Paper path keeps its current behavior.

### 4.2 Tiles

Tile removals and creations execute on the chunk owner after sections. Every successful tile action records an applied bit. A failure stops the phase, records the exact successful subset, and prevents later optional work from being reported as successful.

### 4.3 Entities

Entity additions execute on the owner of their target location. Existing-entity updates or removals use the entity scheduler.

The chunk region never waits. It dispatches the entity work and yields the plan. When all entity callbacks finish, the plan is submitted back to its chunk owner before advancing to POI.

Entity retirement produces an explicit phase failure. The history receipt contains the exact entity UUIDs successfully added, removed, or changed.

### 4.4 POI and neighbor effects

POI mutation returns to the chunk owner.

Neighbor effects crossing an ownership boundary are decomposed by destination chunk and dispatched to each destination owner. No source-region callback invokes a neighbor method against a differently owned chunk. These destination tasks may run concurrently, but the light phase begins only after all required neighbor tasks settle.

No global physics or update flag is used.

### 4.5 Light

Wave-0 Folia lighting follows L1–L5:

- Commit-path light nibble injection runs on the committed chunk owner.
- GET light reads and lazy `DataLayer` creation occur only during owner-side snapshot capture.
- Full relight uses detached `NMSRelighter` output and materializes it through the normal chunk commit.
- No global scheduler, global ticket mutation, or arbitrary future thread performs lighting work.
- Server Starlight remains a future option requiring its separate cross-region certification.

The synchronized NMSRelighter limits parallelism within one edit session, but independent operations and their owner commits still run across regions.

### 4.6 Packets

Packet construction and viewer enumeration occur on the chunk owner after light.

For each viewer:

- If the viewer is owned by the current context, send inline.
- Otherwise enqueue the immutable packet into a per-player packet mailbox.
- Each mailbox schedules at most one entity task at a time and drains a bounded packet batch in order.
- The chunk packet phase completes when all server-side send enqueues have succeeded, not when a client acknowledges rendering.

This avoids one entity scheduler task per `(chunk, viewer)` while retaining entity ownership. Packet mailbox queues are included in finalizer and global byte limits.

### 4.7 Mandatory callback re-dispatch

Every external future or server callback is treated as having an unspecified execution context.

The callback body may only capture its detached result and invoke:

```java
dispatcher.submit(
        new FaweTaskTarget.ChunkOwner(world, chunkX, chunkZ),
        FoliaRegionDispatcher.TaskKind.FINALIZER,
        () -> coordinator.resumeOnChunkOwner(result)
);
```

`submit` runs inline when the callback already owns the target and schedules otherwise. Ticket removal, live chunk access, packet construction, history finalization, and chunk completion never occur directly on a Moonrise, common-pool, async-chunk-load, or other completion thread.

This rule is mandatory because W0.2 observed relight callbacks on `Paper_Common_Worker_#0` with `owner=false`.

## 5. Backpressure and saturation behavior

### 5.1 Bounded resources

The pipeline independently bounds:

- Snapshot bytes and entries.
- Prepared SET bytes.
- Ready chunks.
- Admission waiters.
- Active finalizer chains.
- Packet mailbox bytes.
- Chunk tickets and outstanding futures.
- Scheduled region drains.
- History data awaiting persistence.

A thousand-chunk operation is streamed through these windows. It does not prepare or schedule every chunk at once.

### 5.2 Ownership-relevant signals

Admission and adaptation use:

- Ready chunks and bytes for the observed region.
- Active finalizers for that region.
- Oldest ready age.
- Region drain schedule delay.
- Region slice runtime.
- Per-region packet mailbox pressure.
- Rebind rate following splits or merges.

Global caps exist solely to bound total process resources. They are not used as a fabricated health measure for individual regions.

### 5.3 Saturation

When a region is saturated:

1. No owner-bound task runs on the submitting thread.
2. The worker pipeline stops producing more plans for that region.
3. A plan already within the bounded waiter queue waits asynchronously.
4. A legacy synchronous A/W caller may await with a bounded deadline and without locks.
5. R/E/G callers that would need to wait are rejected before accepting mutations.
6. If the waiter queue is full or the deadline expires, the plan is rejected before acceptance.
7. The operation stops accepting further plans and completes:
   - as a failure if nothing committed; or
   - as an explicit partial failure if earlier plans committed.
8. The undo record includes only applied receipts from committed or partially committed plans.

A plan rebound after region migration retains a global accepted reservation. It must acquire capacity in the actual region before touching live state. If migration cannot settle before the deadline, that plan terminates without mutation; earlier committed chunks determine whether the operation result is a partial failure.

## 6. Failure, completion, and undo semantics

### 6.1 Chunk outcomes

Every chunk ticket ends in exactly one of:

- `NOT_ACCEPTED`
- `NO_CHANGE`
- `FAILED_BEFORE_MUTATION`
- `COMMITTED`
- `PARTIALLY_COMMITTED`
- `CANCELLED_BEFORE_MUTATION`

A unique `(operationId, chunkKey, ticketSequence, phase, attempt)` identifies callbacks. Duplicate or late callbacks are ignored by an idempotent phase transition and counted diagnostically.

### 6.2 Write-ahead history

Before acceptance, history stores:

```text
PREPARED:
  operation ID
  chunk ticket/version
  before and after section data
  tile/entity/POI/light deltas
  deterministic phase item IDs
```

After live execution it stores:

```text
APPLIED:
  applied section bitmap
  applied tile IDs
  applied entity UUIDs/actions
  applied POI/neighbor IDs
  applied light sections
  packet finalizer result
  terminal failure, if any
```

For persistent history, the `PREPARED` boundary is durable before owner mutation. Operation completion waits for the `APPLIED` record and history close to reach the configured persistence boundary. In-memory history uses the equivalent in-memory publication point.

A restart encountering `PREPARED` without `APPLIED` resolves the record using the stored before/after fingerprints and item identities before exposing the undo entry.

### 6.3 Partial failure

Once section mutation begins, cancellation does not abandon required consistency work. The pipeline attempts the remaining tiles, entities, POI, light, and packet phases. If any phase fails:

- Later dependent phases are not claimed as successful.
- The exact applied subset is persisted.
- The operation reports explicit partial failure.
- The resulting undo entry operates only on that applied subset.
- Whole-operation rollback is not attempted automatically because other regions may already have observed their commits.

### 6.4 Exactly-once terminal completion

An operation coordinator owns one atomic terminal transition:

```text
OPEN → COMPLETING → SUCCEEDED | FAILED | PARTIAL
```

`COMPLETING` is entered only after every accepted chunk reaches a terminal outcome. `SUCCEEDED` additionally requires:

- Every accepted world mutation committed.
- All required finalizers succeeded.
- All packet sends were enqueued.
- The history persistence boundary completed.

Failures are aggregated internally, but the actor or API completion receives one terminal result. Command translation, future completion, and logging share that result rather than independently reporting the same failure.

Legacy boolean and integer edit methods continue to describe staged preparation. They are not commit-success signals. `close`, `flushQueue`, or the returned `Operation` is the terminal result.

### 6.5 Unload and disable

- World or chunk unload before mutation produces `FAILED_BEFORE_MUTATION`.
- Unload after a phase begins records the exact applied receipt and produces partial failure.
- Plugin shutdown stops new admission first.
- Pending unaccepted work is cancelled immediately.
- Already scheduled callbacks either finish or are recorded as incomplete within the bounded shutdown deadline.
- No tick context waits for shutdown drain.
- Shutdown drain duration, unresolved tickets, and history persistence duration are certification measurements.

## 7. `Fawe.isMainThread()` migration

Every occurrence receives an individual review-record row. The logical migration is:

| Current family | Replacement | Rationale |
|---|---|---|
| `QueueHandler.run` and private `sync` branches | Removed from Folia; explicit `FaweTaskTarget` dispatch | There is no global Folia sync drain |
| `TaskManager.taskNowMain`, `taskWhenFree`, `sync*` | `isTickThread` for current-context inline behavior; otherwise explicit/global dispatch | These are caller-context decisions, not chunk ownership |
| `TaskManager.runUnsafe` | Removed predicate; deterministic Folia rejection | Its advertised global behavior has no Folia implementation |
| `SingleThreadQueueExtent.submitUnchecked` | `ownsChunk(world,cx,cz)` | Inline `chunk.call()` is legal only on that chunk owner |
| `NMSAdapter.setSectionAtomic` | `ownsChunk(world,pair.x,pair.z)` | Folia section replacement and packet construction share the owner thread |
| `BukkitWorld.checkLoadedChunk` | `ownsChunk(world,cx,cz)` | Direct loading is owner-specific |
| Adapter-26.1 `getChunkImmediatelyAsync` branches | `ownsChunk(world,cx,cz)` | “Avoid async on main” becomes “direct only on owner” |
| Adapter-26.1 native-access `setBlockState` and `flush` | `ownsChunk(world,cx,cz)` or explicit chunk dispatch | The cached change list may contain several chunks and must be partitioned |
| `LazyBaseEntity` | Carry `EntityOwner` with the lazy supplier; use `ownsEntity` or dispatch | Entity NBT cannot derive ownership from generic tick status |
| `FaweCache.createMainThreadSafeCache` | `isTickThread` | Avoid shared mutable cache behavior on every tick context |
| `SlowExtent.delay` | `isTickThread` | No region/entity/global tick context may sleep |
| `LocalSession.clearHistory` | `isTickThread` | Tick contexts use the non-waiting path |
| `AbstractPlayerActor` clipboard waits | `isTickThread` | No tick context waits for file work |
| `EditSessionBuilder` parallel branch | `!isTickThread()` and legal A/W context | Builder parallel joins are not owner-thread work |
| PlotSquared schematic handoff | `isTickThread` | Move CPU and file work off every tick context |
| `PlatformCommandManager` | Explicit actor/entity context; `isTickThread` only for inline command dispatch | Player callbacks retain their entity ownership |
| `AbstractChangeSet.addWriteTask` | Remove the predicate; always serialize through the history writer | Tick status must not select synchronous history I/O |

Only adapter-26.1 gains Folia ownership predicates. Other adapters retain Paper behavior behind the installed Paper `FaweThreadContext`.

A build-time migration check rejects new production references to `Fawe.isMainThread()` from Folia-enabled source sets.

## 8. Measurement plan and trade-offs

### 8.1 Required measurements

The implementation exposes per-region and global histograms/counters for:

- Snapshot capture time, bytes, profiles, hits, evictions, and wait time.
- Prepared SET bytes and admission wait time.
- Region drain schedule delay.
- Slice runtime and chunks per slice.
- Ready chunks, waiters, scheduled drains, and finalizer chains.
- Region-hint rebinds after ownership changes.
- Commit phase duration by sections, tiles, entities, POI, light, and packets.
- Packet mailbox depth and batching ratio.
- Outstanding chunk tickets and futures.
- Operation throughput and median/p95/p99 completion time.
- Per-region tick-time impact.
- Peak heap, RSS, allocation rate, and GC pause time.
- History persistence and shutdown drain time.

The §8 benchmark must cover:

- Small interactive edits.
- Large contiguous edits.
- Sparse edits over thousands of chunks.
- One operation spanning many regions.
- Many concurrent operations in disjoint regions.
- Many operations contending for the same region.
- Same-chunk contention.
- Lighting-heavy edits.
- Tile/entity-heavy schematic pastes.
- Cancellation, unload, migration, saturation, and shutdown.

Results must separately report:

1. Ported Paper/Spigot versus the unported base revision.
2. Folia versus the ported Paper/Mojang backend on matched hardware and scenarios.

The W0.2 measurements select the initial strategy; they do not satisfy these budgets.

### 8.2 Costs of this throughput-first design

This design intentionally trades some latency and simplicity for sustained multi-region throughput:

- Region sweeps and an initial eight-chunk slice can produce higher per-region tick-time p99 than a one-chunk design.
- Packet mailbox batching may delay a send by part of a tick.
- Large bounded snapshot and prepared-data windows consume more heap and RSS than a minimal streaming design.
- Region hints, rebinding, per-chunk sequencing, deficit round-robin, and phase receipts add implementation complexity.
- Cold sparse edits may schedule more discovery work before useful region hints exist.
- Durable history preparation adds I/O latency before commit acceptance.
- Same-chunk sequencing limits throughput for overlapping edits; allowing concurrent whole-section replacements would risk lost mutations.
- NMSRelighter remains single-threaded within one edit session. A future certified Starlight design could outperform it on lighting-heavy operations.
- The marked Paper seam introduces small dispatch/context overhead; a less ambitious Folia-only fork could score better on the non-Folia regression metric.

A latency-first rival is likely to beat this design on median completion for one- or two-chunk edits and may use less heap. A smaller-slice rival may also produce better per-region tick-time p99. A successfully certified Starlight rival could win lighting throughput and border fidelity.

This proposal should win where the imposed objective matters: aggregate committed chunks per second across many independently ticking regions, scheduler task count at large N, bounded outstanding work, and fair progress across concurrent edits without relaxing ownership, liveness, finalizer, history, or completion semantics.