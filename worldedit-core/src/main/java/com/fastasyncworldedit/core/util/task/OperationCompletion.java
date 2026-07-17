package com.fastasyncworldedit.core.util.task;

import java.util.concurrent.CompletionStage;

/** One coordinator per operation. OPEN → COMPLETING → SUCCEEDED | FAILED | PARTIAL. */
public interface OperationCompletion {

    /** Registration happens exactly once per chunk plan, BEFORE its admission attempt
     *  (and therefore before scheduling). planSequence comes from the same-chunk sequencer. */
    void register(long chunkKey, long planSequence);

    /** No further registration after this; completion becomes reachable. Preserves the
     *  aggregate rejection cause of NOT_ACCEPTED terminals (r2 amendment 2). */
    void closeAdmission();

    /** Idempotent per (operationId, chunkKey, planSequence); duplicates never decrement. */
    void terminal(ChunkTerminalRecord record);

    CompletionStage<OperationResult> future();   // completes exactly once

}
