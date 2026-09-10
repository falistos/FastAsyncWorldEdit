package com.fastasyncworldedit.core.util.task;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Terminal record, keyed. Duplicates by key are ignored (counted diagnostically). */
public record ChunkTerminalRecord(
        UUID operationId,
        long chunkKey,
        long planSequence,                       // same-chunk sequencer allocation, invariant
                                                 // across callbacks/rebinds/recapture (r2 am. 1)
        TerminalStatus status,
        AppliedReceipt applied,                  // empty receipt for pre-mutation statuses
        Optional<Throwable> failure
) {

    public ChunkTerminalRecord {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(failure, "failure");
    }

}
