package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v26_1;

import com.fastasyncworldedit.core.math.IntPair;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.QueueHandlerRouting;
import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.fastasyncworldedit.core.util.task.ContextResolver;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.fastasyncworldedit.core.util.task.RegionTask;
import com.fastasyncworldedit.core.util.task.RegionTicket;
import com.fastasyncworldedit.core.util.task.TicketAuthority;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@Execution(ExecutionMode.SAME_THREAD)
class PaperweightFaweWorldNativeAccessTest {

    private static final MutableContext CONTEXT = new MutableContext();
    private static final TicketAuthority AUTHORITY = TicketAuthority.issue();

    @BeforeAll
    static void registerContext() {
        ContextResolver.register(CONTEXT);
    }

    @Test
    void distinctTargetsBecomeDistinctOwningCallbacksWithNoCrossTargetPayload() throws Exception {
        World world = mock(World.class);
        TargetedValue first = new TargetedValue(new IntPair(1, 2), "first");
        TargetedValue second = new TargetedValue(new IntPair(1, 2), "second");
        TargetedValue third = new TargetedValue(new IntPair(8, 13), "third");
        IntPair sendOnly = new IntPair(-4, 9);
        Map<IntPair, List<TargetedValue>> partitions = ChunkTargetPartitions.partition(
                List.of(first, second, third),
                TargetedValue::target,
                Set.of(sendOnly)
        );
        List<IntPair> dispatchedTargets = new CopyOnWriteArrayList<>();
        List<IntPair> payloadTargets = new CopyOnWriteArrayList<>();
        List<Thread> payloadThreads = new CopyOnWriteArrayList<>();
        Thread caller = Thread.currentThread();
        QueueHandler handler = mock(QueueHandler.class, invocation -> {
            if (!invocation.getMethod().getName().equals("syncOn")) {
                return invocation.callRealMethod();
            }
            ChunkTarget target = invocation.getArgument(0);
            RegionTask task = invocation.getArgument(1);
            dispatchedTargets.add(new IntPair(target.chunkX(), target.chunkZ()));
            CompletableFuture<Void> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> runOnOwner(target, task, result));
            return result;
        });

        try (AutoCloseable ignored = overrideRouting(handler)) {
            List<CompletionStage<Void>> dispatches = ChunkTargetPartitions.dispatchEach(
                    world,
                    partitions,
                    (target, values) -> {
                        assertTrue(CONTEXT.ownsChunk(world, target.x(), target.z()));
                        assertTrue(values.stream().allMatch(value -> value.target().equals(target)));
                        payloadTargets.add(target);
                        payloadThreads.add(Thread.currentThread());
                    }
            );
            CompletableFuture.allOf(dispatches.stream()
                    .map(CompletionStage::toCompletableFuture)
                    .toArray(CompletableFuture[]::new)
            ).get(1, TimeUnit.SECONDS);
        }

        assertEquals(3, partitions.size());
        assertEquals(2, partitions.get(first.target()).size());
        assertTrue(partitions.get(sendOnly).isEmpty());
        assertEquals(partitions.keySet(), Set.copyOf(dispatchedTargets));
        assertEquals(partitions.keySet(), Set.copyOf(payloadTargets));
        assertEquals(3, payloadThreads.size());
        payloadThreads.forEach(thread -> assertNotEquals(caller, thread));
    }

    private static void runOnOwner(
            ChunkTarget target,
            RegionTask task,
            CompletableFuture<Void> result
    ) {
        CONTEXT.own(target.world(), target.chunkX(), target.chunkZ());
        RegionTicket ticket = AUTHORITY.mintRegion(target.world(), target.chunkX(), target.chunkZ());
        try {
            task.run(ticket);
            result.complete(null);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        } finally {
            AUTHORITY.retire(ticket);
            CONTEXT.clear();
        }
    }

    private static AutoCloseable overrideRouting(QueueHandler handler) throws Exception {
        Method method = QueueHandlerRouting.class.getDeclaredMethod("overrideActiveForTesting", QueueHandler.class);
        method.setAccessible(true);
        return (AutoCloseable) method.invoke(null, handler);
    }

    private record TargetedValue(IntPair target, String value) {
    }

    private static final class MutableContext implements FaweThreadContext {

        private final ThreadLocal<World> ownedWorld = new ThreadLocal<>();
        private final ThreadLocal<IntPair> ownedChunk = new ThreadLocal<>();

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
            return ownedWorld.get() != null;
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
            return false;
        }

        @Override
        public boolean isFaweWorker() {
            return false;
        }

    }

}
