package com.fastasyncworldedit.bukkit.folia;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal lane-grouping metadata for the commit broker (architecture v3 F2). The observed region
 * identity is a grouping and accounting hint, never proof that the current thread owns a chunk.
 * Ownership is re-derived before every drain and mutation.
 */
public final class RegionKey {

    private static final AtomicLong SYNTHETIC_REGION_SEQUENCE = new AtomicLong(Long.MIN_VALUE);
    private static final UUID SYNTHETIC_WORLD_ID = new UUID(0, 0);

    private final UUID worldId;
    private final long observedRegionId;

    /**
     * Creates a value key re-derived from the live region currently owning part of a world.
     */
    public RegionKey(UUID worldId, long observedRegionId) {
        this.worldId = Objects.requireNonNull(worldId, "worldId");
        this.observedRegionId = observedRegionId;
    }

    /**
     * Test-support identity retained for task-13 fixtures written before the value shape landed.
     * Each invocation denotes a different synthetic region and must not be used by production wiring.
     */
    RegionKey() {
        this(SYNTHETIC_WORLD_ID, nextSyntheticRegionId());
    }

    public UUID worldId() {
        return worldId;
    }

    public long observedRegionId() {
        return observedRegionId;
    }

    @Override
    public boolean equals(Object object) {
        return this == object || object instanceof RegionKey other
                && observedRegionId == other.observedRegionId
                && worldId.equals(other.worldId);
    }

    @Override
    public int hashCode() {
        return 31 * worldId.hashCode() + Long.hashCode(observedRegionId);
    }

    @Override
    public String toString() {
        return worldId + ":" + observedRegionId;
    }

    private static long nextSyntheticRegionId() {
        long identity = SYNTHETIC_REGION_SEQUENCE.getAndIncrement();
        if (identity >= 0) {
            throw new IllegalStateException("Synthetic RegionKey identity space exhausted");
        }
        return identity;
    }

}
