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

import com.sk89q.worldedit.world.World;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Proof — valid only for the lexical dynamic extent of the single dispatcher callback it was
 * minted for — that the current thread owns the named region and may touch its live state.
 * Minted only by the injected TicketAuthority; retired in the dispatcher's finally block.
 * Never stored in a field, detached plan, future, callback result, or finalizer.
 */
public final class RegionTicket {

    private final TicketAuthority authority;
    private final FaweThreadContext context;
    private final World world;
    private final long sequence;
    private final AtomicBoolean live = new AtomicBoolean(true);
    private volatile Thread mintingThread;

    RegionTicket(
            TicketAuthority authority,
            FaweThreadContext context,
            World world,
            Thread mintingThread,
            long sequence
    ) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.context = Objects.requireNonNull(context, "context");
        this.world = Objects.requireNonNull(world, "world");
        this.mintingThread = Objects.requireNonNull(mintingThread, "mintingThread");
        this.sequence = sequence;
    }

    public World world() {
        return world;
    }

    public boolean owns(int chunkX, int chunkZ) {
        return live.get()
                && Thread.currentThread() == mintingThread
                && context.ownsChunk(world, chunkX, chunkZ);
    }

    /**
     * Fail-fast: throws if this ticket does not own (cx,cz), is used off its minting thread,
     * or has been retired.
     */
    public void assertOwns(int chunkX, int chunkZ) {
        if (!owns(chunkX, chunkZ)) {
            throw new WrongOwnerException(context, world, chunkX, chunkZ);
        }
    }

    /** True only until the dispatcher retires this ticket (end of its one callback). */
    public boolean isLive() {
        return live.get();
    }

    /** Monotonic mint sequence — identifies an individual callback mint for DIAGNOSTICS ONLY.
     *  MUST NOT key terminal completion; the terminal key is the plan sequence (§3.6). */
    public long sequence() {
        return sequence;
    }

    void retire(TicketAuthority retiringAuthority) {
        if (retiringAuthority != authority) {
            throw new IllegalArgumentException("Region ticket was not minted by this authority");
        }
        if (Thread.currentThread() != mintingThread) {
            throw new IllegalStateException("Region ticket must be retired on its minting thread");
        }
        if (!live.compareAndSet(true, false)) {
            throw new IllegalStateException("Region ticket is already retired");
        }
        mintingThread = null;
    }

}
