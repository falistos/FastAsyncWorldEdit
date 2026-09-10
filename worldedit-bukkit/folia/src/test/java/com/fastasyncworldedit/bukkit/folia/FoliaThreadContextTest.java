package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.FaweBasicThreadFactory;
import com.fastasyncworldedit.core.util.task.FaweThread;
import com.fastasyncworldedit.core.util.task.FaweThreadUtil;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaThreadContextTest {

    @Test
    void ownershipUsesTheLiveQueryAndNeverASameRegionHint() {
        FakeOwnershipPredicates scheduler = new FakeOwnershipPredicates();
        World world = proxy(World.class);
        Entity entity = proxy(Entity.class);
        FoliaTargetAdapter adapter = new FoliaTargetAdapter() {
            @Override
            public FoliaWorldHandle adapt(World ignored) {
                return FoliaWorldHandle.from(proxy("org.bukkit.World"));
            }

            @Override
            public FoliaEntityHandle adapt(Entity ignored) {
                return FoliaEntityHandle.from(proxy("org.bukkit.entity.Entity"));
            }
        };
        FoliaThreadContext context = new FoliaThreadContext(
                new FoliaThreadContext.LiveOwnershipQueries(adapter, scheduler)
        );

        scheduler.sameRegionHint = true;
        scheduler.ownsChunk = false;
        scheduler.ownsEntity = false;
        assertFalse(context.ownsChunk(world, 7, 11));
        assertFalse(context.ownsEntity(entity));
        assertEquals(0, scheduler.sameRegionHintReads);

        scheduler.sameRegionHint = false;
        scheduler.ownsChunk = true;
        scheduler.ownsEntity = true;
        assertTrue(context.ownsChunk(world, 7, 11));
        assertTrue(context.ownsEntity(entity));
        assertEquals(0, scheduler.sameRegionHintReads);
    }

    @Test
    void tickAndGlobalRolesDelegateToThePlatformPredicates() {
        FakeOwnershipQueries scheduler = new FakeOwnershipQueries();
        FoliaThreadContext context = new FoliaThreadContext(scheduler);

        scheduler.tickThread = true;
        scheduler.globalContext = false;
        assertTrue(context.isTickThread());
        assertFalse(context.isGlobalContext());

        scheduler.tickThread = false;
        scheduler.globalContext = true;
        assertFalse(context.isTickThread());
        assertTrue(context.isGlobalContext());
    }

    @Test
    void workerRoleRequiresTheExtentCarryingThreadMarker() throws Exception {
        FoliaThreadContext context = new FoliaThreadContext(new FakeOwnershipQueries());
        AtomicBoolean activeResult = new AtomicBoolean();
        Thread active = new FaweBasicThreadFactory("Task 17 active worker").newThread(
                () -> activeResult.set(context.isFaweWorker())
        );
        ((FaweThread) active).setCurrentExtent(proxy(Extent.class));
        AtomicBoolean idleResult = new AtomicBoolean(true);
        Thread idle = new FaweBasicThreadFactory("Task 17 idle worker").newThread(
                () -> idleResult.set(context.isFaweWorker())
        );
        AtomicBoolean plainResult = new AtomicBoolean(true);
        Thread plain = new Thread(() -> {
            FaweThreadUtil.setCurrentExtent(proxy(Extent.class));
            try {
                plainResult.set(context.isFaweWorker());
            } finally {
                FaweThreadUtil.clearCurrentExtent();
            }
        }, "Task 17 plain worker");

        active.start();
        idle.start();
        plain.start();
        active.join();
        idle.join();
        plain.join();

        assertTrue(activeResult.get());
        assertFalse(idleResult.get());
        assertFalse(plainResult.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> null);
    }

    private static Object proxy(String className) {
        try {
            Class<?> type = Class.forName(className);
            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (instance, method, args) -> null);
        } catch (ClassNotFoundException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class FakeOwnershipQueries implements FoliaThreadContext.OwnershipQueries {

        private boolean tickThread;
        private boolean ownsChunk;
        private boolean ownsEntity;
        private boolean globalContext;

        @Override
        public boolean isTickThread() {
            return tickThread;
        }

        @Override
        public boolean ownsChunk(World world, int chunkX, int chunkZ) {
            return ownsChunk;
        }

        @Override
        public boolean ownsEntity(Entity entity) {
            return ownsEntity;
        }

        @Override
        public boolean isGlobalContext() {
            return globalContext;
        }

    }

    private static final class FakeOwnershipPredicates implements FoliaThreadContext.OwnershipPredicates {

        private boolean tickThread;
        private boolean ownsChunk;
        private boolean ownsEntity;
        private boolean globalContext;
        private boolean sameRegionHint;
        private int sameRegionHintReads;

        @SuppressWarnings("unused")
        private boolean sameRegionHint() {
            sameRegionHintReads++;
            return sameRegionHint;
        }

        @Override
        public boolean isTickThread() {
            return tickThread;
        }

        @Override
        public boolean ownsChunk(FoliaWorldHandle world, int chunkX, int chunkZ) {
            return ownsChunk;
        }

        @Override
        public boolean ownsEntity(FoliaEntityHandle entity) {
            return ownsEntity;
        }

        @Override
        public boolean isGlobalContext() {
            return globalContext;
        }

    }

}
