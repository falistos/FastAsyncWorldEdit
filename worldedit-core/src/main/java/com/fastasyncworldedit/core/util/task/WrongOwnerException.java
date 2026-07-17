package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.world.World;

/**
 * Thrown when a seam requiring ownership is entered from a context that does not own the target
 * chunk. Fail-fast: the caller must re-dispatch to the owning context before touching live state.
 */
public class WrongOwnerException extends RuntimeException {

    private final transient FaweThreadContext context;
    private final transient World world;
    private final int chunkX;
    private final int chunkZ;

    public WrongOwnerException(FaweThreadContext context, World world, int chunkX, int chunkZ) {
        super("Current context does not own chunk (" + chunkX + ", " + chunkZ + ") in "
                + (world == null ? "null" : world.getName()));
        this.context = context;
        this.world = world;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public FaweThreadContext context() {
        return context;
    }

    public World world() {
        return world;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

}
