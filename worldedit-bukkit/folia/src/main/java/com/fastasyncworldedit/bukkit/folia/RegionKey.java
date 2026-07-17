package com.fastasyncworldedit.bukkit.folia;

/**
 * Internal lane-grouping metadata for the commit broker (architecture v3 F2): a RegionKey has no
 * chunk anchor and is never schedulable, so it never appears in a core or public signature.
 * Shape is not frozen; wave 1 fills it in.
 */
public final class RegionKey {

    // wave 1: derived from live Folia ownership at drain time (never region-ID as proof).

}
