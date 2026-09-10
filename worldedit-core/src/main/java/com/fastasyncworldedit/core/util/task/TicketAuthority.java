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
import com.sk89q.worldedit.world.World;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The sole minting path for {@link RegionTicket} / {@link EntityTicket} capabilities.
 * A same-package final class with a non-public constructor whose sole instance is issued exactly
 * once at backend bootstrap and injected into the platform dispatcher. No public accessor exposes
 * it; a second issuance attempt throws. Platform modules neither implement nor construct tickets.
 */
public final class TicketAuthority {

    private static final AtomicBoolean ISSUED = new AtomicBoolean();

    private final AtomicLong mintSequence = new AtomicLong();

    TicketAuthority() {
        if (!ISSUED.compareAndSet(false, true)) {
            throw new IllegalStateException("A TicketAuthority has already been issued");
        }
    }

    /** Issues the sole authority for injection into the active backend dispatcher. */
    public static TicketAuthority issue() {
        return new TicketAuthority();
    }

    /** Mint a region capability for the extent of one dispatcher callback. */
    public RegionTicket mintRegion(World world, int chunkX, int chunkZ) {
        World checkedWorld = Objects.requireNonNull(world, "world");
        FaweThreadContext context = FaweThreadContext.current();
        context.requireOwns(checkedWorld, chunkX, chunkZ);
        return new RegionTicket(
                this,
                context,
                checkedWorld,
                Thread.currentThread(),
                nextSequence()
        );
    }

    /** Mint an entity capability for the extent of one entity callback. */
    public EntityTicket mintEntity(Entity entity) {
        Entity checkedEntity = Objects.requireNonNull(entity, "entity");
        FaweThreadContext context = FaweThreadContext.current();
        if (!context.ownsEntity(checkedEntity)) {
            throw new IllegalStateException("Current context does not own the entity");
        }
        return new EntityTicket(
                this,
                context,
                checkedEntity,
                Thread.currentThread(),
                nextSequence()
        );
    }

    /** Retire a capability at the end of its single callback (dispatcher finally block). */
    public void retire(RegionTicket ticket) {
        Objects.requireNonNull(ticket, "ticket").retire(this);
    }

    /** Retire an entity capability at the end of its single callback. */
    public void retire(EntityTicket ticket) {
        Objects.requireNonNull(ticket, "ticket").retire(this);
    }

    private long nextSequence() {
        long sequence = mintSequence.incrementAndGet();
        if (sequence <= 0) {
            throw new IllegalStateException("Ticket mint sequence exhausted");
        }
        return sequence;
    }

}
