package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.task.ContextResolver;
import com.fastasyncworldedit.core.util.task.FawePlatformBackend;
import com.fastasyncworldedit.core.util.task.OperationCompletionService;
import com.fastasyncworldedit.core.util.task.TicketAuthority;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** ServiceLoader entry point for the complete Folia backend graph. */
public final class FoliaPlatformBackend implements FawePlatformBackend {

    private static final Duration SHUTDOWN_DRAIN_BUDGET = Duration.ofMillis(2_961);
    private static final int CONTINUATION_FINALIZER_RESERVE = 8;

    private final AtomicBoolean shutdown = new AtomicBoolean();
    private State state = State.NEW;
    private Plugin plugin;
    private TicketAuthority ticketAuthority;
    private FoliaThreadContext threadContext;
    private LiveFoliaTargetAdapter targetAdapter;
    private FoliaTaskManager taskManager;
    private OperationCompletionService completionService;
    private DefaultFoliaRegionDispatcher dispatcher;
    private DefaultFoliaBackpressure backpressure;
    private FoliaCommitBroker broker;
    private FoliaSnapshotCache snapshotCache;
    private FoliaQueueHandler queueHandler;

    @Override
    public synchronized void registerThreadContext() {
        requireState(State.NEW, "register the Folia thread context");
        targetAdapter = new LiveFoliaTargetAdapter();
        threadContext = new FoliaThreadContext(targetAdapter);
        ContextResolver.register(threadContext);
        ticketAuthority = TicketAuthority.issue();
        state = State.REGISTERED;
    }

    @Override
    public synchronized TaskManager createTaskManager(Object platform) {
        requireState(State.REGISTERED, "create the Folia task manager");
        if (!(platform instanceof Plugin plugin)) {
            throw new IllegalArgumentException("The Folia backend requires a Plugin platform object");
        }
        this.plugin = plugin;
        taskManager = new FoliaTaskManager(plugin);
        state = State.TASK_MANAGER_CREATED;
        return taskManager;
    }

    @Override
    public synchronized QueueHandler createQueueHandler() {
        if (state == State.QUEUE_CREATED) {
            return queueHandler;
        }
        requireState(State.TASK_MANAGER_CREATED, "create the Folia queue handler");
        completionService = new OperationCompletionService("FAWE Folia Completion");
        dispatcher = new DefaultFoliaRegionDispatcher(
                plugin,
                ticketAuthority,
                completionService,
                targetAdapter
        );
        LiveRegionObserver regionObserver = new LiveRegionObserver(threadContext, targetAdapter);
        DefaultFoliaBackpressure.AdmissionRejectionHandler rejectionHandler = FoliaCommitBroker.rejectionHook(
                completionService,
                (region, demand, failure) -> {
                }
        );
        backpressure = new DefaultFoliaBackpressure(
                DefaultFoliaBackpressure.initialLimits(Runtime.getRuntime().maxMemory()),
                CONTINUATION_FINALIZER_RESERVE,
                completionService,
                rejectionHandler
        );
        broker = new FoliaCommitBroker(
                dispatcher,
                backpressure,
                completionService,
                regionObserver,
                FoliaCommitBroker.CommitAction.unavailable(),
                FoliaCommitBroker.SliceTuning.initial(),
                System::nanoTime,
                () -> {
                },
                taskManager::async
        );
        snapshotCache = new FoliaSnapshotCache(dispatcher);
        queueHandler = new FoliaQueueHandler(dispatcher, broker);
        state = State.QUEUE_CREATED;
        return queueHandler;
    }

    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        ShutdownGraph graph;
        synchronized (this) {
            graph = new ShutdownGraph(taskManager, queueHandler, completionService, dispatcher, backpressure);
        }
        if (graph.completionService == null || graph.dispatcher == null || graph.backpressure == null) {
            shutdownExecutors(graph);
            return;
        }
        Thread.ofVirtual().name("FAWE Folia Shutdown").start(() -> shutdownGraph(graph));
    }

    private static void shutdownGraph(ShutdownGraph graph) {
        IllegalStateException reason = new IllegalStateException("FAWE Folia backend is shutting down");
        long deadlineNanos = saturatedAdd(System.nanoTime(), SHUTDOWN_DRAIN_BUDGET.toNanos());
        graph.backpressure.stopAccepting(reason);
        graph.dispatcher.stopAccepting(reason);
        CompletionStage<Void> drained;
        try {
            drained = graph.dispatcher.drain(remaining(deadlineNanos)).handle((report, failure) -> null);
        } catch (Throwable failure) {
            drained = CompletableFuture.completedFuture(null);
        }
        drained.thenCompose(ignored -> graph.completionService.flush(remaining(deadlineNanos)))
                .whenComplete((ignored, failure) -> shutdownExecutors(graph));
    }

    private void requireState(State expected, String action) {
        if (state != expected) {
            throw new IllegalStateException("Cannot " + action + " while backend state is " + state);
        }
    }

    private static Duration remaining(long deadlineNanos) {
        return Duration.ofNanos(Math.max(1L, deadlineNanos - System.nanoTime()));
    }

    private static long saturatedAdd(long first, long second) {
        return second > 0 && first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static void shutdownExecutors(ShutdownGraph graph) {
        if (graph.queueHandler != null) {
            graph.queueHandler.shutdownExecutors();
        }
        if (graph.taskManager != null) {
            graph.taskManager.cancelAll();
            graph.taskManager.getPublicForkJoinPool().shutdownNow();
        }
    }

    private enum State {
        NEW,
        REGISTERED,
        TASK_MANAGER_CREATED,
        QUEUE_CREATED
    }

    private record ShutdownGraph(
            FoliaTaskManager taskManager,
            FoliaQueueHandler queueHandler,
            OperationCompletionService completionService,
            DefaultFoliaRegionDispatcher dispatcher,
            DefaultFoliaBackpressure backpressure
    ) {
    }

}
