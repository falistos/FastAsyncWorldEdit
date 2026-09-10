package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.FaweThreadContext;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Cross-checks the registered context before a Folia path may wait or derive a scheduler. */
final class FoliaTickThreadGuard {

    private final BooleanSupplier tickThread;

    private FoliaTickThreadGuard(BooleanSupplier tickThread) {
        this.tickThread = Objects.requireNonNull(tickThread, "tickThread");
    }

    static FoliaTickThreadGuard production() {
        // Resolve eagerly: constructing a scheduler service before task 17 registers the Folia
        // context is a bootstrap-order defect and must fail closed.
        FaweThreadContext context = FaweThreadContext.current();
        return new FoliaTickThreadGuard(context::isTickThread);
    }

    static FoliaTickThreadGuard testing(BooleanSupplier tickThread) {
        BooleanSupplier checked = Objects.requireNonNull(tickThread, "tickThread");
        return new FoliaTickThreadGuard(checked);
    }

    static FoliaTickThreadGuard testing(
            BooleanSupplier contextTickThread,
            BooleanSupplier platformTickThread
    ) {
        BooleanSupplier checkedContext = Objects.requireNonNull(contextTickThread, "contextTickThread");
        BooleanSupplier checkedPlatform = Objects.requireNonNull(platformTickThread, "platformTickThread");
        return new FoliaTickThreadGuard(() -> {
            boolean contextResult = checkedContext.getAsBoolean();
            boolean platformResult = checkedPlatform.getAsBoolean();
            if (contextResult != platformResult) {
                throw new IllegalStateException("Test thread predicates disagree");
            }
            return contextResult;
        });
    }

    boolean isTickThread() {
        return tickThread.getAsBoolean();
    }

}
