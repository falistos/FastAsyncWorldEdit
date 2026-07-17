package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.entity.Entity;

/** Entity-bound analogue: exact-entity ownership proof for the extent of one entity callback. */
public final class EntityTicket {

    EntityTicket(/* package-private: TicketAuthority only */) {
    }

    public Entity entity() {
        throw new UnsupportedOperationException("wave 1");
    }

    public void assertOwns(Entity entity) {
        throw new UnsupportedOperationException("wave 1");
    }  // exact-entity assertion

    public boolean isLive() {
        throw new UnsupportedOperationException("wave 1");
    }

    public long sequence() {
        throw new UnsupportedOperationException("wave 1");
    }

}
