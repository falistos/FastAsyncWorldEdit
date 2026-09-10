package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;

/**
 * Thread-role classification. Bukkit backend collapses tick/owner to the main thread;
 * Folia backend answers per region/entity/global scheduler ownership.
 * Pure predicate object — never blocks, never hops, never touches live state.
 * Implemented by the active platform backend; resolved through a single core resolver.
 */
public interface FaweThreadContext {

    static FaweThreadContext current() {
        return ContextResolver.resolve();
    } // single resolver

    boolean isTickThread();                         // any server tick thread (region/entity/global)

    boolean ownsChunk(World world, int chunkX, int chunkZ);

    boolean ownsEntity(Entity entity);

    boolean isGlobalContext();                       // Folia global-region thread; Paper main

    boolean isFaweWorker();                          // an extent-carrying FAWE prepare-pool thread
                                                     // (FaweThread-marked); NOT every FAWE-owned
                                                     // thread (UUID-queue/TaskManager pools excluded)

    /** Fail-fast guard used at every seam that requires ownership. */
    default void requireOwns(World world, int cx, int cz) {
        if (!ownsChunk(world, cx, cz)) {
            throw new WrongOwnerException(this, world, cx, cz);
        }
    }

}
