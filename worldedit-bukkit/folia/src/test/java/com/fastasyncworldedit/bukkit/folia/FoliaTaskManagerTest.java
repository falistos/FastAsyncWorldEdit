package com.fastasyncworldedit.bukkit.folia;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("removal")
class FoliaTaskManagerTest {

    @Test
    void routesEveryAbstractTaskManagerShapeToItsC4Scheduler() {
        Fixture fixture = fixture();

        int repeatId = fixture.manager.repeat(() -> {
        }, 3);
        assertRoute(fixture.schedulers, Route.GLOBAL_REPEAT, 3, 3, null);
        assertCancellable(fixture, repeatId);

        int repeatAsyncId = fixture.manager.repeatAsync(() -> {
        }, 4);
        assertRoute(fixture.schedulers, Route.ASYNC_REPEAT, 200, 200, TimeUnit.MILLISECONDS);
        assertCancellable(fixture, repeatAsyncId);

        fixture.manager.task(() -> {
        });
        assertRoute(fixture.schedulers, Route.GLOBAL_NOW, 0, 0, null);
        assertCancellable(fixture, fixture.manager.taskId(fixture.schedulers.lastTask));

        fixture.manager.later(() -> {
        }, 5);
        assertRoute(fixture.schedulers, Route.GLOBAL_LATER, 5, 0, null);
        assertCancellable(fixture, fixture.manager.taskId(fixture.schedulers.lastTask));

        fixture.manager.async(() -> {
        });
        assertRoute(fixture.schedulers, Route.ASYNC_NOW, 0, 0, null);
        assertCancellable(fixture, fixture.manager.taskId(fixture.schedulers.lastTask));

        fixture.manager.laterAsync(() -> {
        }, 6);
        assertRoute(fixture.schedulers, Route.ASYNC_LATER, 300, 0, TimeUnit.MILLISECONDS);
        assertCancellable(fixture, fixture.manager.taskId(fixture.schedulers.lastTask));
    }

    @Test
    void nonPositiveDelayUsesTheDocumentedSchedulerFloors() {
        Fixture fixture = fixture();

        fixture.manager.later(() -> {
        }, 0);
        assertRoute(fixture.schedulers, Route.GLOBAL_NOW, 0, 0, null);
        fixture.schedulers.fireLast();

        fixture.manager.laterAsync(() -> {
        }, -1);
        assertRoute(fixture.schedulers, Route.ASYNC_LATER, 0, 0, TimeUnit.MILLISECONDS);
        fixture.schedulers.fireLast();

        int globalRepeat = fixture.manager.repeat(() -> {
        }, 0);
        assertRoute(fixture.schedulers, Route.GLOBAL_REPEAT, 1, 1, null);
        fixture.manager.cancel(globalRepeat);

        int asyncRepeat = fixture.manager.repeatAsync(() -> {
        }, 0);
        assertRoute(fixture.schedulers, Route.ASYNC_REPEAT, 0, 50, TimeUnit.MILLISECONDS);
        fixture.manager.cancel(asyncRepeat);
    }

    @Test
    void oneShotCallbacksDrainAndFailedRepeatsStayTrackedUntilCancelled() {
        Fixture fixture = fixture();

        fixture.manager.task(() -> {
        });
        assertEquals(1, fixture.manager.trackedTaskCount());
        fixture.schedulers.fireLast();
        assertEquals(0, fixture.manager.trackedTaskCount());

        fixture.manager.async(() -> {
        });
        fixture.schedulers.fireLast();
        assertEquals(0, fixture.manager.trackedTaskCount());

        fixture.manager.later(() -> {
        }, 2);
        fixture.schedulers.fireLast();
        assertEquals(0, fixture.manager.trackedTaskCount());

        fixture.manager.laterAsync(() -> {
        }, 2);
        fixture.schedulers.fireLast();
        assertEquals(0, fixture.manager.trackedTaskCount());

        AtomicInteger repeatInvocations = new AtomicInteger();
        int repeatId = fixture.manager.repeat(() -> {
            repeatInvocations.incrementAndGet();
            throw new IllegalStateException("repeat failed");
        }, 1);

        fixture.schedulers.fireLast();
        assertEquals(1, repeatInvocations.get());
        assertEquals(ScheduledTask.ExecutionState.IDLE, fixture.schedulers.lastTask.getExecutionState());
        assertEquals(1, fixture.manager.trackedTaskCount());

        fixture.schedulers.fireLast();
        assertEquals(2, repeatInvocations.get());
        fixture.manager.cancel(repeatId);
        assertTrue(fixture.schedulers.lastTask.isCancelled());
        assertEquals(0, fixture.manager.trackedTaskCount());
    }

    @Test
    void synchronousSchedulerCallbackCannotReinsertACompletedHandle() {
        Fixture fixture = fixture();
        fixture.schedulers.fireBeforeReturn = true;

        fixture.manager.task(() -> {
        });

        assertEquals(0, fixture.manager.trackedTaskCount());
        assertEquals(ScheduledTask.ExecutionState.FINISHED, fixture.schedulers.lastTask.getExecutionState());
    }

    @Test
    void schedulerCancelledHandleDrainsAsSoonAsSubmissionReturns() {
        Fixture fixture = fixture();
        fixture.schedulers.cancelBeforeReturn = true;

        fixture.manager.task(() -> {
        });

        assertEquals(0, fixture.manager.trackedTaskCount());
        assertTrue(fixture.schedulers.lastTask.isCancelled());
    }

    @Test
    void handleMapIsBoundedAndCapacityReturnsAfterCancellation() {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        FoliaTaskManager manager = new FoliaTaskManager(plugin(), schedulers, schedulers, 1);
        int taskId = manager.repeat(() -> {
        }, 1);

        assertThrows(RejectedExecutionException.class, () -> manager.async(() -> {
        }));

        manager.cancel(taskId);
        manager.async(() -> {
        });
        assertEquals(1, manager.trackedTaskCount());
        schedulers.fireLast();
        assertEquals(0, manager.trackedTaskCount());
    }

    @Test
    void lifecycleCancellationDrainsEveryOutstandingHandle() {
        Fixture fixture = fixture();
        fixture.manager.repeat(() -> {
        }, 1);
        FakeScheduledTask repeating = fixture.schedulers.lastTask;
        fixture.manager.laterAsync(() -> {
        }, 20);
        FakeScheduledTask delayed = fixture.schedulers.lastTask;

        fixture.manager.cancelAll();

        assertTrue(repeating.isCancelled());
        assertTrue(delayed.isCancelled());
        assertEquals(0, fixture.manager.trackedTaskCount());
    }

    @Test
    void lifecycleCancellationReleasesPendingOwnerWaitImmediately() throws Exception {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        FoliaTaskManager manager = manager(schedulers, new AtomicBoolean(false), Duration.ofSeconds(5));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                manager.sync(() -> 1);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        assertTrue(schedulers.awaitSubmission());
        manager.cancelAll();
        caller.join(1_000);

        assertFalse(caller.isAlive());
        assertTrue(failure.get() instanceof CancellationException);
        assertEquals(0, manager.trackedTaskCount());
    }

    @Test
    void submissionChecksAtMostOneExistingHandleOnTheNormalPath() {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        FoliaTaskManager manager = new FoliaTaskManager(plugin(), schedulers, schedulers, 128);
        for (int i = 0; i < 64; i++) {
            manager.repeat(() -> {
            }, 1);
        }
        List<FakeScheduledTask> existing = List.copyOf(schedulers.tasks);
        existing.forEach(FakeScheduledTask::resetExecutionStateReads);

        manager.async(() -> {
        });

        assertTrue(existing.stream().mapToInt(FakeScheduledTask::executionStateReads).sum() <= 1);
        manager.cancelAll();
    }

    @Test
    void capacityPressureIncrementallyReapsExternallyCancelledHandles() {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        FoliaTaskManager manager = new FoliaTaskManager(plugin(), schedulers, schedulers, 2);
        manager.repeat(() -> {
        }, 1);
        manager.repeat(() -> {
        }, 1);
        schedulers.tasks.get(1).cancel();

        manager.async(() -> {
        });

        assertEquals(2, manager.trackedTaskCount());
        manager.cancelAll();
    }

    @Test
    void unknownAndStruckDrainIdsAreDeterministicNoOps() {
        Fixture fixture = fixture();

        fixture.manager.cancel(-1);
        fixture.manager.cancel(Integer.MAX_VALUE);

        assertEquals(0, fixture.manager.trackedTaskCount());
        assertNull(fixture.schedulers.route);
    }

    @Test
    void inheritedCurrentContextRowsStayInlineAndTickWaitsFailFast() {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        AtomicBoolean tickThread = new AtomicBoolean(true);
        FoliaTaskManager manager = manager(schedulers, tickThread, Duration.ofSeconds(1));
        AtomicIntegerCounter callbacks = new AtomicIntegerCounter();

        manager.taskNowMain(callbacks::increment);
        manager.taskSoonMain(callbacks::increment, false);
        manager.taskWhenFree(callbacks::increment);

        assertEquals(3, callbacks.value());
        assertFalse(schedulers.hasSubmission());
        assertThrows(IllegalStateException.class, () -> manager.parallel(List.of()));
        assertThrows(IllegalStateException.class, () -> manager.wait(new AtomicBoolean(true), 1));
        assertEquals(4, manager.sync(callbacks::increment));
        assertFalse(schedulers.hasSubmission());
    }

    @Test
    void inheritedWorkerSyncWaitIsBoundedAndCancellationPropagates() throws Exception {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        FoliaTaskManager manager = manager(schedulers, new AtomicBoolean(false), Duration.ofSeconds(1));
        AtomicReference<Integer> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                value.set(manager.sync(() -> 42));
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        assertTrue(schedulers.awaitSubmission());
        assertEquals(Route.GLOBAL_NOW, schedulers.route);
        schedulers.fireLast();
        caller.join();

        assertNull(failure.get());
        assertEquals(42, value.get());
        assertEquals(0, manager.trackedTaskCount());

        FoliaTaskManager timingOut = manager(
                new RecordingSchedulers(),
                new AtomicBoolean(false),
                Duration.ofMillis(1)
        );
        assertThrows(IllegalStateException.class, () -> timingOut.sync(() -> 1));
        assertEquals(0, timingOut.trackedTaskCount());
    }

    @Test
    void waitingPathsFailClosedWhenContextAndPlatformPredicatesDisagree() {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        FoliaTaskManager manager = new FoliaTaskManager(
                plugin(),
                schedulers,
                schedulers,
                FoliaTaskManager.DEFAULT_MAX_TRACKED_TASKS,
                () -> false,
                () -> true,
                Duration.ofSeconds(1)
        );

        assertThrows(IllegalStateException.class, () -> manager.sync(() -> 1));
        assertThrows(IllegalStateException.class, () -> manager.parallel(List.of()));
        assertFalse(schedulers.hasSubmission());
    }

    @Test
    void concurrentCancelBeforeAttachSkipsCallbackAndReleasesOnePermit() throws Exception {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        schedulers.blockReturn = new CountDownLatch(1);
        schedulers.fireOnWorker = true;
        FoliaTaskManager manager = new FoliaTaskManager(plugin(), schedulers, schedulers, 1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<Throwable> submissionFailure = new AtomicReference<>();
        Thread submitter = Thread.ofVirtual().start(() -> {
            try {
                manager.repeat(callbacks::incrementAndGet, 1);
            } catch (Throwable failure) {
                submissionFailure.set(failure);
            }
        });

        assertTrue(schedulers.awaitSubmission());
        manager.cancelAll();
        schedulers.releaseWorker.countDown();
        assertTrue(schedulers.workerFinished.await(1, TimeUnit.SECONDS));
        schedulers.blockReturn.countDown();
        submitter.join(1_000);

        assertFalse(submitter.isAlive());
        assertNull(submissionFailure.get());
        assertEquals(0, callbacks.get());
        assertTrue(schedulers.lastTask.isCancelled());
        assertEquals(0, manager.trackedTaskCount());

        int liveId = manager.repeat(() -> {
        }, 1);
        assertThrows(RejectedExecutionException.class, () -> manager.async(() -> {
        }));
        manager.cancel(liveId);
    }

    @Test
    void parallelAggregatesFailuresAndInterruptsRunningWorkOnTimeout() {
        FoliaTaskManager manager = manager(
                new RecordingSchedulers(),
                new AtomicBoolean(false),
                Duration.ofMillis(20)
        );
        IllegalStateException aggregate = assertThrows(
                IllegalStateException.class,
                () -> manager.parallel(List.of(
                        () -> {
                            throw new IllegalArgumentException("first");
                        },
                        () -> {
                            throw new IllegalStateException("second");
                        }
                ))
        );
        assertEquals("first", aggregate.getCause().getMessage());
        assertEquals(1, aggregate.getSuppressed().length);
        assertEquals("second", aggregate.getSuppressed()[0].getMessage());

        AtomicBoolean interrupted = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> manager.parallel(List.of(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
            }
            interrupted.set(true);
        })));
        long interruptDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!interrupted.get() && System.nanoTime() < interruptDeadline) {
            Thread.onSpinWait();
        }
        assertTrue(interrupted.get());
    }

    private static Fixture fixture() {
        RecordingSchedulers schedulers = new RecordingSchedulers();
        return new Fixture(new FoliaTaskManager(plugin(), schedulers, schedulers), schedulers);
    }

    static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(
                Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType())
        );
    }

    private static FoliaTaskManager manager(
            RecordingSchedulers schedulers,
            AtomicBoolean tickThread,
            Duration ownerWaitTimeout
    ) {
        return new FoliaTaskManager(
                plugin(),
                schedulers,
                schedulers,
                FoliaTaskManager.DEFAULT_MAX_TRACKED_TASKS,
                tickThread::get,
                ownerWaitTimeout
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    private static void assertCancellable(Fixture fixture, int taskId) {
        assertNotEquals(-1, taskId);
        FakeScheduledTask task = fixture.schedulers.lastTask;
        fixture.manager.cancel(taskId);
        assertTrue(task.isCancelled());
        assertEquals(0, fixture.manager.trackedTaskCount());
    }

    private static void assertRoute(
            RecordingSchedulers schedulers,
            Route route,
            long delay,
            long period,
            TimeUnit unit
    ) {
        assertEquals(route, schedulers.route);
        assertEquals(delay, schedulers.delay);
        assertEquals(period, schedulers.period);
        assertEquals(unit, schedulers.unit);
    }

    private record Fixture(FoliaTaskManager manager, RecordingSchedulers schedulers) {
    }

    private static final class AtomicIntegerCounter {

        private int value;

        private int increment() {
            return ++value;
        }

        private int value() {
            return value;
        }

    }

    private enum Route {
        GLOBAL_NOW,
        GLOBAL_LATER,
        GLOBAL_REPEAT,
        ASYNC_NOW,
        ASYNC_LATER,
        ASYNC_REPEAT
    }

    static final class RecordingSchedulers implements GlobalRegionScheduler, AsyncScheduler {

        private final CountDownLatch submissionRecorded = new CountDownLatch(1);
        private final CountDownLatch releaseWorker = new CountDownLatch(1);
        private final CountDownLatch workerFinished = new CountDownLatch(1);
        private final List<FakeScheduledTask> tasks = new CopyOnWriteArrayList<>();
        private volatile Route route;
        private volatile long delay;
        private volatile long period;
        private volatile TimeUnit unit;
        private volatile Consumer<ScheduledTask> callback;
        private volatile FakeScheduledTask lastTask;
        private volatile boolean fireBeforeReturn;
        private volatile boolean cancelBeforeReturn;
        private volatile boolean fireOnWorker;
        private volatile CountDownLatch blockReturn;

        @Override
        public void execute(Plugin plugin, Runnable run) {
            throw new AssertionError("FoliaTaskManager must retain a cancellable handle");
        }

        @Override
        public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) {
            return record(Route.GLOBAL_NOW, task, false, 0, 0, null);
        }

        @Override
        public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delayTicks) {
            return record(Route.GLOBAL_LATER, task, false, delayTicks, 0, null);
        }

        @Override
        public ScheduledTask runAtFixedRate(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long initialDelayTicks,
                long periodTicks
        ) {
            return record(Route.GLOBAL_REPEAT, task, true, initialDelayTicks, periodTicks, null);
        }

        @Override
        public ScheduledTask runNow(Plugin plugin, Consumer<ScheduledTask> task) {
            return record(Route.ASYNC_NOW, task, false, 0, 0, null);
        }

        @Override
        public ScheduledTask runDelayed(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long delay,
                TimeUnit unit
        ) {
            return record(Route.ASYNC_LATER, task, false, delay, 0, unit);
        }

        @Override
        public ScheduledTask runAtFixedRate(
                Plugin plugin,
                Consumer<ScheduledTask> task,
                long initialDelay,
                long period,
                TimeUnit unit
        ) {
            return record(Route.ASYNC_REPEAT, task, true, initialDelay, period, unit);
        }

        @Override
        public void cancelTasks(Plugin plugin) {
            if (lastTask != null) {
                lastTask.cancel();
            }
        }

        private FakeScheduledTask record(
                Route route,
                Consumer<ScheduledTask> callback,
                boolean repeating,
                long delay,
                long period,
                TimeUnit unit
        ) {
            this.route = route;
            this.callback = callback;
            this.lastTask = new FakeScheduledTask(repeating);
            tasks.add(lastTask);
            this.delay = delay;
            this.period = period;
            this.unit = unit;
            submissionRecorded.countDown();
            if (fireOnWorker) {
                Thread.ofVirtual().start(() -> {
                    try {
                        releaseWorker.await();
                        fireLast();
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                    } finally {
                        workerFinished.countDown();
                    }
                });
            }
            if (fireBeforeReturn) {
                fireLast();
            }
            if (cancelBeforeReturn) {
                lastTask.cancel();
            }
            CountDownLatch returnBlock = blockReturn;
            if (returnBlock != null) {
                try {
                    returnBlock.await();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while blocking scheduler return", failure);
                }
            }
            return lastTask;
        }

        private void fireLast() {
            lastTask.fire(callback);
        }

        boolean hasSubmission() {
            return route != null;
        }

        boolean awaitSubmission() throws InterruptedException {
            return submissionRecorded.await(1, TimeUnit.SECONDS);
        }

    }

    private static final class FakeScheduledTask implements ScheduledTask {

        private final boolean repeating;
        private final AtomicReference<ExecutionState> state = new AtomicReference<>(ExecutionState.IDLE);
        private final AtomicInteger executionStateReads = new AtomicInteger();

        private FakeScheduledTask(boolean repeating) {
            this.repeating = repeating;
        }

        @Override
        public Plugin getOwningPlugin() {
            return null;
        }

        @Override
        public boolean isRepeatingTask() {
            return repeating;
        }

        @Override
        public CancelledState cancel() {
            while (true) {
                ExecutionState current = state.get();
                if (current == ExecutionState.CANCELLED
                        || current == ExecutionState.CANCELLED_RUNNING) {
                    return CancelledState.CANCELLED_ALREADY;
                }
                ExecutionState cancelled = current == ExecutionState.RUNNING
                        ? ExecutionState.CANCELLED_RUNNING
                        : ExecutionState.CANCELLED;
                if (state.compareAndSet(current, cancelled)) {
                    return CancelledState.CANCELLED_BY_CALLER;
                }
            }
        }

        @Override
        public ExecutionState getExecutionState() {
            executionStateReads.incrementAndGet();
            return state.get();
        }

        private void fire(Consumer<ScheduledTask> callback) {
            if (!state.compareAndSet(ExecutionState.IDLE, ExecutionState.RUNNING)) {
                return;
            }
            try {
                callback.accept(this);
            } catch (Throwable ignored) {
                // Folia logs scheduler callback failures and still reschedules repeating tasks.
            } finally {
                state.compareAndSet(
                        ExecutionState.RUNNING,
                        repeating ? ExecutionState.IDLE : ExecutionState.FINISHED
                );
            }
        }

        private int executionStateReads() {
            return executionStateReads.get();
        }

        private void resetExecutionStateReads() {
            executionStateReads.set(0);
        }

    }

}
