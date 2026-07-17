package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.TaskManager;

/**
 * TaskManager implementation on Folia schedulers (architecture v3 §2). Skeleton only; wave 1 wires
 * the RegionScheduler / GlobalRegionScheduler / AsyncScheduler backing.
 */
public final class FoliaTaskManager extends TaskManager {

    @Override
    public int repeat(Runnable runnable, int interval) {
        throw new UnsupportedOperationException("wave 1");
    }

    @Override
    public int repeatAsync(Runnable runnable, int interval) {
        throw new UnsupportedOperationException("wave 1");
    }

    @Override
    public void async(Runnable runnable) {
        throw new UnsupportedOperationException("wave 1");
    }

    @Override
    public void task(Runnable runnable) {
        throw new UnsupportedOperationException("wave 1");
    }

    @Override
    public void later(Runnable runnable, int delay) {
        throw new UnsupportedOperationException("wave 1");
    }

    @Override
    public void laterAsync(Runnable runnable, int delay) {
        throw new UnsupportedOperationException("wave 1");
    }

    @Override
    public void cancel(int task) {
        throw new UnsupportedOperationException("wave 1");
    }

}
