package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.FaweThread;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;

import java.util.Objects;

/** Live Folia thread roles and ownership predicates. */
public final class FoliaThreadContext implements FaweThreadContext {

    private final OwnershipQueries ownership;

    FoliaThreadContext(FoliaTargetAdapter targetAdapter) {
        this(new LiveOwnershipQueries(targetAdapter, LiveOwnershipPredicates.INSTANCE));
    }

    FoliaThreadContext(OwnershipQueries ownership) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    @Override
    public boolean isTickThread() {
        return ownership.isTickThread();
    }

    @Override
    public boolean ownsChunk(World world, int chunkX, int chunkZ) {
        return ownership.ownsChunk(Objects.requireNonNull(world, "world"), chunkX, chunkZ);
    }

    @Override
    public boolean ownsEntity(Entity entity) {
        return ownership.ownsEntity(Objects.requireNonNull(entity, "entity"));
    }

    @Override
    public boolean isGlobalContext() {
        return ownership.isGlobalContext();
    }

    @Override
    public boolean isFaweWorker() {
        return Thread.currentThread() instanceof FaweThread faweThread && faweThread.getCurrentExtent() != null;
    }

    interface OwnershipQueries {

        boolean isTickThread();

        boolean ownsChunk(World world, int chunkX, int chunkZ);

        boolean ownsEntity(Entity entity);

        boolean isGlobalContext();

    }

    record LiveOwnershipQueries(
            FoliaTargetAdapter targetAdapter,
            OwnershipPredicates predicates
    ) implements OwnershipQueries {

        LiveOwnershipQueries {
            Objects.requireNonNull(targetAdapter, "targetAdapter");
            Objects.requireNonNull(predicates, "predicates");
        }

        @Override
        public boolean isTickThread() {
            return predicates.isTickThread();
        }

        @Override
        public boolean ownsChunk(World world, int chunkX, int chunkZ) {
            return predicates.ownsChunk(targetAdapter.adapt(world), chunkX, chunkZ);
        }

        @Override
        public boolean ownsEntity(Entity entity) {
            return predicates.ownsEntity(targetAdapter.adapt(entity));
        }

        @Override
        public boolean isGlobalContext() {
            return predicates.isGlobalContext();
        }

    }

    interface OwnershipPredicates {

        boolean isTickThread();

        boolean ownsChunk(FoliaWorldHandle world, int chunkX, int chunkZ);

        boolean ownsEntity(FoliaEntityHandle entity);

        boolean isGlobalContext();

    }

    private enum LiveOwnershipPredicates implements OwnershipPredicates {
        INSTANCE;

        @Override
        public boolean isTickThread() {
            return Bukkit.isPrimaryThread();
        }

        @Override
        public boolean ownsChunk(FoliaWorldHandle world, int chunkX, int chunkZ) {
            return Bukkit.isOwnedByCurrentRegion(world.world(), chunkX, chunkZ);
        }

        @Override
        public boolean ownsEntity(FoliaEntityHandle entity) {
            return Bukkit.isOwnedByCurrentRegion(entity.entity());
        }

        @Override
        public boolean isGlobalContext() {
            return Bukkit.isGlobalTickThread();
        }

    }

}
