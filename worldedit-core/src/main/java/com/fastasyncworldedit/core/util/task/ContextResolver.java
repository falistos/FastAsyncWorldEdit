package com.fastasyncworldedit.core.util.task;

/**
 * Single core resolver for the active {@link FaweThreadContext}. The platform backend registers
 * exactly one implementation at bootstrap; re-registration fails.
 */
public final class ContextResolver {

    private ContextResolver() {
    }

    /** Accepts exactly one platform registration at bootstrap; re-registration fails. */
    public static void register(FaweThreadContext context) {
        throw new UnsupportedOperationException("wave 1");
    }

    static FaweThreadContext resolve() {
        throw new UnsupportedOperationException("wave 1");
    }

}
