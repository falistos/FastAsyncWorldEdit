package com.fastasyncworldedit.bukkit.folia;

/**
 * Versioned, leased, bounded GET snapshot cache (architecture v3 §3.7). No live reference and no
 * ticket in any snapshot (C2/F3). Behavior is not frozen; wave 1 implements it.
 */
public final class FoliaSnapshotCache {

    private final FoliaRegionDispatcher dispatcher;

    public FoliaSnapshotCache(FoliaRegionDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

}
