package com.fastasyncworldedit.core.util.task;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.TaskManager;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BootstrapOrderingTest {

    @Test
    void queueRunAndRequalifiedPredicatesFailUntilRegistrationCompletes() throws Exception {
        AtomicReference<FaweThreadContext> resolver = resolverReference();
        FaweThreadContext previous = resolver.getAndSet(null);
        Field taskManagerInstance = TaskManager.class.getDeclaredField("INSTANCE");
        taskManagerInstance.setAccessible(true);
        TaskManager previousTaskManager = (TaskManager) taskManagerInstance.get(null);
        TaskManager previousImp = TaskManager.IMP;
        RecordingTaskManager taskManager = null;
        TestQueueHandler queueHandler = null;
        try {
            taskManager = new RecordingTaskManager();
            queueHandler = new TestQueueHandler();
            assertThrows(IllegalStateException.class, queueHandler::run);
            assertThrows(IllegalStateException.class, FaweThreadContext::current);

            AtomicInteger predicateReads = new AtomicInteger();
            ContextResolver.register(context(predicateReads));
            assertDoesNotThrow(taskManager::runScheduledQueue);
            assertEquals(1, predicateReads.get());
        } finally {
            if (queueHandler != null) {
                queueHandler.shutdownExecutors();
            }
            if (taskManager != null) {
                taskManager.getPublicForkJoinPool().shutdownNow();
            }
            taskManagerInstance.set(null, previousTaskManager);
            TaskManager.IMP = previousImp;
            resolver.set(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<FaweThreadContext> resolverReference() throws Exception {
        Field context = ContextResolver.class.getDeclaredField("CONTEXT");
        context.setAccessible(true);
        return (AtomicReference<FaweThreadContext>) context.get(null);
    }

    private static FaweThreadContext context(AtomicInteger predicateReads) {
        return new FaweThreadContext() {
            @Override
            public boolean isTickThread() {
                predicateReads.incrementAndGet();
                return true;
            }

            @Override
            public boolean ownsChunk(World world, int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean ownsEntity(Entity entity) {
                return true;
            }

            @Override
            public boolean isGlobalContext() {
                predicateReads.incrementAndGet();
                return true;
            }

            @Override
            public boolean isFaweWorker() {
                return false;
            }
        };
    }

    private static final class RecordingTaskManager extends TaskManager {

        private Runnable scheduledQueue;

        @Override
        public int repeat(Runnable runnable, int interval) {
            scheduledQueue = runnable;
            return 1;
        }

        @Override
        public int repeatAsync(Runnable runnable, int interval) {
            return 2;
        }

        @Override
        public void async(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void task(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void later(Runnable runnable, int delay) {
            runnable.run();
        }

        @Override
        public void laterAsync(Runnable runnable, int delay) {
            runnable.run();
        }

        @Override
        public void cancel(int task) {
        }

        private void runScheduledQueue() {
            scheduledQueue.run();
        }

    }

    private static final class TestQueueHandler extends QueueHandler {

        @Override
        public void startUnsafe(boolean parallel) {
        }

        @Override
        public void endUnsafe(boolean parallel) {
        }

    }

}
