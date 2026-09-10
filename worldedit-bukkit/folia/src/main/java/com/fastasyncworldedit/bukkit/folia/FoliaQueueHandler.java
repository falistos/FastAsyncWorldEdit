package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.fastasyncworldedit.core.util.task.EntityTarget;
import com.fastasyncworldedit.core.util.task.EntityTask;
import com.fastasyncworldedit.core.util.task.GlobalTask;
import com.fastasyncworldedit.core.util.task.RegionCall;
import com.fastasyncworldedit.core.util.task.RegionTask;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** QueueHandler that routes Folia sync work through target lanes instead of a global drain tick. */
public final class FoliaQueueHandler extends QueueHandler {

    private static final Duration DEFAULT_OWNER_WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final String UNSAFE_MESSAGE =
            "Unsafe execution is not supported on Folia; live state must use its owning scheduler";

    private final FoliaCommitBroker broker;
    private final FoliaTickThreadGuard tickThreadGuard;
    private final long ownerWaitTimeoutNanos;

    public FoliaQueueHandler(FoliaRegionDispatcher dispatcher) {
        this(
                dispatcher,
                null,
                FoliaTickThreadGuard.production(),
                DEFAULT_OWNER_WAIT_TIMEOUT
        );
    }

    public FoliaQueueHandler(FoliaRegionDispatcher dispatcher, Duration ownerWaitTimeout) {
        this(
                dispatcher,
                null,
                FoliaTickThreadGuard.production(),
                ownerWaitTimeout
        );
    }

    FoliaQueueHandler(FoliaRegionDispatcher dispatcher, FoliaCommitBroker broker) {
        this(dispatcher, broker, FoliaTickThreadGuard.production(), DEFAULT_OWNER_WAIT_TIMEOUT);
    }

    FoliaQueueHandler(FoliaRegionDispatcher dispatcher, BooleanSupplier tickThread) {
        this(
                dispatcher,
                null,
                FoliaTickThreadGuard.testing(tickThread),
                DEFAULT_OWNER_WAIT_TIMEOUT
        );
    }

    FoliaQueueHandler(
            FoliaRegionDispatcher dispatcher,
            BooleanSupplier tickThread,
            Duration ownerWaitTimeout
    ) {
        this(
                dispatcher,
                null,
                FoliaTickThreadGuard.testing(tickThread),
                ownerWaitTimeout
        );
    }

    FoliaQueueHandler(
            FoliaRegionDispatcher dispatcher,
            FoliaCommitBroker broker,
            BooleanSupplier tickThread,
            Duration ownerWaitTimeout
    ) {
        this(dispatcher, broker, FoliaTickThreadGuard.testing(tickThread), ownerWaitTimeout);
    }

    FoliaQueueHandler(
            FoliaRegionDispatcher dispatcher,
            BooleanSupplier contextTickThread,
            BooleanSupplier platformTickThread,
            Duration ownerWaitTimeout
    ) {
        this(
                dispatcher,
                null,
                FoliaTickThreadGuard.testing(contextTickThread, platformTickThread),
                ownerWaitTimeout
        );
    }

    private FoliaQueueHandler(
            FoliaRegionDispatcher dispatcher,
            FoliaCommitBroker broker,
            FoliaTickThreadGuard tickThreadGuard,
            Duration ownerWaitTimeout
    ) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        this.broker = broker;
        this.tickThreadGuard = Objects.requireNonNull(tickThreadGuard, "tickThreadGuard");
        Duration checkedTimeout = Objects.requireNonNull(ownerWaitTimeout, "ownerWaitTimeout");
        if (checkedTimeout.isZero() || checkedTimeout.isNegative()) {
            throw new IllegalArgumentException("ownerWaitTimeout must be positive");
        }
        this.ownerWaitTimeoutNanos = checkedTimeout.toNanos();
    }

    @Override
    public void run() {
        throw new UnsupportedOperationException("The Folia global sync-drain tick is disabled");
    }

    @Override
    public <T> Future<T> sync(Runnable run) {
        return execute(Objects.requireNonNull(run, "run"), null);
    }

    @Override
    public <T> Future<T> sync(Callable<T> call) throws Exception {
        Callable<T> checkedCall = Objects.requireNonNull(call, "call");
        if (tickThreadGuard.isTickThread()) {
            return CompletableFuture.completedFuture(checkedCall.call());
        }
        return dispatchGlobal(checkedCall);
    }

    @Override
    public <T> Future<T> sync(Supplier<T> supplier) {
        Supplier<T> checkedSupplier = Objects.requireNonNull(supplier, "supplier");
        if (tickThreadGuard.isTickThread()) {
            return CompletableFuture.completedFuture(checkedSupplier.get());
        }
        return dispatchGlobal(checkedSupplier::get);
    }

    /** Folia note: off-tick work enters the global target lane after every queued normal sync. */
    @Override
    public <T> Future<T> syncWhenFree(Runnable run, T value) {
        return executeWhenFree(Objects.requireNonNull(run, "run"), value);
    }

    /** Folia note: off-tick work enters the global target lane after every queued normal sync. */
    @Override
    public <T> Future<T> syncWhenFree(Runnable run) {
        return executeWhenFree(Objects.requireNonNull(run, "run"), null);
    }

    /** Folia note: off-tick work enters the global target lane after every queued normal sync. */
    @Override
    public <T> Future<T> syncWhenFree(Callable<T> call) throws Exception {
        Callable<T> checkedCall = Objects.requireNonNull(call, "call");
        if (tickThreadGuard.isTickThread()) {
            return CompletableFuture.completedFuture(checkedCall.call());
        }
        return dispatchGlobalWhenFree(checkedCall);
    }

    /** Folia note: off-tick work enters the global target lane after every queued normal sync. */
    @Override
    public <T> Future<T> syncWhenFree(Supplier<T> supplier) {
        Supplier<T> checkedSupplier = Objects.requireNonNull(supplier, "supplier");
        if (tickThreadGuard.isTickThread()) {
            return CompletableFuture.completedFuture(checkedSupplier.get());
        }
        return dispatchGlobalWhenFree(checkedSupplier::get);
    }

    @Override
    protected <T> CompletionStage<T> syncOn(ChunkTarget target, RegionCall<T> call) {
        ChunkTarget checkedTarget = Objects.requireNonNull(target, "target");
        RegionCall<T> checkedCall = Objects.requireNonNull(call, "call");
        FoliaCommitBroker checkedBroker = broker();
        DispatchFuture<T> result = newDispatchFuture();
        CompletionStage<T> dispatched;
        try {
            dispatched = checkedBroker.scheduleSync(
                    checkedTarget,
                    FoliaCommitBroker.SyncPriority.NORMAL,
                    ticket -> {
                        if (!result.claimCallback()) {
                            return null;
                        }
                        try {
                            T value = checkedCall.call(ticket);
                            result.completeResult(value);
                            return value;
                        } catch (Throwable failure) {
                            result.completeFailure(failure);
                            throw propagate(failure);
                        }
                    }
            );
        } catch (Throwable failure) {
            result.completeFailure(failure);
            return result;
        }
        completeFrom(result, dispatched);
        return result;
    }

    @Override
    protected CompletionStage<Void> syncOn(ChunkTarget target, RegionTask task) {
        RegionTask checkedTask = Objects.requireNonNull(task, "task");
        return syncOn(target, ticket -> {
            checkedTask.run(ticket);
            return null;
        });
    }

    @Override
    protected CompletionStage<Void> syncOn(EntityTarget target, EntityTask task) {
        EntityTarget checkedTarget = Objects.requireNonNull(target, "target");
        EntityTask checkedTask = Objects.requireNonNull(task, "task");
        FoliaCommitBroker checkedBroker = broker();
        DispatchFuture<Void> result = newDispatchFuture();
        CompletionStage<Void> dispatched;
        try {
            dispatched = checkedBroker.scheduleSync(
                    checkedTarget,
                    FoliaCommitBroker.SyncPriority.NORMAL,
                    ticket -> {
                        if (!result.claimCallback()) {
                            return;
                        }
                        try {
                            checkedTask.run(ticket);
                            result.completeResult(null);
                        } catch (Throwable failure) {
                            result.completeFailure(failure);
                            throw propagate(failure);
                        }
                    }
            );
        } catch (Throwable failure) {
            result.completeFailure(failure);
            return result;
        }
        completeFrom(result, dispatched);
        return result;
    }

    @Override
    protected CompletionStage<Void> syncOnGlobal(GlobalTask task) {
        GlobalTask checkedTask = Objects.requireNonNull(task, "task");
        FoliaCommitBroker checkedBroker = broker();
        DispatchFuture<Void> result = newDispatchFuture();
        CompletionStage<Void> dispatched;
        try {
            dispatched = checkedBroker.scheduleSyncGlobal(
                    FoliaCommitBroker.SyncPriority.NORMAL,
                    () -> {
                        if (!result.claimCallback()) {
                            return;
                        }
                        try {
                            checkedTask.run();
                            result.completeResult(null);
                        } catch (Throwable failure) {
                            result.completeFailure(failure);
                            throw propagate(failure);
                        }
                    }
            );
        } catch (Throwable failure) {
            result.completeFailure(failure);
            return result;
        }
        completeFrom(result, dispatched);
        return result;
    }

    @Override
    protected boolean permitsLegacyLocationFreeLiveState() {
        return false;
    }

    @Override
    public void startUnsafe(boolean parallel) {
        throw new UnsupportedOperationException(UNSAFE_MESSAGE);
    }

    @Override
    public void endUnsafe(boolean parallel) {
        throw new UnsupportedOperationException(UNSAFE_MESSAGE);
    }

    private <T> Future<T> execute(Runnable runnable, T value) {
        if (tickThreadGuard.isTickThread()) {
            runnable.run();
            return CompletableFuture.completedFuture(value);
        }
        return dispatchGlobal(() -> {
            runnable.run();
            return value;
        });
    }

    private <T> Future<T> executeWhenFree(Runnable runnable, T value) {
        if (tickThreadGuard.isTickThread()) {
            runnable.run();
            return CompletableFuture.completedFuture(value);
        }
        return dispatchGlobalWhenFree(() -> {
            runnable.run();
            return value;
        });
    }

    private <T> Future<T> dispatchGlobal(Callable<T> callable) {
        FoliaCommitBroker checkedBroker = broker();
        DispatchFuture<T> result = newDispatchFuture();
        dispatchGlobal(checkedBroker, callable, FoliaCommitBroker.SyncPriority.NORMAL, result);
        return result;
    }

    private <T> Future<T> dispatchGlobalWhenFree(Callable<T> callable) {
        FoliaCommitBroker checkedBroker = broker();
        DispatchFuture<T> result = newDispatchFuture();
        dispatchGlobal(checkedBroker, callable, FoliaCommitBroker.SyncPriority.WHEN_FREE, result);
        return result;
    }

    private <T> void dispatchGlobal(
            FoliaCommitBroker checkedBroker,
            Callable<T> callable,
            FoliaCommitBroker.SyncPriority priority,
            DispatchFuture<T> result
    ) {
        AtomicReference<T> value = new AtomicReference<>();
        CompletionStage<Void> dispatched = checkedBroker.scheduleSyncGlobal(
                priority,
                () -> {
                    if (!result.claimCallback()) {
                        return;
                    }
                    try {
                        value.set(callable.call());
                    } catch (Throwable failure) {
                        throw new CallableDispatchException(failure);
                    }
                }
        );
        dispatched.whenComplete((ignored, failure) -> {
            if (failure == null) {
                result.completeResult(value.get());
            } else {
                result.completeFailure(unwrap(failure));
            }
        });
    }

    private FoliaCommitBroker broker() {
        if (broker == null) {
            throw new IllegalStateException("FoliaCommitBroker target lanes are not wired");
        }
        return broker;
    }

    private <T> DispatchFuture<T> newDispatchFuture() {
        DispatchFuture<T> result = new DispatchFuture<>();
        result.orTimeout(ownerWaitTimeoutNanos, TimeUnit.NANOSECONDS);
        return result;
    }

    private static <T> void completeFrom(DispatchFuture<T> result, CompletionStage<T> dispatched) {
        dispatched.whenComplete((value, failure) -> {
            if (failure == null) {
                result.completeResult(value);
            } else {
                result.completeFailure(unwrap(failure));
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable unwrapped = failure;
        while (unwrapped instanceof CompletionException && unwrapped.getCause() != null) {
            unwrapped = unwrapped.getCause();
        }
        if (unwrapped instanceof CallableDispatchException && unwrapped.getCause() != null) {
            return unwrapped.getCause();
        }
        return unwrapped;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new CompletionException(failure);
    }

    private static final class CallableDispatchException extends RuntimeException {

        private CallableDispatchException(Throwable cause) {
            super(cause);
        }

    }

    private static final class DispatchFuture<T> extends CompletableFuture<T> {

        private static final int PENDING = 0;
        private static final int RUNNING = 1;
        private static final int TERMINAL = 2;

        private final AtomicInteger state = new AtomicInteger(PENDING);

        private boolean claimCallback() {
            return state.compareAndSet(PENDING, RUNNING);
        }

        private void completeResult(T value) {
            complete(value);
        }

        private void completeFailure(Throwable failure) {
            completeExceptionally(failure);
        }

        @Override
        public boolean complete(T value) {
            return markTerminal() && super.complete(value);
        }

        @Override
        public boolean completeExceptionally(Throwable failure) {
            return markTerminal() && super.completeExceptionally(failure);
        }

        private boolean markTerminal() {
            int current;
            do {
                current = state.get();
                if (current == TERMINAL) {
                    return false;
                }
            } while (!state.compareAndSet(current, TERMINAL));
            return true;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!state.compareAndSet(PENDING, TERMINAL)) {
                return false;
            }
            return super.cancel(false);
        }

    }

}
