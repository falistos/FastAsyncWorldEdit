package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.world.World;

/**
 * Proof — valid only for the lexical dynamic extent of the single dispatcher callback it was
 * minted for — that the current thread owns the named region and may touch its live state.
 * Minted only by the injected TicketAuthority; retired in the dispatcher's finally block.
 * Never stored in a field, detached plan, future, callback result, or finalizer.
 */
public final class RegionTicket {

    RegionTicket(/* package-private: TicketAuthority only */) {
    }

    public World world() {
        throw new UnsupportedOperationException("wave 1");
    }

    public boolean owns(int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("wave 1");
    }

    /**
     * Fail-fast: throws if this ticket does not own (cx,cz), is used off its minting thread,
     * or has been retired.
     */
    public void assertOwns(int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("wave 1");
    }

    /** True only until the dispatcher retires this ticket (end of its one callback). */
    public boolean isLive() {
        throw new UnsupportedOperationException("wave 1");
    }

    /** Monotonic mint sequence — identifies an individual callback mint for DIAGNOSTICS ONLY.
     *  MUST NOT key terminal completion; the terminal key is the plan sequence (§3.6). */
    public long sequence() {
        throw new UnsupportedOperationException("wave 1");
    }

}
