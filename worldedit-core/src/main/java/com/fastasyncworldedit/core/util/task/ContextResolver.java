package com.fastasyncworldedit.core.util.task;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single core resolver for the active {@link FaweThreadContext}. The platform backend registers
 * exactly one implementation at bootstrap; re-registration fails.
 */
public final class ContextResolver {

    private static final AtomicReference<FaweThreadContext> CONTEXT = new AtomicReference<>();

    private ContextResolver() {
    }

    /** Accepts exactly one platform registration at bootstrap; re-registration fails. */
    public static void register(FaweThreadContext context) {
        FaweThreadContext registeredContext = Objects.requireNonNull(context, "context");
        if (!CONTEXT.compareAndSet(null, registeredContext)) {
            throw new IllegalStateException("A FaweThreadContext is already registered");
        }
    }

    static FaweThreadContext resolve() {
        FaweThreadContext context = CONTEXT.get();
        if (context == null) {
            throw new IllegalStateException("No FaweThreadContext has been registered");
        }
        return context;
    }

}
