/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.fastasyncworldedit.bukkit.util;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.task.FaweThread;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;

/**
 * Bukkit thread roles, where every owning context is the FAWE main thread.
 */
public final class BukkitThreadContext implements FaweThreadContext {

    @Override
    public boolean isTickThread() {
        return Fawe.isMainThread();
    }

    @Override
    public boolean ownsChunk(final World world, final int chunkX, final int chunkZ) {
        return isTickThread();
    }

    @Override
    public boolean ownsEntity(final Entity entity) {
        return isTickThread();
    }

    @Override
    public boolean isGlobalContext() {
        return isTickThread();
    }

    @Override
    public boolean isFaweWorker() {
        return Thread.currentThread() instanceof FaweThread;
    }

}
