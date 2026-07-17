package com.fastasyncworldedit.bukkit.folia;

import java.util.Optional;

/**
 * Per-region lane/mailbox set, sliced-sweep drain, DRR interleaving, rebind + permit transfer on
 * split/merge (architecture v3 §3.7). {@link RegionKey} lives here as internal lane metadata only
 * (F2). Behavior is not frozen; wave 1 implements it.
 */
public final class FoliaCommitBroker {

    private final FoliaRegionDispatcher dispatcher;
    private final FoliaBackpressure backpressure;

    public FoliaCommitBroker(FoliaRegionDispatcher dispatcher, FoliaBackpressure backpressure) {
        this.dispatcher = dispatcher;
        this.backpressure = backpressure;
    }

    /** On FoliaCommitBroker (frozen). */
    DiagnosticSnapshot diagnostics(RegionKey region) {
        throw new UnsupportedOperationException("wave 1");
    }

    /** On FoliaCommitBroker (frozen). */
    DiagnosticSnapshot diagnosticsGlobal() {
        throw new UnsupportedOperationException("wave 1");
    }

}

/** Per-region or global (region == null) diagnostic snapshot. Frozen G4 producer. */
record DiagnosticSnapshot(
        Optional<RegionKey> region,              // empty = global aggregate
        int readyChunks,
        long readyBytes,
        int waiters,
        int finalizerChains,
        long packetMailboxBytes,
        int scheduledDrains,
        int liveTickets,                         // dispatcher mint/retire counter delta
        int outstandingFutures,                  // dispatcher stages not yet settled
        int outstandingChunks,                   // registered, non-terminal plans (§3.6)
        long rebindCount,                        // lane rebinds after split/merge
        long oldestReadyNanos
) {
}
