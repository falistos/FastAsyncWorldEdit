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

package com.fastasyncworldedit.bukkit.folia;

import org.bukkit.entity.Entity;

import java.util.Objects;

/** Bukkit entity handle supplied by the bootstrap adapter. */
record FoliaEntityHandle(Entity entity) {

    FoliaEntityHandle {
        Objects.requireNonNull(entity, "entity");
    }

    static FoliaEntityHandle from(Object handle) {
        if (!(handle instanceof Entity entity)) {
            throw new IllegalStateException("Bukkit entity was retired before ownership resolution");
        }
        return new FoliaEntityHandle(entity);
    }

}
