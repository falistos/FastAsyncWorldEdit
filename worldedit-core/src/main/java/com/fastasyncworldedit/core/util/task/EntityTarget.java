package com.fastasyncworldedit.core.util.task;

import com.sk89q.worldedit.entity.Entity;

import java.util.Objects;

public record EntityTarget(Entity entity) {

    public EntityTarget {
        Objects.requireNonNull(entity, "entity");
    }

}
