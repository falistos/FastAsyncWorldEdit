package com.fastasyncworldedit.bukkit.adapter;

import com.fastasyncworldedit.bukkit.FaweBukkitWorld;
import com.fastasyncworldedit.core.math.IntPair;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.QueueHandlerRouting;
import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.fastasyncworldedit.core.util.task.ContextResolver;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.fastasyncworldedit.core.util.task.RegionCall;
import com.fastasyncworldedit.core.util.task.RegionTask;
import com.fastasyncworldedit.core.util.task.RegionTicket;
import com.fastasyncworldedit.core.util.task.TicketAuthority;
import com.sk89q.worldedit.bukkit.BukkitBlockCommandSender;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.bukkit.Bukkit;
import org.mockito.MockedStatic;
import org.mockito.invocation.InvocationOnMock;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

@Execution(ExecutionMode.SAME_THREAD)
class NMSAdapterRoutingTest {

    private static final MutableContext CONTEXT = new MutableContext();
    private static final TicketAuthority AUTHORITY = TicketAuthority.issue();

    @BeforeAll
    static void registerContext() {
        ContextResolver.register(CONTEXT);
    }

    @Test
    void worldOverloadPerformsOneCasOnlyInsideTheOwningTicketCallback() throws Exception {
        World world = mock(World.class);
        IntPair pair = new IntPair(8, 13);
        Object expected = new Object();
        Object value = new Object();
        Object[] sections = {expected};
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger compareAndSets = new AtomicInteger();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        QueueHandler handler = routingHandler(false, invocation -> {
            ChunkTarget target = invocation.getArgument(0);
            RegionCall<Boolean> call = invocation.getArgument(1);
            callbacks.incrementAndGet();
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                CONTEXT.own(target.world(), target.chunkX(), target.chunkZ());
                RegionTicket ticket = AUTHORITY.mintRegion(
                        target.world(),
                        target.chunkX(),
                        target.chunkZ()
                );
                try {
                    callbackThread.set(Thread.currentThread());
                    result.complete(call.call(ticket));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    AUTHORITY.retire(ticket);
                    CONTEXT.clear();
                }
            });
            return result;
        });

        try (AutoCloseable ignored = overrideRouting(handler)) {
            CompletionStage<Boolean> stage = NMSAdapter.setSectionAtomic(
                    world,
                    pair,
                    sections,
                    expected,
                    value,
                    0,
                    (actualSections, actualExpected, actualValue, layer) -> {
                        assertTrue(CONTEXT.ownsChunk(world, pair.x(), pair.z()));
                        compareAndSets.incrementAndGet();
                        if (actualSections[layer] != actualExpected) {
                            return false;
                        }
                        actualSections[layer] = actualValue;
                        return true;
                    }
            );
            assertTrue(stage.toCompletableFuture().get(1, TimeUnit.SECONDS));
        }

        assertEquals(1, callbacks.get());
        assertEquals(1, compareAndSets.get());
        assertTrue(callbackThread.get() != caller);
        assertSame(value, sections[0]);
    }

    @Test
    void legacyOverloadFailsBeforeTouchingSectionsWhenBackendRejectsIt() throws Exception {
        QueueHandler handler = routingHandler(false, invocation -> {
            throw new AssertionError("No target route should be submitted");
        });

        try (AutoCloseable ignored = overrideRouting(handler)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> ExposedAdapter.setSectionAtomic("world", new IntPair(1, 2), null, null, null, 0)
            );
        }
    }

    @Test
    void legacyOverloadKeepsThePaperGlobalContextCas() throws Exception {
        QueueHandler handler = routingHandler(true, invocation -> {
            throw new AssertionError("Paper global CAS must not dispatch");
        });
        Object expected = new Object();
        Object value = new Object();
        Object[] sections = {expected};
        CONTEXT.global = true;

        try (AutoCloseable ignored = overrideRouting(handler)) {
            assertTrue(ExposedAdapter.setSectionAtomic(
                    "world",
                    new IntPair(1, 2),
                    sections,
                    expected,
                    value,
                    0
            ));
        } finally {
            CONTEXT.global = false;
        }

        assertSame(value, sections[0]);
    }

    @Test
    void legacyOverloadKeepsThePaperWorkerLockAndCas() throws Exception {
        QueueHandler handler = routingHandler(true, invocation -> {
            throw new AssertionError("Paper worker CAS must not dispatch");
        });
        IntPair pair = new IntPair(3, 7);
        Object expected = new Object();
        Object value = new Object();
        Object[] sections = {expected};
        ConcurrentHashMap<IntPair, NMSAdapter.ChunkSendLock> locks = new ConcurrentHashMap<>();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getVersion).thenReturn("test-server");
            try (MockedStatic<FaweBukkitWorld> faweWorld = mockStatic(FaweBukkitWorld.class);
                 AutoCloseable ignored = overrideRouting(handler)) {
                faweWorld.when(() -> FaweBukkitWorld.getWorldSendingChunksMap("worker-world")).thenReturn(locks);
                assertTrue(ExposedAdapter.setSectionAtomic(
                        "worker-world",
                        pair,
                        sections,
                        expected,
                        value,
                        0
                ));
                NMSAdapter.ChunkSendLock lock = locks.get(pair);
                assertFalse(lock.writeWaiting);
                assertFalse(lock.lock.isWriteLocked());
            }
        }

        assertSame(value, sections[0]);
    }

    @Test
    void commandBlockProbeReachesItsOwningChunkContextInsteadOfRunningInline() throws Exception {
        World world = mock(World.class);
        ChunkTarget target = new ChunkTarget(world, 5, 11);
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        QueueHandler handler = routingHandler(false, invocation -> {
            ChunkTarget routedTarget = invocation.getArgument(0);
            RegionTask task = invocation.getArgument(1);
            CompletableFuture<Void> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                CONTEXT.own(routedTarget.world(), routedTarget.chunkX(), routedTarget.chunkZ());
                RegionTicket ticket = AUTHORITY.mintRegion(
                        routedTarget.world(),
                        routedTarget.chunkX(),
                        routedTarget.chunkZ()
                );
                try {
                    task.run(ticket);
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    AUTHORITY.retire(ticket);
                    CONTEXT.clear();
                }
            });
            return result;
        });
        Method route = BukkitBlockCommandSender.class.getDeclaredMethod(
                "routeActiveUpdate",
                ChunkTarget.class,
                Runnable.class
        );
        route.setAccessible(true);

        try (AutoCloseable ignored = overrideRouting(handler)) {
            CompletionStage<?> stage = (CompletionStage<?>) route.invoke(
                    null,
                    target,
                    (Runnable) () -> {
                        assertTrue(CONTEXT.ownsChunk(world, 5, 11));
                        callbackThread.set(Thread.currentThread());
                    }
            );
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }

        assertTrue(callbackThread.get() != caller);
    }

    private static QueueHandler routingHandler(boolean permitsLegacy, RoutingAnswer answer) {
        return mock(QueueHandler.class, invocation -> {
            String name = invocation.getMethod().getName();
            if (name.equals("permitsLegacyLocationFreeLiveState")) {
                return permitsLegacy;
            }
            if (name.equals("syncOn")) {
                return answer.answer(invocation);
            }
            return invocation.callRealMethod();
        });
    }

    private static AutoCloseable overrideRouting(QueueHandler handler) throws Exception {
        Method method = QueueHandlerRouting.class.getDeclaredMethod("overrideActiveForTesting", QueueHandler.class);
        method.setAccessible(true);
        return (AutoCloseable) method.invoke(null, handler);
    }

    @FunctionalInterface
    private interface RoutingAnswer {

        Object answer(InvocationOnMock invocation) throws Throwable;

    }

    private static final class ExposedAdapter extends NMSAdapter {

        protected static <S> CompletionStage<Boolean> setSectionAtomic(
                World world,
                IntPair pair,
                S[] sections,
                S expected,
                S value,
                int layer
        ) {
            return NMSAdapter.setSectionAtomic(world, pair, sections, expected, value, layer);
        }

        protected static <S> boolean setSectionAtomic(
                String worldName,
                IntPair pair,
                S[] sections,
                S expected,
                S value,
                int layer
        ) {
            return NMSAdapter.setSectionAtomic(worldName, pair, sections, expected, value, layer);
        }

    }

    private static final class MutableContext implements FaweThreadContext {

        private final ThreadLocal<World> ownedWorld = new ThreadLocal<>();
        private final ThreadLocal<IntPair> ownedChunk = new ThreadLocal<>();
        private volatile boolean global;

        private void own(World world, int chunkX, int chunkZ) {
            ownedWorld.set(world);
            ownedChunk.set(new IntPair(chunkX, chunkZ));
        }

        private void clear() {
            ownedWorld.remove();
            ownedChunk.remove();
        }

        @Override
        public boolean isTickThread() {
            return global || ownedWorld.get() != null;
        }

        @Override
        public boolean ownsChunk(World world, int chunkX, int chunkZ) {
            return ownedWorld.get() == world && new IntPair(chunkX, chunkZ).equals(ownedChunk.get());
        }

        @Override
        public boolean ownsEntity(Entity entity) {
            return false;
        }

        @Override
        public boolean isGlobalContext() {
            return global;
        }

        @Override
        public boolean isFaweWorker() {
            return false;
        }

    }

}
