package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.fastasyncworldedit.core.util.task.EntityTarget;
import com.fastasyncworldedit.core.util.task.EntityTask;
import com.fastasyncworldedit.core.util.task.GlobalTask;
import com.fastasyncworldedit.core.util.task.RegionCall;
import com.fastasyncworldedit.core.util.task.RegionTask;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoliaQueueHandlerTest {

    @Test
    void preservesAllTenPublicQueueHandlerDescriptors() throws Exception {
        assertDescriptor(QueueHandler.class, "async", Runnable.class, Object.class);
        assertDescriptor(QueueHandler.class, "async", Runnable.class);
        assertDescriptor(QueueHandler.class, "async", Callable.class);
        assertDescriptor(FoliaQueueHandler.class, "sync", Runnable.class);
        Method syncCallable = assertDescriptor(FoliaQueueHandler.class, "sync", Callable.class);
        assertDescriptor(FoliaQueueHandler.class, "sync", Supplier.class);
        assertDescriptor(FoliaQueueHandler.class, "syncWhenFree", Runnable.class, Object.class);
        assertDescriptor(FoliaQueueHandler.class, "syncWhenFree", Runnable.class);
        Method whenFreeCallable = assertDescriptor(
                FoliaQueueHandler.class,
                "syncWhenFree",
                Callable.class
        );
        assertDescriptor(FoliaQueueHandler.class, "syncWhenFree", Supplier.class);

        assertArrayEquals(new Class<?>[]{Exception.class}, syncCallable.getExceptionTypes());
        assertArrayEquals(new Class<?>[]{Exception.class}, whenFreeCallable.getExceptionTypes());
    }

    @Test
    void locationFreeSyncRowsRunInlineOnAnyTickContext() throws Exception {
        Fixture fixture = fixture(true);
        AtomicInteger callbacks = new AtomicInteger();

        assertNull(fixture.handler.<Object>sync((Runnable) callbacks::incrementAndGet).get());
        assertEquals(2, fixture.handler.sync((Callable<Integer>) callbacks::incrementAndGet).get());
        assertEquals(3, fixture.handler.sync((Supplier<Integer>) callbacks::incrementAndGet).get());
        assertEquals("value", fixture.handler.syncWhenFree(callbacks::incrementAndGet, "value").get());
        assertNull(fixture.handler.<Object>syncWhenFree((Runnable) callbacks::incrementAndGet).get());
        assertEquals(6, fixture.handler.syncWhenFree((Callable<Integer>) callbacks::incrementAndGet).get());
        assertEquals(7, fixture.handler.syncWhenFree((Supplier<Integer>) callbacks::incrementAndGet).get());

        assertEquals(7, callbacks.get());
        assertEquals(0, fixture.dispatcher.globalCalls);
    }

    @Test
    void locationFreeSyncRowsUseTheGlobalDispatcherOffTick() throws Exception {
        Fixture fixture = fixture(false);
        AtomicInteger callbacks = new AtomicInteger();

        assertNull(fixture.handler.<Object>sync((Runnable) callbacks::incrementAndGet).get());
        assertEquals(2, fixture.handler.sync((Callable<Integer>) callbacks::incrementAndGet).get());
        assertEquals(3, fixture.handler.sync((Supplier<Integer>) callbacks::incrementAndGet).get());
        assertEquals("value", fixture.handler.syncWhenFree(callbacks::incrementAndGet, "value").get());
        assertNull(fixture.handler.<Object>syncWhenFree((Runnable) callbacks::incrementAndGet).get());
        assertEquals(6, fixture.handler.syncWhenFree((Callable<Integer>) callbacks::incrementAndGet).get());
        assertEquals(7, fixture.handler.syncWhenFree((Supplier<Integer>) callbacks::incrementAndGet).get());

        assertEquals(7, callbacks.get());
        assertEquals(7, fixture.dispatcher.globalCalls);
        assertTrue(fixture.dispatcher.globalKinds.stream().allMatch(
                kind -> kind == FoliaRegionDispatcher.TaskKind.LEGACY_GLOBAL
        ));
    }

    @Test
    void normalSyncPreemptsQueuedWhenFreeAfterTheLaneIsArmed() throws Exception {
        Fixture fixture = fixture(false);
        fixture.dispatcher.deferGlobal = true;
        List<String> order = new CopyOnWriteArrayList<>();

        Future<Object> whenFree = fixture.handler.syncWhenFree((Runnable) () -> order.add("when-free"));
        assertEquals(1, fixture.dispatcher.globalCalls);
        assertTrue(order.isEmpty());

        AtomicReference<Future<Object>> normal = new AtomicReference<>();
        Thread submitter = Thread.ofVirtual().start(() -> normal.set(
                fixture.handler.sync((Runnable) () -> order.add("normal"))
        ));
        submitter.join(1_000);
        assertFalse(submitter.isAlive());
        assertEquals(1, fixture.dispatcher.globalCalls);

        fixture.dispatcher.runDeferredGlobal();
        assertNull(normal.get().get(1, TimeUnit.SECONDS));
        assertNull(whenFree.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("normal", "when-free"), order);
    }

    @Test
    void globalSyncWorkSharesTheLaneLocalAdaptiveSliceBudget() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.deferGlobal = true;
        AtomicLong clock = new AtomicLong();
        FoliaCommitBroker broker = syncBroker(dispatcher, clock);
        FoliaQueueHandler handler = handler(dispatcher, broker, Duration.ofSeconds(5));
        AtomicInteger callbacks = new AtomicInteger();
        List<Future<Object>> futures = new CopyOnWriteArrayList<>();

        for (int index = 0; index < 10; index++) {
            futures.add(handler.sync((Runnable) () -> {
                callbacks.incrementAndGet();
                clock.addAndGet(TimeUnit.MICROSECONDS.toNanos(200));
            }));
        }

        assertEquals(1, dispatcher.globalCalls);
        dispatcher.runDeferredGlobal();
        assertEquals(4, callbacks.get());
        assertEquals(1, dispatcher.deferredGlobalCount());

        dispatcher.runAllDeferredGlobal();
        assertEquals(10, callbacks.get());
        assertTrue(futures.stream().allMatch(Future::isDone));
    }

    @Test
    void chunkTargetSyncWorkSharesItsRegionLaneAdaptiveSliceBudget() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.executeTargets = true;
        dispatcher.deferRegionTasks = true;
        AtomicLong clock = new AtomicLong();
        FoliaCommitBroker broker = targetBroker(dispatcher, clock);
        FoliaQueueHandler handler = handler(dispatcher, broker, Duration.ofSeconds(5));
        AtomicInteger callbacks = new AtomicInteger();
        ChunkTarget target = new ChunkTarget(proxy(World.class), 8, 13);
        List<CompletionStage<Void>> stages = new CopyOnWriteArrayList<>();

        for (int index = 0; index < 10; index++) {
            stages.add(handler.syncOn(target, (RegionTask) ticket -> {
                callbacks.incrementAndGet();
                clock.addAndGet(TimeUnit.MICROSECONDS.toNanos(200));
            }));
        }

        assertEquals(1, dispatcher.deferredRegionCount());
        dispatcher.runDeferredRegion();
        assertEquals(4, callbacks.get());
        assertEquals(1, dispatcher.deferredRegionCount());

        dispatcher.runAllDeferredRegions();
        assertEquals(10, callbacks.get());
        assertTrue(stages.stream().allMatch(stage -> stage.toCompletableFuture().isDone()));
    }

    @Test
    void callableFailureIsReportedOnceWithItsOriginalCause() throws Exception {
        Fixture fixture = fixture(false);
        Exception expected = new Exception("checked failure");

        Future<Object> future = fixture.handler.sync((Callable<Object>) () -> {
            throw expected;
        });

        ExecutionException failure = assertThrows(ExecutionException.class, future::get);
        assertSame(expected, failure.getCause());
        assertEquals(1, fixture.dispatcher.globalCalls);
    }

    @Test
    void cancellingAQueuedLocationFreeFutureSkipsItsCallback() {
        Fixture fixture = fixture(false);
        fixture.dispatcher.deferGlobal = true;
        AtomicInteger callbacks = new AtomicInteger();

        Future<Object> future = fixture.handler.sync((Runnable) callbacks::incrementAndGet);
        assertTrue(future.cancel(false));
        fixture.dispatcher.runDeferredGlobal();

        assertTrue(future.isCancelled());
        assertEquals(0, callbacks.get());
    }

    @Test
    void queuedFutureTimesOutAndSkipsADeferredCallback() throws Exception {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.deferGlobal = true;
        FoliaQueueHandler handler = handler(dispatcher, Duration.ofMillis(20));
        AtomicInteger callbacks = new AtomicInteger();

        Future<Object> future = handler.sync((Runnable) callbacks::incrementAndGet);
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.get(1, TimeUnit.SECONDS)
        );

        assertTrue(failure.getCause() instanceof TimeoutException);
        dispatcher.runDeferredGlobal();
        assertEquals(0, callbacks.get());
    }

    @Test
    void targetStageTimesOutAndSkipsAnUnclaimedOwningCallback() throws Exception {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.deferRegionTasks = true;
        FoliaCommitBroker broker = targetBroker(dispatcher, new AtomicLong());
        FoliaQueueHandler handler = handler(dispatcher, broker, Duration.ofMillis(20));
        AtomicInteger callbacks = new AtomicInteger();
        ChunkTarget target = new ChunkTarget(proxy(World.class), 4, 9);

        CompletionStage<Void> stage = handler.syncOn(target, (RegionTask) ticket -> callbacks.incrementAndGet());
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> stage.toCompletableFuture().get(1, TimeUnit.SECONDS)
        );

        assertTrue(failure.getCause() instanceof TimeoutException);
        dispatcher.runDeferredRegion();
        assertEquals(0, callbacks.get());
    }

    @Test
    void asynchronousDispatcherExercisesBothCancelClaimOutcomes() throws Exception {
        AsyncDispatcher cancelledDispatcher = new AsyncDispatcher();
        FoliaQueueHandler cancelledHandler = handler(cancelledDispatcher, Duration.ofSeconds(5));
        AtomicInteger cancelledCallbacks = new AtomicInteger();
        Future<Object> cancelled = cancelledHandler.sync((Runnable) cancelledCallbacks::incrementAndGet);
        assertTrue(cancelledDispatcher.submitted.await(1, TimeUnit.SECONDS));

        AtomicBoolean cancellationWon = new AtomicBoolean();
        Thread canceller = Thread.ofVirtual().start(() -> cancellationWon.set(cancelled.cancel(false)));
        canceller.join(1_000);
        cancelledDispatcher.releaseDispatch.countDown();
        assertTrue(cancelledDispatcher.finished.await(1, TimeUnit.SECONDS));

        assertTrue(cancellationWon.get());
        assertEquals(0, cancelledCallbacks.get());

        AsyncDispatcher claimedDispatcher = new AsyncDispatcher();
        FoliaQueueHandler claimedHandler = handler(claimedDispatcher, Duration.ofSeconds(5));
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        Future<Integer> claimed = claimedHandler.sync((Callable<Integer>) () -> {
            callbackStarted.countDown();
            assertTrue(releaseCallback.await(1, TimeUnit.SECONDS));
            return 42;
        });
        assertTrue(claimedDispatcher.submitted.await(1, TimeUnit.SECONDS));
        claimedDispatcher.releaseDispatch.countDown();
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

        assertFalse(claimed.cancel(false));
        releaseCallback.countDown();
        assertEquals(42, claimed.get(1, TimeUnit.SECONDS));
        assertTrue(claimedDispatcher.finished.await(1, TimeUnit.SECONDS));
    }

    @Test
    void schedulerDerivationFailsClosedWhenContextAndPlatformDisagree() {
        FoliaTaskManagerTest.RecordingSchedulers schedulers = new FoliaTaskManagerTest.RecordingSchedulers();
        new FoliaTaskManager(FoliaTaskManagerTest.plugin(), schedulers, schedulers);
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        FoliaQueueHandler handler = new FoliaQueueHandler(
                dispatcher,
                () -> false,
                () -> true,
                Duration.ofSeconds(1)
        );

        assertThrows(IllegalStateException.class, () -> handler.sync((Runnable) () -> {
        }));
        assertEquals(0, dispatcher.globalCalls);
    }

    @Test
    void contextCarryingRowsRouteToTheExactDispatcherTarget() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.executeTargets = true;
        FoliaQueueHandler handler = handler(
                dispatcher,
                targetBroker(dispatcher, new AtomicLong()),
                Duration.ofSeconds(1)
        );
        World world = proxy(World.class);
        Entity entity = proxy(Entity.class);
        ChunkTarget chunk = new ChunkTarget(world, 12, 34);
        EntityTarget entityTarget = new EntityTarget(entity);

        handler.syncOn(chunk, (RegionCall<Object>) ticket -> null).toCompletableFuture().join();
        assertEquals(Route.REGION_CALL, dispatcher.route);
        assertSame(world, dispatcher.world);
        assertEquals(12, dispatcher.chunkX);
        assertEquals(34, dispatcher.chunkZ);
        assertEquals(FoliaRegionDispatcher.TaskKind.FINALIZER, dispatcher.kind);

        handler.syncOn(chunk, (RegionTask) ticket -> {
        }).toCompletableFuture().join();
        assertEquals(Route.REGION_CALL, dispatcher.route);

        handler.syncOn(entityTarget, ticket -> {
        }).toCompletableFuture().join();
        assertEquals(Route.ENTITY, dispatcher.route);
        assertSame(entity, dispatcher.entity);
        assertEquals(FoliaRegionDispatcher.TaskKind.FINALIZER, dispatcher.kind);

        handler.syncOnGlobal(() -> {
        }).toCompletableFuture().join();
        assertEquals(Route.GLOBAL, dispatcher.route);
        assertEquals(FoliaRegionDispatcher.TaskKind.LEGACY_GLOBAL, dispatcher.kind);
    }

    @Test
    void unavailableTargetLaneFailsBeforeThePayloadRuns() {
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        dispatcher.executeTargets = true;
        FoliaTaskManagerTest.RecordingSchedulers schedulers = new FoliaTaskManagerTest.RecordingSchedulers();
        new FoliaTaskManager(FoliaTaskManagerTest.plugin(), schedulers, schedulers);
        FoliaQueueHandler handler = new FoliaQueueHandler(dispatcher, () -> false, Duration.ofSeconds(1));
        AtomicInteger sideEffects = new AtomicInteger();
        ChunkTarget target = new ChunkTarget(proxy(World.class), 4, 9);

        assertThrows(
                IllegalStateException.class,
                () -> handler.syncOn(target, (RegionTask) ticket -> sideEffects.incrementAndGet())
        );
        assertThrows(
                IllegalStateException.class,
                () -> handler.sync((Runnable) sideEffects::incrementAndGet)
        );

        assertEquals(0, sideEffects.get());
        assertEquals(0, dispatcher.globalCalls);
    }

    @Test
    void foliaBackendRejectsLegacyLocationFreeLiveStateBeforeCallersTouchIt() {
        Fixture fixture = fixture(false);

        assertFalse(fixture.handler.permitsLegacyLocationFreeLiveState());
        assertEquals(0, fixture.dispatcher.globalCalls);
    }

    @Test
    void queueConstructionDoesNotInstallTheLegacyGlobalDrain() {
        FoliaTaskManagerTest.RecordingSchedulers schedulers = new FoliaTaskManagerTest.RecordingSchedulers();
        FoliaTaskManager manager = new FoliaTaskManager(FoliaTaskManagerTest.plugin(), schedulers, schedulers);

        new FoliaQueueHandler(new RecordingDispatcher(), () -> false);

        assertFalse(schedulers.hasSubmission());
        assertEquals(0, manager.trackedTaskCount());
    }

    @Test
    void unsafeAndManualDrainEntryPointsFailBeforeSideEffects() {
        Fixture fixture = fixture(false);

        assertThrows(UnsupportedOperationException.class, () -> fixture.handler.startUnsafe(true));
        assertThrows(UnsupportedOperationException.class, () -> fixture.handler.endUnsafe(true));
        assertThrows(UnsupportedOperationException.class, fixture.handler::run);
        assertEquals(0, fixture.dispatcher.globalCalls);
    }

    private static Fixture fixture(boolean tickThread) {
        FoliaTaskManagerTest.RecordingSchedulers schedulers = new FoliaTaskManagerTest.RecordingSchedulers();
        new FoliaTaskManager(FoliaTaskManagerTest.plugin(), schedulers, schedulers);
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        FoliaCommitBroker broker = syncBroker(dispatcher, new AtomicLong());
        return new Fixture(
                new FoliaQueueHandler(dispatcher, broker, () -> tickThread, Duration.ofSeconds(5)),
                dispatcher
        );
    }

    private static Method method(String name, Class<?>... parameterTypes) throws Exception {
        return FoliaQueueHandler.class.getMethod(name, parameterTypes);
    }

    private static Method assertDescriptor(
            Class<?> declaringClass,
            String name,
            Class<?>... parameterTypes
    ) throws Exception {
        Method method = method(name, parameterTypes);
        assertSame(Future.class, method.getReturnType());
        assertSame(declaringClass, method.getDeclaringClass());
        return method;
    }

    private static FoliaQueueHandler handler(
            FoliaRegionDispatcher dispatcher,
            Duration ownerWaitTimeout
    ) {
        FoliaTaskManagerTest.RecordingSchedulers schedulers = new FoliaTaskManagerTest.RecordingSchedulers();
        new FoliaTaskManager(FoliaTaskManagerTest.plugin(), schedulers, schedulers);
        return new FoliaQueueHandler(
                dispatcher,
                syncBroker(dispatcher, new AtomicLong()),
                () -> false,
                ownerWaitTimeout
        );
    }

    private static FoliaQueueHandler handler(
            FoliaRegionDispatcher dispatcher,
            FoliaCommitBroker broker,
            Duration ownerWaitTimeout
    ) {
        FoliaTaskManagerTest.RecordingSchedulers schedulers = new FoliaTaskManagerTest.RecordingSchedulers();
        new FoliaTaskManager(FoliaTaskManagerTest.plugin(), schedulers, schedulers);
        return new FoliaQueueHandler(dispatcher, broker, () -> false, ownerWaitTimeout);
    }

    private static FoliaCommitBroker syncBroker(FoliaRegionDispatcher dispatcher, AtomicLong clock) {
        return new FoliaCommitBroker(
                dispatcher,
                null,
                null,
                FoliaCommitBroker.RegionObserver.unavailable(),
                FoliaCommitBroker.CommitAction.unavailable(),
                FoliaCommitBroker.SliceTuning.initial(),
                clock::get,
                () -> {
                },
                Runnable::run
        );
    }

    private static FoliaCommitBroker targetBroker(RecordingDispatcher dispatcher, AtomicLong clock) {
        RegionKey region = new RegionKey();
        FoliaCommitBroker.RegionObserver observer = new FoliaCommitBroker.RegionObserver() {
            @Override
            public Optional<RegionKey> lastRegionHint(
                    World world,
                    int chunkX,
                    int chunkZ
            ) {
                return Optional.of(region);
            }

            @Override
            public RegionKey currentRegion(World world) {
                return region;
            }

            @Override
            public RegionKey currentRegion(EntityTarget target) {
                return region;
            }

            @Override
            public boolean ownsChunk(World world, int chunkX, int chunkZ) {
                return true;
            }

            @Override
            public boolean ownsEntity(EntityTarget target) {
                return true;
            }
        };
        return new FoliaCommitBroker(
                dispatcher,
                null,
                null,
                observer,
                FoliaCommitBroker.CommitAction.unavailable(),
                FoliaCommitBroker.SliceTuning.initial(),
                clock::get,
                () -> {
                },
                Runnable::run
        );
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> null
        ));
    }

    private record Fixture(FoliaQueueHandler handler, RecordingDispatcher dispatcher) {
    }

    private enum Route {
        REGION_CALL,
        REGION_TASK,
        ENTITY,
        GLOBAL
    }

    private static final class RecordingDispatcher implements FoliaRegionDispatcher {

        private Route route;
        private World world;
        private int chunkX;
        private int chunkZ;
        private Entity entity;
        private TaskKind kind;
        private int globalCalls;
        private final List<TaskKind> globalKinds = new CopyOnWriteArrayList<>();
        private boolean deferGlobal;
        private boolean executeTargets;
        private boolean deferRegionTasks;
        private final ConcurrentLinkedQueue<DeferredGlobal> deferredGlobals = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<DeferredRegion> deferredRegions = new ConcurrentLinkedQueue<>();

        @Override
        public CompletionStage<Void> onRegion(
                World world,
                int cx,
                int cz,
                TaskKind kind,
                RegionTask task
        ) {
            recordRegion(world, cx, cz, kind);
            route = Route.REGION_TASK;
            if (deferRegionTasks) {
                CompletableFuture<Void> result = new CompletableFuture<>();
                deferredRegions.add(new DeferredRegion(task, result));
                return result;
            }
            if (executeTargets) {
                try {
                    task.run(null);
                } catch (Throwable failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletionStage<T> onRegion(
                World world,
                int cx,
                int cz,
                TaskKind kind,
                RegionCall<T> call
        ) {
            recordRegion(world, cx, cz, kind);
            route = Route.REGION_CALL;
            if (executeTargets) {
                try {
                    return CompletableFuture.completedFuture(call.call(null));
                } catch (Throwable failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> onEntity(Entity entity, TaskKind kind, EntityTask task) {
            this.route = Route.ENTITY;
            this.entity = entity;
            this.kind = kind;
            if (executeTargets) {
                try {
                    task.run(null);
                } catch (Throwable failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> onGlobal(TaskKind kind, GlobalTask task) {
            this.route = Route.GLOBAL;
            this.kind = kind;
            globalCalls++;
            globalKinds.add(kind);
            if (deferGlobal) {
                CompletableFuture<Void> result = new CompletableFuture<>();
                deferredGlobals.add(new DeferredGlobal(task, result));
                return result;
            }
            return executeGlobal(task);
        }

        private CompletionStage<Void> executeGlobal(GlobalTask task) {
            try {
                task.run();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        private void runDeferredGlobal() {
            DeferredGlobal deferred = deferredGlobals.remove();
            CompletionStage<Void> outcome = executeGlobal(deferred.task());
            outcome.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    deferred.result().complete(null);
                } else {
                    deferred.result().completeExceptionally(failure);
                }
            });
        }

        private void runAllDeferredGlobal() {
            while (!deferredGlobals.isEmpty()) {
                runDeferredGlobal();
            }
        }

        private int deferredGlobalCount() {
            return deferredGlobals.size();
        }

        private void runDeferredRegion() {
            DeferredRegion deferred = deferredRegions.remove();
            try {
                deferred.task().run(null);
                deferred.result().complete(null);
            } catch (Throwable failure) {
                deferred.result().completeExceptionally(failure);
            }
        }

        private void runAllDeferredRegions() {
            while (!deferredRegions.isEmpty()) {
                runDeferredRegion();
            }
        }

        private int deferredRegionCount() {
            return deferredRegions.size();
        }

        @Override
        public void stopAccepting(Throwable reason) {
        }

        @Override
        public CompletionStage<DrainReport> drain(Duration deadline) {
            return CompletableFuture.completedFuture(new DrainReport(0, 0, List.of()));
        }

        @Override
        public int liveTickets() {
            return 0;
        }

        @Override
        public int outstandingFutures() {
            return 0;
        }

        private void recordRegion(World world, int chunkX, int chunkZ, TaskKind kind) {
            this.world = world;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.kind = kind;
        }

        private record DeferredGlobal(GlobalTask task, CompletableFuture<Void> result) {
        }

        private record DeferredRegion(RegionTask task, CompletableFuture<Void> result) {
        }

    }

    private static final class AsyncDispatcher implements FoliaRegionDispatcher {

        private final CountDownLatch submitted = new CountDownLatch(1);
        private final CountDownLatch releaseDispatch = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public CompletionStage<Void> onGlobal(TaskKind kind, GlobalTask task) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                submitted.countDown();
                try {
                    releaseDispatch.await();
                    task.run();
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    finished.countDown();
                }
            });
            return result;
        }

        @Override
        public CompletionStage<Void> onRegion(
                World world,
                int cx,
                int cz,
                TaskKind kind,
                RegionTask task
        ) {
            throw new AssertionError("Unexpected region dispatch");
        }

        @Override
        public <T> CompletionStage<T> onRegion(
                World world,
                int cx,
                int cz,
                TaskKind kind,
                RegionCall<T> call
        ) {
            throw new AssertionError("Unexpected region dispatch");
        }

        @Override
        public CompletionStage<Void> onEntity(Entity entity, TaskKind kind, EntityTask task) {
            throw new AssertionError("Unexpected entity dispatch");
        }

        @Override
        public void stopAccepting(Throwable reason) {
        }

        @Override
        public CompletionStage<DrainReport> drain(Duration deadline) {
            return CompletableFuture.completedFuture(new DrainReport(0, 0, List.of()));
        }

        @Override
        public int liveTickets() {
            return 0;
        }

        @Override
        public int outstandingFutures() {
            return 0;
        }

    }

}
