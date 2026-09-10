package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.task.RunnableVal;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** TaskManager implementation on Folia's global-region and asynchronous schedulers. */
@SuppressWarnings("removal")
public final class FoliaTaskManager extends TaskManager {

    static final int DEFAULT_MAX_TRACKED_TASKS = 65_536;
    private static final long MILLISECONDS_PER_TICK = 50L;
    private static final Duration DEFAULT_OWNER_WAIT_TIMEOUT = Duration.ofSeconds(60);

    private final Plugin plugin;
    private final GlobalRegionScheduler globalRegionScheduler;
    private final AsyncScheduler asyncScheduler;
    private final Map<Integer, TaskRegistration> tasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TaskRegistration> reapCandidates = new ConcurrentLinkedQueue<>();
    private final Semaphore trackingCapacity;
    private final int maxTrackedTasks;
    private final FoliaTickThreadGuard tickThreadGuard;
    private final long ownerWaitTimeoutNanos;
    private final AtomicInteger nextTaskId = new AtomicInteger(1);

    public FoliaTaskManager(Plugin plugin) {
        this(plugin, DEFAULT_OWNER_WAIT_TIMEOUT);
    }

    public FoliaTaskManager(Plugin plugin, Duration ownerWaitTimeout) {
        this(plugin, server(plugin), ownerWaitTimeout);
    }

    private FoliaTaskManager(Plugin plugin, Server server, Duration ownerWaitTimeout) {
        this(
                plugin,
                Objects.requireNonNull(server.getGlobalRegionScheduler(), "globalRegionScheduler"),
                Objects.requireNonNull(server.getAsyncScheduler(), "asyncScheduler"),
                DEFAULT_MAX_TRACKED_TASKS,
                FoliaTickThreadGuard.production(),
                ownerWaitTimeout
        );
    }

    FoliaTaskManager(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            AsyncScheduler asyncScheduler
    ) {
        this(
                plugin,
                globalRegionScheduler,
                asyncScheduler,
                DEFAULT_MAX_TRACKED_TASKS,
                () -> false,
                DEFAULT_OWNER_WAIT_TIMEOUT
        );
    }

    FoliaTaskManager(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            AsyncScheduler asyncScheduler,
            int maxTrackedTasks
    ) {
        this(
                plugin,
                globalRegionScheduler,
                asyncScheduler,
                maxTrackedTasks,
                FoliaTickThreadGuard.testing(() -> false),
                DEFAULT_OWNER_WAIT_TIMEOUT
        );
    }

    FoliaTaskManager(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            AsyncScheduler asyncScheduler,
            int maxTrackedTasks,
            BooleanSupplier tickThread,
            Duration ownerWaitTimeout
    ) {
        this(
                plugin,
                globalRegionScheduler,
                asyncScheduler,
                maxTrackedTasks,
                FoliaTickThreadGuard.testing(tickThread),
                ownerWaitTimeout
        );
    }

    FoliaTaskManager(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            AsyncScheduler asyncScheduler,
            int maxTrackedTasks,
            BooleanSupplier contextTickThread,
            BooleanSupplier platformTickThread,
            Duration ownerWaitTimeout
    ) {
        this(
                plugin,
                globalRegionScheduler,
                asyncScheduler,
                maxTrackedTasks,
                FoliaTickThreadGuard.testing(contextTickThread, platformTickThread),
                ownerWaitTimeout
        );
    }

    private FoliaTaskManager(
            Plugin plugin,
            GlobalRegionScheduler globalRegionScheduler,
            AsyncScheduler asyncScheduler,
            int maxTrackedTasks,
            FoliaTickThreadGuard tickThreadGuard,
            Duration ownerWaitTimeout
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.globalRegionScheduler = Objects.requireNonNull(globalRegionScheduler, "globalRegionScheduler");
        this.asyncScheduler = Objects.requireNonNull(asyncScheduler, "asyncScheduler");
        if (maxTrackedTasks <= 0) {
            throw new IllegalArgumentException("maxTrackedTasks must be positive");
        }
        this.maxTrackedTasks = maxTrackedTasks;
        this.tickThreadGuard = Objects.requireNonNull(tickThreadGuard, "tickThreadGuard");
        Duration checkedTimeout = Objects.requireNonNull(ownerWaitTimeout, "ownerWaitTimeout");
        if (checkedTimeout.isZero() || checkedTimeout.isNegative()) {
            throw new IllegalArgumentException("ownerWaitTimeout must be positive");
        }
        this.ownerWaitTimeoutNanos = checkedTimeout.toNanos();
        this.trackingCapacity = new Semaphore(maxTrackedTasks);
    }

    @Override
    public int repeat(@Nonnull Runnable runnable, int interval) {
        Runnable checkedRunnable = Objects.requireNonNull(runnable, "runnable");
        if (checkedRunnable instanceof FoliaQueueHandler) {
            // QueueHandler's superclass installs the legacy global sync-drain here. Folia routes each
            // sync submission directly, so registering that repeating drain is intentionally struck.
            return -1;
        }
        long periodTicks = repeatingPeriod(interval);
        return submit(
                checkedRunnable,
                true,
                callback -> globalRegionScheduler.runAtFixedRate(
                        plugin,
                        callback,
                        globalInitialDelay(interval),
                        periodTicks
                )
        );
    }

    @Override
    public int repeatAsync(@Nonnull Runnable runnable, int interval) {
        long periodMillis = ticksToMilliseconds(repeatingPeriod(interval));
        return submit(
                Objects.requireNonNull(runnable, "runnable"),
                true,
                callback -> asyncScheduler.runAtFixedRate(
                        plugin,
                        callback,
                        ticksToMilliseconds(Math.max(0L, interval)),
                        periodMillis,
                        TimeUnit.MILLISECONDS
                )
        );
    }

    @Override
    public void async(@Nonnull Runnable runnable) {
        submit(
                Objects.requireNonNull(runnable, "runnable"),
                false,
                callback -> asyncScheduler.runNow(plugin, callback)
        );
    }

    @Override
    public void task(@Nonnull Runnable runnable) {
        submit(
                Objects.requireNonNull(runnable, "runnable"),
                false,
                callback -> globalRegionScheduler.run(plugin, callback)
        );
    }

    @Override
    public void later(@Nonnull Runnable runnable, int delay) {
        Runnable checkedRunnable = Objects.requireNonNull(runnable, "runnable");
        submit(
                checkedRunnable,
                false,
                callback -> delay <= 0
                        ? globalRegionScheduler.run(plugin, callback)
                        : globalRegionScheduler.runDelayed(plugin, callback, delay)
        );
    }

    @Override
    public void laterAsync(@Nonnull Runnable runnable, int delay) {
        submit(
                Objects.requireNonNull(runnable, "runnable"),
                false,
                callback -> asyncScheduler.runDelayed(
                        plugin,
                        callback,
                        ticksToMilliseconds(Math.max(0L, delay)),
                        TimeUnit.MILLISECONDS
                )
        );
    }

    @Override
    public void cancel(int task) {
        if (task == -1) {
            return;
        }
        TaskRegistration registration = tasks.get(task);
        if (registration != null) {
            registration.cancel();
        }
    }

    @Override
    public void taskNowMain(@Nonnull Runnable runnable) {
        Runnable checkedRunnable = Objects.requireNonNull(runnable, "runnable");
        if (tickThreadGuard.isTickThread()) {
            checkedRunnable.run();
        } else {
            task(checkedRunnable);
        }
    }

    @Override
    public void taskNowAsync(@Nonnull Runnable runnable) {
        Runnable checkedRunnable = Objects.requireNonNull(runnable, "runnable");
        if (tickThreadGuard.isTickThread()) {
            async(checkedRunnable);
        } else {
            checkedRunnable.run();
        }
    }

    @Override
    public void taskSoonMain(@Nonnull Runnable runnable, boolean async) {
        Runnable checkedRunnable = Objects.requireNonNull(runnable, "runnable");
        if (async) {
            async(checkedRunnable);
        } else {
            taskNowMain(checkedRunnable);
        }
    }

    @Override
    public void taskWhenFree(@Nonnull Runnable runnable) {
        taskNowMain(runnable);
    }

    @Override
    public <T> T syncWhenFree(@Nonnull RunnableVal<T> function) {
        return syncWhenFree((Supplier<T>) Objects.requireNonNull(function, "function"));
    }

    @Override
    public <T> T syncWhenFree(@Nonnull Supplier<T> supplier) {
        return sync(supplier);
    }

    @Override
    public <T> T sync(@Nonnull RunnableVal<T> function) {
        return sync((Supplier<T>) Objects.requireNonNull(function, "function"));
    }

    @Override
    public <T> T sync(Supplier<T> supplier) {
        Supplier<T> checkedSupplier = Objects.requireNonNull(supplier, "supplier");
        if (tickThreadGuard.isTickThread()) {
            return checkedSupplier.get();
        }
        return awaitGlobal(checkedSupplier);
    }

    @Override
    @Deprecated(forRemoval = true, since = "2.7.0")
    public void parallel(Collection<Runnable> runnables) {
        requireNonTickWait("parallel");
        runParallel(runnables, getPublicForkJoinPool());
    }

    @Override
    @Deprecated(forRemoval = true, since = "2.7.0")
    public void parallel(Collection<Runnable> runnables, @Nullable Integer numThreads) {
        requireNonTickWait("parallel");
        if (runnables == null) {
            return;
        }
        int threads = numThreads == null ? Settings.settings().QUEUE.PARALLEL_THREADS : numThreads;
        if (threads <= 1) {
            runSequential(runnables);
            return;
        }
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            runParallel(runnables, pool);
        } finally {
            pool.shutdownNow();
        }
    }

    @Override
    @Deprecated(forRemoval = true, since = "2.7.0")
    public void wait(AtomicBoolean running, int timeout) {
        Objects.requireNonNull(running, "running");
        requireNonTickWait("wait");
        if (timeout <= 0) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        long deadline = saturatedAdd(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(timeout));
        synchronized (running) {
            while (running.get()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("Timed out waiting for the task notification");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(running, remaining);
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for the task notification", failure);
                }
            }
        }
    }

    int trackedTaskCount() {
        reapFinishedTasks();
        return tasks.size();
    }

    int taskId(ScheduledTask task) {
        for (TaskRegistration registration : tasks.values()) {
            if (registration.handle() == task) {
                return registration.id;
            }
        }
        return -1;
    }

    void cancelAll() {
        for (TaskRegistration registration : List.copyOf(tasks.values())) {
            registration.cancel();
        }
    }

    private int submit(Runnable runnable, boolean repeating, SchedulerSubmission submission) {
        return submit(runnable, repeating, submission, () -> {
        });
    }

    private int submit(
            Runnable runnable,
            boolean repeating,
            SchedulerSubmission submission,
            Runnable cancellationAction
    ) {
        TaskRegistration registration = register(repeating, cancellationAction);
        Consumer<ScheduledTask> callback = scheduledTask -> registration.execute(scheduledTask, runnable);
        try {
            ScheduledTask handle = Objects.requireNonNull(submission.submit(callback), "scheduled task");
            registration.attach(handle);
            registration.completeIfTerminal();
            return registration.id;
        } catch (RuntimeException | Error failure) {
            registration.complete();
            throw failure;
        }
    }

    private <T> T awaitGlobal(Supplier<T> supplier) {
        CompletableFuture<T> result = new CompletableFuture<>();
        int taskId = submit(
                () -> {
                    try {
                        result.complete(supplier.get());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                },
                false,
                callback -> globalRegionScheduler.run(plugin, callback),
                () -> result.cancel(false)
        );
        try {
            return result.get(ownerWaitTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
            cancel(taskId);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the global-region callback", failure);
        } catch (TimeoutException failure) {
            cancel(taskId);
            throw new IllegalStateException("Timed out waiting for the global-region callback", failure);
        } catch (ExecutionException failure) {
            throw propagate(failure.getCause());
        }
    }

    private void runParallel(Collection<Runnable> runnables, Executor executor) {
        Objects.requireNonNull(runnables, "runnables");
        List<FutureTask<Void>> futures = new ArrayList<>(runnables.size());
        for (Runnable runnable : runnables) {
            if (runnable != null) {
                FutureTask<Void> future = new FutureTask<>(runnable, null);
                futures.add(future);
                try {
                    executor.execute(future);
                } catch (RuntimeException | Error failure) {
                    cancelFutures(futures);
                    throw failure;
                }
            }
        }
        long deadline = saturatedAdd(System.nanoTime(), ownerWaitTimeoutNanos);
        List<Throwable> failures = new ArrayList<>();
        for (FutureTask<Void> future : futures) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                cancelFutures(futures);
                throw parallelWaitFailure("Timed out waiting for parallel tasks", new TimeoutException(), failures);
            }
            try {
                future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException failure) {
                cancelFutures(futures);
                Thread.currentThread().interrupt();
                throw parallelWaitFailure("Interrupted while waiting for parallel tasks", failure, failures);
            } catch (TimeoutException failure) {
                cancelFutures(futures);
                throw parallelWaitFailure("Timed out waiting for parallel tasks", failure, failures);
            } catch (ExecutionException failure) {
                failures.add(failure.getCause());
            } catch (CancellationException failure) {
                failures.add(failure);
            }
        }
        throwParallelFailures(failures);
    }

    private static void runSequential(Collection<Runnable> runnables) {
        List<Throwable> failures = new ArrayList<>();
        for (Runnable runnable : runnables) {
            if (runnable == null) {
                continue;
            }
            try {
                runnable.run();
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        throwParallelFailures(failures);
    }

    private static void cancelFutures(List<? extends FutureTask<?>> futures) {
        for (FutureTask<?> future : futures) {
            future.cancel(true);
        }
    }

    private static IllegalStateException parallelWaitFailure(
            String message,
            Throwable cause,
            List<Throwable> callbackFailures
    ) {
        IllegalStateException failure = new IllegalStateException(message, cause);
        callbackFailures.forEach(failure::addSuppressed);
        return failure;
    }

    private static void throwParallelFailures(List<Throwable> failures) {
        if (failures.isEmpty()) {
            return;
        }
        if (failures.size() == 1) {
            throw propagate(failures.getFirst());
        }
        IllegalStateException aggregate = new IllegalStateException(
                "Multiple parallel tasks failed (" + failures.size() + ')',
                failures.getFirst()
        );
        failures.stream().skip(1).forEach(aggregate::addSuppressed);
        throw aggregate;
    }

    private void requireNonTickWait(String operation) {
        if (tickThreadGuard.isTickThread()) {
            throw new IllegalStateException(operation + " must not block a Folia tick thread");
        }
    }

    private TaskRegistration register(boolean repeating, Runnable cancellationAction) {
        reapCandidates(1);
        if (!trackingCapacity.tryAcquire()) {
            // Keep saturation work constant on a tick caller while progressively finding
            // handles cancelled outside this bridge. Repeated pressure rotates the whole ring.
            reapCandidates(31);
            if (!trackingCapacity.tryAcquire()) {
                throw new RejectedExecutionException(
                        "Folia task handle limit reached (" + maxTrackedTasks + ')'
                );
            }
        }
        boolean registered = false;
        try {
            for (int attempt = 0; attempt <= maxTrackedTasks; attempt++) {
                int id = nextId();
                TaskRegistration registration = new TaskRegistration(id, repeating, cancellationAction);
                if (tasks.putIfAbsent(id, registration) == null) {
                    reapCandidates.add(registration);
                    registered = true;
                    return registration;
                }
            }
            throw new RejectedExecutionException("No free Folia task id is available");
        } finally {
            if (!registered) {
                trackingCapacity.release();
            }
        }
    }

    private void reapFinishedTasks() {
        for (TaskRegistration registration : tasks.values()) {
            ScheduledTask handle = registration.handle();
            if (handle == null) {
                continue;
            }
            registration.completeIfTerminal();
        }
    }

    private void reapCandidates(int limit) {
        for (int i = 0; i < limit; i++) {
            TaskRegistration registration = reapCandidates.poll();
            if (registration == null) {
                return;
            }
            if (tasks.get(registration.id) != registration) {
                continue;
            }
            registration.completeIfTerminal();
            if (tasks.get(registration.id) == registration) {
                reapCandidates.add(registration);
            }
        }
    }

    private int nextId() {
        return nextTaskId.getAndUpdate(current -> current == Integer.MAX_VALUE ? 1 : current + 1);
    }

    private static Server server(Plugin plugin) {
        return Objects.requireNonNull(Objects.requireNonNull(plugin, "plugin").getServer(), "server");
    }

    private static long globalInitialDelay(int interval) {
        // Bukkit normalizes a non-positive initial delay to the next scheduler turn. Folia's
        // global scheduler expresses that turn as one tick and rejects zero-delay submissions.
        return Math.max(1L, interval);
    }

    private static long repeatingPeriod(int interval) {
        // Bukkit normalizes a zero period to one tick. Negative repeat periods have no useful
        // TaskManager contract, so the Folia repeating surface applies the same one-tick floor.
        return Math.max(1L, interval);
    }

    private static long ticksToMilliseconds(long ticks) {
        return Math.multiplyExact(ticks, MILLISECONDS_PER_TICK);
    }

    private static long saturatedAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Global-region callback failed", failure);
    }

    @FunctionalInterface
    private interface SchedulerSubmission {

        ScheduledTask submit(Consumer<ScheduledTask> callback);

    }

    private final class TaskRegistration {

        private final int id;
        private final boolean repeating;
        private final Runnable cancellationAction;
        private final AtomicReference<ScheduledTask> handle = new AtomicReference<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();

        private TaskRegistration(int id, boolean repeating, Runnable cancellationAction) {
            this.id = id;
            this.repeating = repeating;
            this.cancellationAction = Objects.requireNonNull(cancellationAction, "cancellationAction");
        }

        private ScheduledTask handle() {
            return handle.get();
        }

        private void attach(ScheduledTask scheduledTask) {
            ScheduledTask checkedTask = Objects.requireNonNull(scheduledTask, "scheduledTask");
            ScheduledTask existing = handle.compareAndExchange(null, checkedTask);
            if (existing != null && existing != checkedTask) {
                throw new IllegalStateException("Scheduler returned a different handle for task " + id);
            }
            if (cancellationRequested.get()) {
                checkedTask.cancel();
            }
        }

        private void execute(ScheduledTask scheduledTask, Runnable runnable) {
            attach(scheduledTask);
            if (cancellationRequested.get()) {
                return;
            }
            try {
                runnable.run();
            } finally {
                // Folia logs callback failures and reschedules repeating tasks. Their registration
                // must remain live until explicit cancellation even when an invocation throws.
                if (!repeating) {
                    complete();
                }
            }
        }

        private void cancel() {
            try {
                if (cancellationRequested.compareAndSet(false, true)) {
                    cancellationAction.run();
                }
            } finally {
                complete();
                ScheduledTask scheduledTask = handle.get();
                if (scheduledTask != null) {
                    scheduledTask.cancel();
                }
            }
        }

        private void completeIfTerminal() {
            ScheduledTask scheduledTask = handle.get();
            if (scheduledTask == null) {
                return;
            }
            ScheduledTask.ExecutionState state = scheduledTask.getExecutionState();
            if (state == ScheduledTask.ExecutionState.FINISHED
                    || state == ScheduledTask.ExecutionState.CANCELLED
                    || state == ScheduledTask.ExecutionState.CANCELLED_RUNNING) {
                complete();
            }
        }

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                tasks.remove(id, this);
                trackingCapacity.release();
            }
        }

    }

}
