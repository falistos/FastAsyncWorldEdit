package com.fastasyncworldedit.bukkit.folia;

import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Resolves WorldEdit wrappers to their live Bukkit handles without a main-module linkage. */
final class LiveFoliaTargetAdapter implements FoliaTargetAdapter {

    private static final String BUKKIT_WORLD_CLASS_NAME = "com.sk89q.worldedit.bukkit.BukkitWorld";
    private static final String WORLD_HANDLE_METHOD_NAME = "getWorld";
    private static final String BUKKIT_ENTITY_CLASS_NAME = "com.sk89q.worldedit.bukkit.BukkitEntity";
    private static final String ENTITY_HANDLE_METHOD_NAME = "getEntityHandle";

    private final Class<?> bukkitWorldType;
    private final Method worldHandleMethod;
    private final Class<?> bukkitEntityType;
    private final Method entityHandleMethod;

    LiveFoliaTargetAdapter() {
        try {
            bukkitWorldType = Class.forName(
                    BUKKIT_WORLD_CLASS_NAME,
                    false,
                    LiveFoliaTargetAdapter.class.getClassLoader()
            );
            worldHandleMethod = bukkitWorldType.getMethod(WORLD_HANDLE_METHOD_NAME);
            bukkitEntityType = Class.forName(
                    BUKKIT_ENTITY_CLASS_NAME,
                    false,
                    LiveFoliaTargetAdapter.class.getClassLoader()
            );
            entityHandleMethod = bukkitEntityType.getMethod(ENTITY_HANDLE_METHOD_NAME);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("The Bukkit ownership bridge is unavailable", failure);
        }
    }

    @Override
    public FoliaWorldHandle adapt(World world) {
        World checkedWorld = Objects.requireNonNull(world, "world");
        if (!bukkitWorldType.isInstance(checkedWorld)) {
            throw new IllegalArgumentException("World is not backed by Bukkit: " + checkedWorld.getClass().getName());
        }
        try {
            Object handle = Objects.requireNonNull(
                    worldHandleMethod.invoke(checkedWorld),
                    "The Bukkit world handle is unavailable"
            );
            return FoliaWorldHandle.from(handle);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access the Bukkit world ownership bridge", failure);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("The Bukkit world ownership bridge failed", failure.getCause());
        }
    }

    @Override
    public FoliaEntityHandle adapt(Entity entity) {
        Entity checkedEntity = Objects.requireNonNull(entity, "entity");
        if (!bukkitEntityType.isInstance(checkedEntity)) {
            throw new IllegalArgumentException("Entity is not backed by Bukkit: " + checkedEntity.getClass().getName());
        }
        try {
            return FoliaEntityHandle.from(entityHandleMethod.invoke(checkedEntity));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot access the Bukkit entity ownership bridge", failure);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("The Bukkit entity ownership bridge failed", failure.getCause());
        }
    }

}
