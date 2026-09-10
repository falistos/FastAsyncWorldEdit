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

package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.entity.Entity;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entity-bound analogue: exact-entity ownership proof for the extent of one entity callback. */
public final class EntityTicket {

    private final TicketAuthority authority;
    private final FaweThreadContext context;
    private final Entity entity;
    private final long sequence;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private volatile Thread mintingThread;

    EntityTicket(
            TicketAuthority authority,
            FaweThreadContext context,
            Entity entity,
            Thread mintingThread,
            long sequence
    ) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.context = Objects.requireNonNull(context, "context");
        this.entity = Objects.requireNonNull(entity, "entity");
        this.mintingThread = Objects.requireNonNull(mintingThread, "mintingThread");
        this.sequence = sequence;
    }

    public Entity entity() {
        return entity;
    }

    public void assertOwns(Entity entity) {
        if (!live.get()
                || Thread.currentThread() != mintingThread
                || this.entity != entity
                || !context.ownsEntity(entity)) {
            throw new IllegalStateException("Current context does not own the ticket's exact entity");
        }
    }  // exact-entity assertion

    public boolean isLive() {
        return live.get();
    }

    public long sequence() {
        return sequence;
    }

    void retire(TicketAuthority retiringAuthority) {
        if (retiringAuthority != authority) {
            throw new IllegalArgumentException("Entity ticket was not minted by this authority");
        }
        if (Thread.currentThread() != mintingThread) {
            throw new IllegalStateException("Entity ticket must be retired on its minting thread");
        }
        if (!live.compareAndSet(true, false)) {
            throw new IllegalStateException("Entity ticket is already retired");
        }
        mintingThread = null;
    }

}
