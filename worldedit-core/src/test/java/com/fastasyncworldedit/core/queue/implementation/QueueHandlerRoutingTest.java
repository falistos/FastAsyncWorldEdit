package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.fastasyncworldedit.core.util.task.EntityTarget;
import com.fastasyncworldedit.core.util.task.EntityTask;
import com.fastasyncworldedit.core.util.task.GlobalTask;
import com.fastasyncworldedit.core.util.task.RegionCall;
import com.fastasyncworldedit.core.util.task.RegionTask;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class QueueHandlerRoutingTest {

    @Test
    void delegatesEveryInternalRouteToTheActiveQueueHandlerWithoutExposingIt() throws Exception {
        List<Object[]> invocations = new ArrayList<>();
        CompletionStage<Object> callStage = CompletableFuture.completedFuture("call");
        CompletionStage<Void> taskStage = CompletableFuture.completedFuture(null);
        QueueHandler handler = mock(QueueHandler.class, invocation -> {
            invocations.add(invocation.getArguments());
            if (invocation.getMethod().getName().equals("permitsLegacyLocationFreeLiveState")) {
                return false;
            }
            if (invocation.getMethod().getName().equals("syncOn")
                    && invocation.getArguments()[1] instanceof RegionCall<?>) {
                return callStage;
            }
            return taskStage;
        });
        World world = mock(World.class);
        Entity entity = mock(Entity.class);
        ChunkTarget chunk = new ChunkTarget(world, 3, 7);
        EntityTarget entityTarget = new EntityTarget(entity);
        RegionCall<Object> call = ticket -> "unused";
        RegionTask regionTask = ticket -> {
        };
        EntityTask entityTask = ticket -> {
        };
        GlobalTask globalTask = () -> {
        };

        try (AutoCloseable ignored = QueueHandlerRouting.overrideActiveForTesting(handler)) {
            assertSame(callStage, QueueHandlerRouting.syncOn(chunk, call));
            assertSame(taskStage, QueueHandlerRouting.syncOn(chunk, regionTask));
            assertSame(taskStage, QueueHandlerRouting.syncOn(entityTarget, entityTask));
            assertSame(taskStage, QueueHandlerRouting.syncOnGlobal(globalTask));
            assertFalse(QueueHandlerRouting.permitsLegacyLocationFreeLiveState());
        }

        assertSame(chunk, invocations.get(0)[0]);
        assertSame(call, invocations.get(0)[1]);
        assertSame(regionTask, invocations.get(1)[1]);
        assertSame(entityTarget, invocations.get(2)[0]);
        assertSame(entityTask, invocations.get(2)[1]);
        assertSame(globalTask, invocations.get(3)[0]);
    }

}
