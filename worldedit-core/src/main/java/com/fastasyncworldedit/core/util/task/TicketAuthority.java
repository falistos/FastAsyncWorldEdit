package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;

/**
 * The sole minting path for {@link RegionTicket} / {@link EntityTicket} capabilities.
 * A same-package final class with a non-public constructor whose sole instance is issued exactly
 * once at backend bootstrap and injected into the platform dispatcher. No public accessor exposes
 * it; a second issuance attempt throws. Platform modules neither implement nor construct tickets.
 */
public final class TicketAuthority {

    TicketAuthority(/* non-public: single issuance at backend bootstrap */) {
    }

    /** Mint a region capability for the extent of one dispatcher callback. */
    public RegionTicket mintRegion(World world, int chunkX, int chunkZ) {
        throw new UnsupportedOperationException("wave 1");
    }

    /** Mint an entity capability for the extent of one entity callback. */
    public EntityTicket mintEntity(Entity entity) {
        throw new UnsupportedOperationException("wave 1");
    }

    /** Retire a capability at the end of its single callback (dispatcher finally block). */
    public void retire(RegionTicket ticket) {
        throw new UnsupportedOperationException("wave 1");
    }

    /** Retire an entity capability at the end of its single callback. */
    public void retire(EntityTicket ticket) {
        throw new UnsupportedOperationException("wave 1");
    }

}
