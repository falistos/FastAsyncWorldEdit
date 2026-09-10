package com.fastasyncworldedit.core.util.task;

/**
 * Outcome of the packet phase for a committed chunk.
 */
public record PacketPhaseResult(int requiredSends, int enqueuedSends) {

    public PacketPhaseResult {
        if (requiredSends < 0) {
            throw new IllegalArgumentException("requiredSends must be non-negative");
        }
        if (enqueuedSends < 0 || enqueuedSends > requiredSends) {
            throw new IllegalArgumentException("enqueuedSends must be between zero and requiredSends");
        }
    }

    public static PacketPhaseResult noPackets() {
        return new PacketPhaseResult(0, 0);
    }

    public boolean allEnqueued() {
        return requiredSends == enqueuedSends;
    }

}
