/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.ContextResolver;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.fastasyncworldedit.core.util.task.OperationCompletionService;
import com.fastasyncworldedit.core.util.task.RegionTask;
import com.fastasyncworldedit.core.util.task.RegionTicket;
import com.fastasyncworldedit.core.util.task.TicketAuthority;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.SAME_THREAD)
class DefaultFoliaRegionDispatcherTest {

    private static final MutableContext CONTEXT = new MutableContext();
    private static final TicketAuthority AUTHORITY = TicketAuthority.issue();

    private OperationCompletionService completionService;

    @BeforeAll
    static void registerContext() {
        ContextResolver.register(CONTEXT);
    }

    @AfterEach
    void stopCompletionService() throws Exception {
        if (completionService != null) {
            completionService.flush(Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
        CONTEXT.clear();
    }

    @Test
    void ownerRegionRunsInlineWithAFunctionScopedTicket() throws Exception {
        Fixture fixture = fixture();
        World world = world("inline-world");
        CONTEXT.own(world, 2, 3);
        AtomicReference<RegionTicket> callbackTicket = new AtomicReference<>();

        CompletionStage<String> stage = fixture.dispatcher.onRegion(
                world,
                2,
                3,
                FoliaRegionDispatcher.TaskKind.COMMIT,
                ticket -> {
                    callbackTicket.set(ticket);
                    assertTrue(ticket.isLive());
                    ticket.assertOwns(2, 3);
                    return "done";
                }
        );

        assertNotNull(callbackTicket.get());
        assertFalse(callbackTicket.get().isLive());
        verify(fixture.regionScheduler, never()).execute(any(), any(), anyInt(), anyInt(), any());
        assertEquals("done", stage.toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertEquals(0, fixture.dispatcher.liveTickets());
        assertEquals(0, fixture.dispatcher.outstandingFutures());
        DefaultFoliaRegionDispatcher.DispatchMetrics metrics = fixture.dispatcher.metricsSnapshot().getFirst();
        assertEquals(1, metrics.submissions());
        assertEquals(1, metrics.callbacks());
        assertEquals(1, metrics.ticketMints());
        assertEquals(1, metrics.ticketRetires());
    }

    @Test
    void nonOwnerRegionSchedulesAndLateContinuationsUseIsolatedNotifications() throws Exception {
        Fixture fixture = fixture();
        World world = world("scheduled-world");
        FoliaWorldHandle bukkitWorld = new FoliaWorldHandle(mock());
        when(fixture.targetAdapter.adapt(world)).thenReturn(bukkitWorld);
        doAnswer(invocation -> {
            CONTEXT.own(world, 5, 6);
            invocation.<Runnable>getArgument(4).run();
            return null;
        }).when(fixture.regionScheduler).execute(
                same(fixture.plugin),
                same(bukkitWorld.world()),
                anyInt(),
                anyInt(),
                any()
        );

        CompletionStage<Void> stage = fixture.dispatcher.onRegion(
                world,
                5,
                6,
                FoliaRegionDispatcher.TaskKind.SNAPSHOT_CAPTURE,
                ticket -> {
                    ticket.assertOwns(5, 6);
                }
        );
        CompletableFuture<Void> future = stage.toCompletableFuture();
        future.get(1, TimeUnit.SECONDS);

        CountDownLatch continuationRan = new CountDownLatch(1);
        AtomicBoolean usedCompletionThread = new AtomicBoolean();
        AtomicBoolean usedNotificationThread = new AtomicBoolean();
        future.thenRun(() -> {
            usedCompletionThread.set(completionService.isCompletionThread());
            usedNotificationThread.set(completionService.isNotificationThread());
            continuationRan.countDown();
        });
        assertTrue(continuationRan.await(1, TimeUnit.SECONDS));
        assertFalse(usedCompletionThread.get());
        assertTrue(usedNotificationThread.get());
        verify(fixture.regionScheduler).execute(
                same(fixture.plugin),
                same(bukkitWorld.world()),
                anyInt(),
                anyInt(),
                any()
        );
    }

    @Test
    void retiredEntityCompletesExceptionallyWithoutMintingATicket() {
        Fixture fixture = fixture();
        Entity entity = mock(Entity.class);
        FoliaEntityHandle bukkitEntity = new FoliaEntityHandle(mock());
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(bukkitEntity.entity().getScheduler()).thenReturn(entityScheduler);
        when(fixture.targetAdapter.adapt(entity)).thenReturn(bukkitEntity);
        when(entityScheduler.execute(any(), any(), any(), anyLong())).thenReturn(false);
        AtomicBoolean callbackRan = new AtomicBoolean();

        CompletionStage<Void> stage = fixture.dispatcher.onEntity(
                entity,
                FoliaRegionDispatcher.TaskKind.FINALIZER,
                ticket -> callbackRan.set(true)
        );

        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertFalse(callbackRan.get());
        assertEquals(0, fixture.dispatcher.liveTickets());
        assertEquals(0, fixture.dispatcher.outstandingFutures());
    }

    @Test
    void ownerEntityRunsInlineWithAnExactEntityTicket() throws Exception {
        Fixture fixture = fixture();
        Entity entity = mock(Entity.class);
        CONTEXT.own(entity);
        AtomicReference<Entity> ticketEntity = new AtomicReference<>();

        CompletionStage<Void> stage = fixture.dispatcher.onEntity(
                entity,
                FoliaRegionDispatcher.TaskKind.FINALIZER,
                ticket -> {
                    ticket.assertOwns(entity);
                    ticketEntity.set(ticket.entity());
                }
        );

        assertSame(entity, ticketEntity.get());
        verify(fixture.targetAdapter, never()).adapt(entity);
        stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(0, fixture.dispatcher.liveTickets());
    }

    @Test
    void nonOwnerEntitySchedulesAndMintsOnTheOwningCallback() throws Exception {
        Fixture fixture = fixture();
        Entity entity = mock(Entity.class);
        FoliaEntityHandle bukkitEntity = new FoliaEntityHandle(mock());
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(bukkitEntity.entity().getScheduler()).thenReturn(entityScheduler);
        when(fixture.targetAdapter.adapt(entity)).thenReturn(bukkitEntity);
        when(entityScheduler.execute(any(), any(), any(), anyLong())).thenAnswer(invocation -> {
            CONTEXT.own(entity);
            invocation.<Runnable>getArgument(1).run();
            return true;
        });

        CompletionStage<Void> stage = fixture.dispatcher.onEntity(
                entity,
                FoliaRegionDispatcher.TaskKind.FINALIZER,
                ticket -> ticket.assertOwns(entity)
        );

        stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(0, fixture.dispatcher.liveTickets());
        assertEquals(0, fixture.dispatcher.outstandingFutures());
        verify(entityScheduler).execute(same(fixture.plugin), any(), any(), eq(1L));
    }

    @Test
    void callbackFailureStillRetiresItsTicket() {
        Fixture fixture = fixture();
        World world = world("failing-world");
        CONTEXT.own(world, 12, 13);
        AtomicReference<RegionTicket> callbackTicket = new AtomicReference<>();

        CompletionStage<Void> stage = fixture.dispatcher.onRegion(
                world,
                12,
                13,
                FoliaRegionDispatcher.TaskKind.COMMIT,
                (RegionTask) ticket -> {
                    callbackTicket.set(ticket);
                    throw new IllegalArgumentException("callback failed");
                }
        );

        assertThrows(CompletionException.class, () -> stage.toCompletableFuture().join());
        assertNotNull(callbackTicket.get());
        assertFalse(callbackTicket.get().isLive());
        assertEquals(0, fixture.dispatcher.liveTickets());
        assertEquals(0, fixture.dispatcher.outstandingFutures());
        DefaultFoliaRegionDispatcher.DispatchMetrics metrics = fixture.dispatcher.metricsSnapshot().getFirst();
        assertEquals(1, metrics.ticketMints());
        assertEquals(1, metrics.ticketRetires());
    }

    @Test
    void globalWorkUsesOnlyTheGlobalRegionScheduler() throws Exception {
        Fixture fixture = fixture();
        AtomicBoolean callbackRan = new AtomicBoolean();
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(fixture.globalRegionScheduler).execute(same(fixture.plugin), any());

        CompletionStage<Void> stage = fixture.dispatcher.onGlobal(
                FoliaRegionDispatcher.TaskKind.LEGACY_GLOBAL,
                () -> callbackRan.set(true)
        );

        stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertTrue(callbackRan.get());
        assertEquals(0, fixture.dispatcher.liveTickets());
        verify(fixture.globalRegionScheduler).execute(same(fixture.plugin), any());
    }

    @Test
    void drainDeadlineReportsUnresolvedSubmissionAndLabels() throws Exception {
        Fixture fixture = fixture();
        World world = world("drain-world");
        FoliaWorldHandle bukkitWorld = new FoliaWorldHandle(mock());
        when(fixture.targetAdapter.adapt(world)).thenReturn(bukkitWorld);
        AtomicReference<Runnable> regionCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            regionCallback.set(invocation.getArgument(4));
            return null;
        }).when(fixture.regionScheduler).execute(any(), any(), anyInt(), anyInt(), any());
        AtomicReference<Consumer<ScheduledTask>> deadlineCallback = new AtomicReference<>();
        ScheduledTask deadlineTask = mock(ScheduledTask.class);
        when(fixture.asyncScheduler.runDelayed(any(), any(), anyLong(), any())).thenAnswer(invocation -> {
            deadlineCallback.set(invocation.getArgument(1));
            return deadlineTask;
        });

        CompletionStage<Void> pending = fixture.dispatcher.onRegion(
                world,
                9,
                10,
                FoliaRegionDispatcher.TaskKind.PACKET,
                ticket -> {
                }
        );
        CompletionStage<FoliaRegionDispatcher.DrainReport> drain = fixture.dispatcher.drain(Duration.ofSeconds(1));
        deadlineCallback.get().accept(deadlineTask);
        FoliaRegionDispatcher.DrainReport report = drain.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(1, report.unresolvedTasks());
        assertEquals(0, report.liveTickets());
        assertEquals(1, report.unresolvedLabels().size());
        assertTrue(report.unresolvedLabels().getFirst().contains("PACKET region:drain-world:9:10"));

        CONTEXT.own(world, 9, 10);
        regionCallback.get().run();
        pending.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(0, fixture.dispatcher.outstandingFutures());
    }

    @Test
    void drainDeadlineSurvivesPluginAsyncSchedulerCancellation() throws Exception {
        Fixture fixture = fixture();
        World world = world("cancelled-deadline-world");
        FoliaWorldHandle bukkitWorld = new FoliaWorldHandle(mock());
        when(fixture.targetAdapter.adapt(world)).thenReturn(bukkitWorld);
        AtomicReference<Runnable> regionCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            regionCallback.set(invocation.getArgument(4));
            return null;
        }).when(fixture.regionScheduler).execute(any(), any(), anyInt(), anyInt(), any());
        ScheduledTask deadlineTask = mock(ScheduledTask.class);
        when(fixture.asyncScheduler.runDelayed(any(), any(), anyLong(), any())).thenReturn(deadlineTask);

        CompletionStage<Void> pending = fixture.dispatcher.onRegion(
                world,
                14,
                15,
                FoliaRegionDispatcher.TaskKind.PACKET,
                ticket -> {
                }
        );
        FoliaRegionDispatcher.DrainReport report = fixture.dispatcher.drain(Duration.ofMillis(20))
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS);

        assertEquals(1, report.unresolvedTasks());
        assertEquals(1, report.unresolvedLabels().size());
        verify(deadlineTask).cancel();

        CONTEXT.own(world, 14, 15);
        regionCallback.get().run();
        pending.toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void completionFlushExpiryTerminalizesDrainExactlyOnceAndReleasesProducer() throws Exception {
        AtomicBoolean snapshotWorkForbidden = new AtomicBoolean();
        Fixture fixture = fixture(() -> {
            if (snapshotWorkForbidden.get()) {
                throw new AssertionError("drain expiry rebuilt or traversed its diagnostic snapshot");
            }
        });
        World world = world("flush-expiry-world");
        FoliaWorldHandle bukkitWorld = new FoliaWorldHandle(mock());
        when(fixture.targetAdapter.adapt(world)).thenReturn(bukkitWorld);
        AtomicReference<Runnable> regionCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            regionCallback.set(invocation.getArgument(4));
            return null;
        }).when(fixture.regionScheduler).execute(any(), any(), anyInt(), anyInt(), any());
        ScheduledTask deadlineTask = mock(ScheduledTask.class);
        when(fixture.asyncScheduler.runDelayed(any(), any(), anyLong(), any())).thenReturn(deadlineTask);

        CompletionStage<Void> pending = fixture.dispatcher.onRegion(
                world,
                18,
                19,
                FoliaRegionDispatcher.TaskKind.PACKET,
                ticket -> {
                }
        );
        CompletionStage<FoliaRegionDispatcher.DrainReport> drain = fixture.dispatcher.drain(Duration.ofDays(1));

        snapshotWorkForbidden.set(true);
        CompletionStage<Void> flush = completionService.flush(Duration.ofMillis(20));
        FoliaRegionDispatcher.DrainReport report = drain.toCompletableFuture().get(1, TimeUnit.SECONDS);
        flush.toCompletableFuture().get(1, TimeUnit.SECONDS);
        snapshotWorkForbidden.set(false);

        assertEquals(1, report.unresolvedTasks());
        assertEquals(report.unresolvedTasks(), report.unresolvedLabels().size());
        assertEquals(0, completionService.registeredProducerCount());
        completionService.flush(Duration.ofMillis(20)).toCompletableFuture().get(1, TimeUnit.SECONDS);
        verify(deadlineTask).cancel();

        CONTEXT.own(world, 18, 19);
        regionCallback.get().run();
        pending.toCompletableFuture().get(1, TimeUnit.SECONDS);
    }

    @Test
    void naturalCompletionAndExpiryPublishTheSameSnapshotReport() throws Exception {
        SnapshotPathFixture fixture = snapshotPathFixture();
        CompletionStage<Void> pending = fixture.dispatcher.onGlobal(
                FoliaRegionDispatcher.TaskKind.PACKET,
                () -> {
                }
        );
        CompletionStage<FoliaRegionDispatcher.DrainReport> settling = fixture.dispatcher.drain(Duration.ofDays(1));
        fixture.globalCallback.get().run();
        pending.toCompletableFuture().get(1, TimeUnit.SECONDS);
        FoliaRegionDispatcher.DrainReport natural = settling.toCompletableFuture().get(1, TimeUnit.SECONDS);

        CompletionStage<FoliaRegionDispatcher.DrainReport> expiring = fixture.dispatcher.drain(Duration.ofDays(1));
        fixture.expiryHook.get().expire(0);
        FoliaRegionDispatcher.DrainReport expired = expiring.toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(natural, expired);
        assertEquals(new FoliaRegionDispatcher.DrainReport(0, 0, List.of()), natural);
    }

    @Test
    void concurrentRegistrationAndRetirementNeverPublishATornReport() throws Exception {
        Fixture fixture = fixture();
        World world = world("concurrent-snapshot-world");
        FoliaWorldHandle bukkitWorld = new FoliaWorldHandle(mock());
        when(fixture.targetAdapter.adapt(world)).thenReturn(bukkitWorld);
        ConcurrentLinkedQueue<ScheduledRegion> callbacks = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<CompletionStage<Void>> stages = new ConcurrentLinkedQueue<>();
        AtomicBoolean registrationFinished = new AtomicBoolean();
        doAnswer(invocation -> {
            callbacks.add(new ScheduledRegion(
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4)
            ));
            return null;
        }).when(fixture.regionScheduler).execute(any(), any(), anyInt(), anyInt(), any());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> registrations = workers.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    int chunkX = i;
                    stages.add(fixture.dispatcher.onRegion(
                            world,
                            chunkX,
                            0,
                            FoliaRegionDispatcher.TaskKind.ASYNC,
                            (RegionTask) ticket -> ticket.assertOwns(chunkX, 0)
                    ));
                }
                registrationFinished.set(true);
            });
            Future<?> retirements = workers.submit(() -> {
                while (!registrationFinished.get() || !callbacks.isEmpty()) {
                    ScheduledRegion callback = callbacks.poll();
                    if (callback != null) {
                        CONTEXT.own(world, callback.chunkX(), callback.chunkZ());
                        callback.task().run();
                    }
                }
            });

            for (int i = 0; i < 100; i++) {
                FoliaRegionDispatcher.DrainReport report = fixture.dispatcher.drain(Duration.ZERO)
                        .toCompletableFuture()
                        .get(1, TimeUnit.SECONDS);
                assertEquals(report.unresolvedTasks(), report.unresolvedLabels().size());
                assertTrue(report.liveTickets() >= 0);
                assertTrue(report.liveTickets() <= report.unresolvedTasks());
            }
            registrations.get(1, TimeUnit.SECONDS);
            retirements.get(1, TimeUnit.SECONDS);
            for (CompletionStage<Void> stage : stages) {
                stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void unresolvedLabelOrderIsDeterministicAcrossDispatchers() throws Exception {
        List<String> first = captureOrderedLabels();
        completionService.flush(Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS);
        List<String> second = captureOrderedLabels();

        assertEquals(first, second);
        assertEquals(List.of("PACKET global #1", "COMMIT global #2", "ASYNC global #3"), first);
    }

    @Test
    void drainProducerTransitionFailureCompletesTheIsolatedStageExceptionally() {
        FailureFixture fixture = failureFixture();
        CompletionStage<FoliaRegionDispatcher.DrainReport> drain = fixture.dispatcher.drain(Duration.ZERO);
        IllegalStateException failure = new IllegalStateException("producer transition failed");

        fixture.transitionFailure.get().accept(failure);

        CompletionException completionFailure = assertThrows(
                CompletionException.class,
                () -> drain.toCompletableFuture().join()
        );
        assertSame(failure, completionFailure.getCause());
        verify(fixture.producer).complete(any());
    }

    @Test
    void drainFailsClosedWhenProducerNoLongerAcceptsTerminalTransition() {
        FailureFixture fixture = failureFixture();
        IllegalStateException failure = new IllegalStateException("producer already complete");
        when(fixture.producer.complete(any())).thenThrow(failure);

        CompletionStage<FoliaRegionDispatcher.DrainReport> drain = fixture.dispatcher.drain(Duration.ZERO);

        CompletionException completionFailure = assertThrows(
                CompletionException.class,
                () -> drain.toCompletableFuture().join()
        );
        assertSame(failure, completionFailure.getCause());
    }

    @Test
    void postTerminationTickCallbackEnqueuesBeforeCompleting() throws Exception {
        Fixture fixture = fixture();
        World world = world("post-termination-world");
        FoliaWorldHandle bukkitWorld = new FoliaWorldHandle(mock());
        when(fixture.targetAdapter.adapt(world)).thenReturn(bukkitWorld);
        AtomicReference<Runnable> regionCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            regionCallback.set(invocation.getArgument(4));
            return null;
        }).when(fixture.regionScheduler).execute(any(), any(), anyInt(), anyInt(), any());
        CountDownLatch continuationRan = new CountDownLatch(1);
        AtomicBoolean usedCompletionThread = new AtomicBoolean();
        AtomicBoolean usedNotificationThread = new AtomicBoolean();

        CompletionStage<Void> stage = fixture.dispatcher.onRegion(
                world,
                16,
                17,
                FoliaRegionDispatcher.TaskKind.COMMIT,
                (RegionTask) ticket -> ticket.assertOwns(16, 17)
        );
        stage.thenRun(() -> {
            usedCompletionThread.set(completionService.isCompletionThread());
            usedNotificationThread.set(completionService.isNotificationThread());
            continuationRan.countDown();
        });
        completionService.flush(Duration.ofSeconds(1)).toCompletableFuture().get(1, TimeUnit.SECONDS);

        CONTEXT.own(world, 16, 17);
        regionCallback.get().run();

        stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertTrue(continuationRan.await(1, TimeUnit.SECONDS));
        assertFalse(usedCompletionThread.get());
        assertTrue(usedNotificationThread.get());
        assertEquals(0, fixture.dispatcher.outstandingFutures());
    }

    @Test
    void stopAcceptingRejectsNewWorkWithoutSchedulingIt() {
        Fixture fixture = fixture();
        World world = world("stopped-world");
        fixture.dispatcher.stopAccepting(new IllegalStateException("disable"));

        CompletionStage<Void> rejected = fixture.dispatcher.onRegion(
                world,
                0,
                0,
                FoliaRegionDispatcher.TaskKind.COMMIT,
                ticket -> {
                }
        );

        assertThrows(CompletionException.class, () -> rejected.toCompletableFuture().join());
        verify(fixture.regionScheduler, never()).execute(any(), any(), anyInt(), anyInt(), any());
        assertEquals(0, fixture.dispatcher.outstandingFutures());
    }

    private Fixture fixture() {
        return fixture(() -> {
        });
    }

    private Fixture fixture(Runnable snapshotWorkProbe) {
        completionService = new OperationCompletionService("dispatcher-test-completion");
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        GlobalRegionScheduler globalRegionScheduler = mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = mock(AsyncScheduler.class);
        FoliaTargetAdapter targetAdapter = mock(FoliaTargetAdapter.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        when(server.getGlobalRegionScheduler()).thenReturn(globalRegionScheduler);
        when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
        DefaultFoliaRegionDispatcher dispatcher = new DefaultFoliaRegionDispatcher(
                plugin,
                AUTHORITY,
                completionService,
                targetAdapter,
                server,
                System::nanoTime,
                snapshotWorkProbe
        );
        return new Fixture(
                dispatcher,
                plugin,
                regionScheduler,
                globalRegionScheduler,
                asyncScheduler,
                targetAdapter
        );
    }

    private SnapshotPathFixture snapshotPathFixture() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler globalRegionScheduler = mock(GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getRegionScheduler()).thenReturn(mock(RegionScheduler.class));
        when(server.getGlobalRegionScheduler()).thenReturn(globalRegionScheduler);
        when(server.getAsyncScheduler()).thenReturn(mock(AsyncScheduler.class));
        OperationCompletionService isolatedService = mock(OperationCompletionService.class);
        OperationCompletionService.Producer producer = mock(OperationCompletionService.Producer.class);
        AtomicReference<OperationCompletionService.FlushExpiryHook> expiryHook = new AtomicReference<>();
        AtomicReference<Runnable> globalCallback = new AtomicReference<>();
        when(isolatedService.registerProducer(any(), any(), any())).thenAnswer(invocation -> {
            expiryHook.set(invocation.getArgument(1));
            return producer;
        });
        when(isolatedService.isolateStage(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(isolatedService).execute(any());
        when(producer.execute(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(producer.complete(any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return CompletableFuture.completedFuture(null);
        });
        doAnswer(invocation -> {
            globalCallback.set(invocation.getArgument(1));
            return null;
        }).when(globalRegionScheduler).execute(same(plugin), any());
        DefaultFoliaRegionDispatcher dispatcher = new DefaultFoliaRegionDispatcher(
                plugin,
                AUTHORITY,
                isolatedService,
                mock(FoliaTargetAdapter.class)
        );
        return new SnapshotPathFixture(dispatcher, globalCallback, expiryHook);
    }

    private List<String> captureOrderedLabels() throws Exception {
        Fixture fixture = fixture();
        List<Runnable> callbacks = new ArrayList<>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1));
            return null;
        }).when(fixture.globalRegionScheduler).execute(same(fixture.plugin), any());
        List<CompletionStage<Void>> stages = List.of(
                fixture.dispatcher.onGlobal(FoliaRegionDispatcher.TaskKind.PACKET, () -> {
                }),
                fixture.dispatcher.onGlobal(FoliaRegionDispatcher.TaskKind.COMMIT, () -> {
                }),
                fixture.dispatcher.onGlobal(FoliaRegionDispatcher.TaskKind.ASYNC, () -> {
                })
        );
        List<String> labels = fixture.dispatcher.drain(Duration.ZERO)
                .toCompletableFuture()
                .get(1, TimeUnit.SECONDS)
                .unresolvedLabels();
        callbacks.forEach(Runnable::run);
        for (CompletionStage<Void> stage : stages) {
            stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        }
        return labels;
    }

    private FailureFixture failureFixture() {
        Plugin plugin = mock(Plugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getRegionScheduler()).thenReturn(mock(RegionScheduler.class));
        when(server.getGlobalRegionScheduler()).thenReturn(mock(GlobalRegionScheduler.class));
        when(server.getAsyncScheduler()).thenReturn(mock(AsyncScheduler.class));
        OperationCompletionService isolatedService = mock(OperationCompletionService.class);
        OperationCompletionService.Producer producer = mock(OperationCompletionService.Producer.class);
        AtomicReference<Consumer<Throwable>> transitionFailure = new AtomicReference<>();
        when(isolatedService.registerProducer(any(), any(), any())).thenAnswer(invocation -> {
            transitionFailure.set(invocation.getArgument(2));
            return producer;
        });
        when(isolatedService.isolateStage(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DefaultFoliaRegionDispatcher dispatcher = new DefaultFoliaRegionDispatcher(
                plugin,
                AUTHORITY,
                isolatedService,
                mock(FoliaTargetAdapter.class)
        );
        return new FailureFixture(dispatcher, producer, transitionFailure);
    }

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    private record Fixture(
            DefaultFoliaRegionDispatcher dispatcher,
            Plugin plugin,
            RegionScheduler regionScheduler,
            GlobalRegionScheduler globalRegionScheduler,
            AsyncScheduler asyncScheduler,
            FoliaTargetAdapter targetAdapter
    ) {
    }

    private record FailureFixture(
            DefaultFoliaRegionDispatcher dispatcher,
            OperationCompletionService.Producer producer,
            AtomicReference<Consumer<Throwable>> transitionFailure
    ) {
    }

    private record ScheduledRegion(int chunkX, int chunkZ, Runnable task) {
    }

    private record SnapshotPathFixture(
            DefaultFoliaRegionDispatcher dispatcher,
            AtomicReference<Runnable> globalCallback,
            AtomicReference<OperationCompletionService.FlushExpiryHook> expiryHook
    ) {
    }

    private static final class MutableContext implements FaweThreadContext {

        private volatile Thread tickThread;
        private volatile World world;
        private volatile int chunkX;
        private volatile int chunkZ;
        private volatile Entity entity;

        private void own(World world, int chunkX, int chunkZ) {
            this.tickThread = Thread.currentThread();
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.entity = null;
        }

        private void own(Entity entity) {
            this.tickThread = Thread.currentThread();
            this.world = null;
            this.entity = entity;
        }

        private void clear() {
            tickThread = null;
            world = null;
            entity = null;
        }

        @Override
        public boolean isTickThread() {
            return tickThread == Thread.currentThread();
        }

        @Override
        public boolean ownsChunk(World world, int chunkX, int chunkZ) {
            return isTickThread() && this.world == world && this.chunkX == chunkX && this.chunkZ == chunkZ;
        }

        @Override
        public boolean ownsEntity(Entity entity) {
            return isTickThread() && this.entity == entity;
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
