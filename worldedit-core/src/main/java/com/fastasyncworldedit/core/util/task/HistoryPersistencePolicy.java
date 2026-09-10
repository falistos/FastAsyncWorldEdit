package com.fastasyncworldedit.core.util.task;

import java.time.Duration;
import java.util.Objects;

/**
 * Finite, config-backed bounds for APPLIED history persistence settlement.
 *
 * @param maxAttempts total attempts derived from the bounded {@code HISTORY_PERSIST_RETRIES} policy
 * @param attemptTimeout finite timeout for each attempt
 * @param retryBackoff finite delay between attempts
 * @param settlementTimeout finite deadline for the complete settlement sequence
 */
public record HistoryPersistencePolicy(
        int maxAttempts,
        Duration attemptTimeout,
        Duration retryBackoff,
        Duration settlementTimeout
) {

    public HistoryPersistencePolicy {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        requirePositive(attemptTimeout, "attemptTimeout");
        Objects.requireNonNull(retryBackoff, "retryBackoff");
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must be non-negative");
        }
        requirePositive(settlementTimeout, "settlementTimeout");
        attemptTimeout.toNanos();
        retryBackoff.toNanos();
        settlementTimeout.toNanos();
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

}
