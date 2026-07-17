package com.fastasyncworldedit.bukkit.folia;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Per-region bounded admission with the full Demand/stage model (architecture v3 §3.4).
 * Folia-module-internal; {@link RegionKey} here is internal lane metadata, legal per F2.
 */
public interface FoliaBackpressure {

    enum Priority {
        INTERACTIVE,
        NORMAL,
        BULK
    }

    enum Stage {
        READY,
        SCHEDULED,
        COMMITTING,
        FINALIZING
    }

    record Demand(
            UUID operationId,
            int chunks,
            long preparedBytes,
            int finalizerChains,
            Priority priority,
            boolean previouslyAccepted,
            long deadlineNanos
    ) {

        public Demand {
            if (chunks <= 0) {
                throw new IllegalArgumentException("chunks must be positive");
            }
            if (preparedBytes < 0 || finalizerChains < 0) {
                throw new IllegalArgumentException("negative demand");
            }
        }
    }

    record Limits(
            int maxReadyChunksPerRegion,
            long maxReadyBytesPerRegion,
            int maxFinalizersPerRegion,
            int maxWaitersPerRegion,
            int maxGlobalReadyChunks,
            long maxGlobalReadyBytes,
            int maxGlobalFinalizers
    ) {
    }

    record Pressure(
            int readyChunks,
            long readyBytes,
            int finalizerChains,
            int waiters,
            int scheduledDrains,
            long oldestReadyNanos,
            long scheduleDelayEwmaNanos,
            long sliceRuntimeEwmaNanos
    ) {
    }

    interface Permit extends AutoCloseable {

        RegionKey region();

        Demand demand();

        Stage stage();

        /**
         * Stages may advance only in the declared order.
         */
        void enter(Stage next);

        /**
         * Idempotent. Releases all remaining accounting.
         */
        @Override
        void close();
    }

    Limits limits();

    /**
     * Completes on a FAWE executor. The cancellation signal completes when
     * the operation is cancelled. Never called from a tick thread.
     */
    CompletionStage<Permit> acquire(
            RegionKey region,
            Demand demand,
            CompletionStage<?> cancellationSignal
    );

    /**
     * F4: owner-thread admission. Immediate and nonblocking — returns empty instead of
     * ever waiting or returning a future the caller might await. The ONLY admission
     * entry point legal from a tick thread (G-A1 uses it exclusively).
     */
    Optional<Permit> tryAcquire(RegionKey region, Demand demand);

    /**
     * F4: continuation capacity. A continuation required to release existing accepted work
     * (entity-phase resume, light materialization, neighbor settlement) draws from a reserved
     * per-region continuation budget and never queues behind the commits it unblocks.
     */
    CompletionStage<Permit> acquireContinuation(
            RegionKey region,
            Demand demand,
            CompletionStage<?> cancellationSignal
    );

    /**
     * F4: region merge/split transfer. Before any mutation under a permit whose observed
     * region is stale, the permit's accounting is transferred to the actual current region.
     * Returns false when the target region cannot admit it now — the plan defers or fails
     * before mutation; it never mutates under stale accounting.
     */
    boolean transfer(Permit permit, RegionKey actualRegion);

    Pressure pressure(RegionKey region);

    void recordScheduleDelay(
            RegionKey region,
            long delayNanos
    );

    void recordSlice(
            RegionKey region,
            int chunks,
            long runtimeNanos
    );

    void stopAccepting(Throwable reason);

}
