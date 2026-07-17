package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.world.World;

import java.util.Objects;

public record ChunkTarget(World world, int chunkX, int chunkZ) {

    public ChunkTarget {
        Objects.requireNonNull(world, "world");
    }

}
