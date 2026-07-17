package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;

/**
 * QueueHandler without the global sync-drain tick (architecture v3 §2). Routes the context-carrying
 * internal surface (§3.5) to the dispatcher. Does NOT touch AsyncCatcher/physicsFreeze (§1b).
 * Skeleton only; wave 1 wires the routing.
 */
public final class FoliaQueueHandler extends QueueHandler {

    @Override
    public void startUnsafe(boolean parallel) {
        throw new UnsupportedOperationException("wave 1");
    }

    @Override
    public void endUnsafe(boolean parallel) {
        throw new UnsupportedOperationException("wave 1");
    }

}
