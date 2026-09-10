package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.EntityTarget;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.sk89q.worldedit.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;

/** Reads the current Folia region identity as lane metadata, never as ownership proof. */
final class LiveRegionObserver implements FoliaCommitBroker.RegionObserver {

    private static final String TICK_REGION_SCHEDULER_CLASS_NAME =
            "io.papermc.paper.threadedregions.TickRegionScheduler";
    private static final String CURRENT_REGION_METHOD_NAME = "getCurrentRegion";
    private static final String REGION_ID_FIELD_NAME = "id";

    private final FaweThreadContext context;
    private final FoliaTargetAdapter targetAdapter;
    private final Method currentRegionMethod;
    private final Field regionIdField;

    LiveRegionObserver(FaweThreadContext context, FoliaTargetAdapter targetAdapter) {
        this.context = Objects.requireNonNull(context, "context");
        this.targetAdapter = Objects.requireNonNull(targetAdapter, "targetAdapter");
        try {
            Class<?> schedulerType = Class.forName(
                    TICK_REGION_SCHEDULER_CLASS_NAME,
                    false,
                    LiveRegionObserver.class.getClassLoader()
            );
            currentRegionMethod = schedulerType.getMethod(CURRENT_REGION_METHOD_NAME);
            regionIdField = currentRegionMethod.getReturnType().getField(REGION_ID_FIELD_NAME);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The certified Folia region-identity bridge is unavailable", failure);
        }
    }

    @Override
    public Optional<RegionKey> lastRegionHint(World world, int chunkX, int chunkZ) {
        return Optional.empty();
    }

    @Override
    public RegionKey currentRegion(World world) {
        return new RegionKey(targetAdapter.adapt(world).world().getUID(), currentRegionId());
    }

    @Override
    public RegionKey currentRegion(EntityTarget target) {
        FoliaEntityHandle handle = targetAdapter.adapt(target.entity());
        return new RegionKey(handle.entity().getWorld().getUID(), currentRegionId());
    }

    @Override
    public boolean ownsChunk(World world, int chunkX, int chunkZ) {
        return context.ownsChunk(world, chunkX, chunkZ);
    }

    @Override
    public boolean ownsEntity(EntityTarget target) {
        return context.ownsEntity(target.entity());
    }

    private long currentRegionId() {
        try {
            Object region = currentRegionMethod.invoke(null);
            if (region == null) {
                throw new IllegalStateException("No live Folia region is bound to the current thread");
            }
            return regionIdField.getLong(region);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read the current Folia region identity", failure);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Folia failed to resolve the current region", failure.getCause());
        }
    }

}
