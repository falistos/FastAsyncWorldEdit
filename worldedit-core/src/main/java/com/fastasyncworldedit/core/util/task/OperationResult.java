package com.fastasyncworldedit.core.util.task;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The single terminal result an operation's actor/API receives.
 */
public record OperationResult(
        UUID operationId,
        Classification classification,
        List<ChunkTerminalRecord> terminalRecords,
        List<Throwable> failures
) {

    public OperationResult {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(classification, "classification");
        terminalRecords = List.copyOf(terminalRecords);
        failures = List.copyOf(failures);
        terminalRecords.forEach(record -> Objects.requireNonNull(record.applied(), "terminalRecord.applied"));
        if (terminalRecords.stream().anyMatch(record -> !operationId.equals(record.operationId()))) {
            throw new IllegalArgumentException("All terminal records must belong to the operation");
        }
    }

    public enum Classification {
        SUCCEEDED,
        FAILED,
        PARTIAL
    }

    /** Operation-scoped history is unusable if any required chunk settlement is unavailable. */
    public boolean historyUsable() {
        return unavailableHistoryChunks().isEmpty();
    }

    public List<Long> unavailableHistoryChunks() {
        return terminalRecords.stream()
                .filter(record -> record.applied().historySettlement() == HistorySettlement.UNAVAILABLE)
                .map(ChunkTerminalRecord::chunkKey)
                .toList();
    }

    public String actorMessage() {
        List<Long> unavailable = unavailableHistoryChunks();
        if (unavailable.isEmpty()) {
            return "";
        }
        return "Chunks " + unavailable + " committed, but their undo/history could not be persisted.";
    }

}
