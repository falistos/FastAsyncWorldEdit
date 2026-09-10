package com.fastasyncworldedit.core.util.task;

import java.util.Objects;
import java.util.UUID;

/**
 * An entity mutation actually performed during a commit.
 */
public record EntityAction(UUID entityId, Type type) {

    public EntityAction {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(type, "type");
    }

    public enum Type {
        ADDED,
        REMOVED,
        CHANGED
    }

}
