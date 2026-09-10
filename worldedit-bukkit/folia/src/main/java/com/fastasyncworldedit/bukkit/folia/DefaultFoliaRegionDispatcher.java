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

import com.fastasyncworldedit.core.util.task.EntityTask;
import com.fastasyncworldedit.core.util.task.EntityTicket;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.fastasyncworldedit.core.util.task.GlobalTask;
import com.fastasyncworldedit.core.util.task.OperationCompletionService;
import com.fastasyncworldedit.core.util.task.RegionCall;
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

import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** Folia scheduler choke point for owner-bound callbacks and their scoped capabilities. */
final class DefaultFoliaRegionDispatcher implements FoliaRegionDispatcher {

    private final Plugin plugin;
    private final TicketAuthority ticketAuthority;
    private final OperationCompletionService completionService;
    private final FoliaTargetAdapter targetAdapter;
    private final RegionScheduler regionScheduler;
    private final GlobalRegionScheduler globalRegionScheduler;
    private final AsyncScheduler asyncScheduler;
    private final LongSupplier nanoTime;
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final AtomicLong submissionSequence = new AtomicLong();
    private final AtomicLong drainSequence = new AtomicLong();
    private final Runnable snapshotWorkProbe;
    private final AtomicReference<DrainSnapshot> diagnosticSnapshot;
    private final ConcurrentHashMap<MetricKey, MutableMetrics> metrics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DrainProducerKey, DrainWaiter> drainWaiters = new ConcurrentHashMap<>();

    private volatile Throwable stoppedReason;

    DefaultFoliaRegionDispatcher(
            Plugin plugin,
            TicketAuthority ticketAuthority,
            OperationCompletionService completionService,
            FoliaTargetAdapter targetAdapter
    ) {
        this(
                plugin,
                ticketAuthority,
                completionService,
                targetAdapter,
                Objects.requireNonNull(plugin, "plugin").getServer(),
                System::nanoTime,
                () -> {
                }
        );
    }

    DefaultFoliaRegionDispatcher(
            Plugin plugin,
            TicketAuthority ticketAuthority,
            OperationCompletionService completionService,
            FoliaTargetAdapter targetAdapter,
            Server server,
            LongSupplier nanoTime
    ) {
        this(plugin, ticketAuthority, completionService, targetAdapter, server, nanoTime, () -> {
        });
    }

    DefaultFoliaRegionDispatcher(
            Plugin plugin,
            TicketAuthority ticketAuthority,
            OperationCompletionService completionService,
            FoliaTargetAdapter targetAdapter,
            Server server,
            LongSupplier nanoTime,
            Runnable snapshotWorkProbe
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ticketAuthority = Objects.requireNonNull(ticketAuthority, "ticketAuthority");
        this.completionService = Objects.requireNonNull(completionService, "completionService");
        this.targetAdapter = Objects.requireNonNull(targetAdapter, "targetAdapter");
        Server checkedServer = Objects.requireNonNull(server, "server");
        this.regionScheduler = Objects.requireNonNull(checkedServer.getRegionScheduler(), "regionScheduler");
        this.globalRegionScheduler = Objects.requireNonNull(
                checkedServer.getGlobalRegionScheduler(),
                "globalRegionScheduler"
        );
        this.asyncScheduler = Objects.requireNonNull(checkedServer.getAsyncScheduler(), "asyncScheduler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.snapshotWorkProbe = Objects.requireNonNull(snapshotWorkProbe, "snapshotWorkProbe");
        this.diagnosticSnapshot = new AtomicReference<>(newSnapshot(
                0,
                0,
                PersistentLabelList.empty(this.snapshotWorkProbe)
        ));
    }

    @Override
    public CompletionStage<Void> onRegion(
            World world,
            int cx,
            int cz,
            TaskKind kind,
            RegionTask task
    ) {
        Objects.requireNonNull(task, "task");
        return onRegion(world, cx, cz, kind, ticket -> {
            task.run(ticket);
            return null;
        });
    }

    @Override
    public <T> CompletionStage<T> onRegion(
            World world,
            int cx,
            int cz,
            TaskKind kind,
            RegionCall<T> call
    ) {
        World checkedWorld = Objects.requireNonNull(world, "world");
        TaskKind checkedKind = Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(call, "call");
        String target = "region:" + checkedWorld.getName() + ':' + cx + ':' + cz;
        MutableMetrics metric = metric(target, checkedKind);
        Submission<T> submission = register(target, checkedKind, metric);
        if (!submission.accepted()) {
            reject(submission);
            return stage(submission);
        }
        try {
            if (FaweThreadContext.current().ownsChunk(checkedWorld, cx, cz)) {
                runRegion(submission, metric, checkedWorld, cx, cz, call);
            } else {
                FoliaWorldHandle targetWorld = Objects.requireNonNull(
                        targetAdapter.adapt(checkedWorld),
                        "adapted world"
                );
                regionScheduler.execute(
                        plugin,
                        targetWorld.world(),
                        cx,
                        cz,
                        () -> runRegion(submission, metric, checkedWorld, cx, cz, call)
                );
            }
        } catch (Throwable failure) {
            complete(submission, null, failure);
        }
        return stage(submission);
    }

    @Override
    public CompletionStage<Void> onEntity(Entity entity, TaskKind kind, EntityTask task) {
        Entity checkedEntity = Objects.requireNonNull(entity, "entity");
        TaskKind checkedKind = Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(task, "task");
        String target = "entity:" + checkedEntity.getClass().getName() + '@'
                + Integer.toHexString(System.identityHashCode(checkedEntity));
        MutableMetrics metric = metric(target, checkedKind);
        Submission<Void> submission = register(target, checkedKind, metric);
        if (!submission.accepted()) {
            reject(submission);
            return stage(submission);
        }
        try {
            if (FaweThreadContext.current().ownsEntity(checkedEntity)) {
                runEntity(submission, metric, checkedEntity, task);
            } else {
                FoliaEntityHandle targetEntity = Objects.requireNonNull(
                        targetAdapter.adapt(checkedEntity),
                        "adapted entity"
                );
                EntityScheduler scheduler = targetEntity.entity().getScheduler();
                Runnable retired = () -> complete(
                        submission,
                        null,
                        new RejectedExecutionException("Entity retired before its dispatcher callback")
                );
                if (!scheduler.execute(
                        plugin,
                        () -> runEntity(submission, metric, checkedEntity, task),
                        retired,
                        // EntityScheduler clamps all execute delays below one tick to one tick.
                        1L
                )) {
                    retired.run();
                }
            }
        } catch (Throwable failure) {
            complete(submission, null, failure);
        }
        return stage(submission);
    }

    @Override
    public CompletionStage<Void> onGlobal(TaskKind kind, GlobalTask task) {
        TaskKind checkedKind = Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(task, "task");
        MutableMetrics metric = metric("global", checkedKind);
        Submission<Void> submission = register("global", checkedKind, metric);
        if (!submission.accepted()) {
            reject(submission);
            return stage(submission);
        }
        try {
            globalRegionScheduler.execute(plugin, () -> runGlobal(submission, metric, task));
        } catch (Throwable failure) {
            complete(submission, null, failure);
        }
        return stage(submission);
    }

    @Override
    public void stopAccepting(Throwable reason) {
        Throwable checkedReason = Objects.requireNonNull(reason, "reason");
        lifecycleLock.lock();
        try {
            if (stoppedReason == null) {
                stoppedReason = checkedReason;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public CompletionStage<DrainReport> drain(Duration deadline) {
        Duration checkedDeadline = Objects.requireNonNull(deadline, "deadline");
        if (checkedDeadline.isNegative()) {
            throw new IllegalArgumentException("deadline must not be negative");
        }
        DrainProducerKey producerKey = new DrainProducerKey(nextDrainId());
        AtomicReference<DrainWaiter> waiterReference = new AtomicReference<>();
        OperationCompletionService.Producer producer;
        DrainWaiter waiter;
        synchronized (waiterReference) {
            producer = completionService.registerProducer(
                    producerKey,
                    ignored -> expireDrain(waiterReference),
                    failure -> failDrain(waiterReference.get(), failure)
            );
            waiter = new DrainWaiter(producerKey, producer);
            waiterReference.set(waiter);
            drainWaiters.put(producerKey, waiter);
        }
        if (checkedDeadline.isZero()) {
            requestFinishDrain(waiter);
        } else {
            Duration boundedDeadline = Duration.ofNanos(durationNanos(checkedDeadline));
            try {
                producer.schedule(() -> requestFinishDrain(waiter), boundedDeadline);
            } catch (Throwable failure) {
                requestFinishDrain(waiter);
            }
            try {
                ScheduledTask deadlineTask = asyncScheduler.runDelayed(
                        plugin,
                        ignored -> producer.execute(() -> requestFinishDrain(waiter)),
                        boundedDeadline.toNanos(),
                        TimeUnit.NANOSECONDS
                );
                waiter.deadlineTask = deadlineTask;
                if (deadlineTask != null && waiter.finished.get()) {
                    deadlineTask.cancel();
                }
            } catch (Throwable ignored) {
                // The completion-service timer remains the lifecycle-guaranteed deadline.
            }
            producer.execute(() -> {
                if (outstandingFutures() == 0) {
                    requestFinishDrain(waiter);
                }
            });
        }
        return completionService.isolateStage(waiter.future);
    }

    @Override
    public int liveTickets() {
        return diagnosticSnapshot.get().report.liveTickets();
    }

    @Override
    public int outstandingFutures() {
        return diagnosticSnapshot.get().report.unresolvedTasks();
    }

    List<DispatchMetrics> metricsSnapshot() {
        List<DispatchMetrics> snapshot = new ArrayList<>();
        metrics.forEach((key, value) -> snapshot.add(value.snapshot(key)));
        snapshot.sort(Comparator.comparing(DispatchMetrics::target).thenComparing(DispatchMetrics::kind));
        return List.copyOf(snapshot);
    }

    private <T> Submission<T> register(String target, TaskKind kind, MutableMetrics metric) {
        long submittedNanos = nanoTime.getAsLong();
        CompletableFuture<T> future = new CompletableFuture<>();
        lifecycleLock.lock();
        try {
            Throwable reason = stoppedReason;
            if (reason != null) {
                return new Submission<>(0, target, kind, submittedNanos, future, reason);
            }
            long id = nextSubmissionId();
            DrainSnapshot current = diagnosticSnapshot.get();
            PersistentLabelList labels = current.labels.addLabel(id, kind + " " + target + " #" + id);
            publishSnapshot(newSnapshot(current.unresolvedTasks + 1, current.liveTickets, labels));
            metric.submissions.increment();
            return new Submission<>(id, target, kind, submittedNanos, future, null);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private <T> void reject(Submission<T> submission) {
        RejectedExecutionException rejection = new RejectedExecutionException(
                "Folia dispatcher is no longer accepting " + submission.kind + " work",
                submission.rejection
        );
        submitCompletion(() -> submission.future.completeExceptionally(rejection));
    }

    private <T> void runRegion(
            Submission<T> submission,
            MutableMetrics metric,
            World world,
            int cx,
            int cz,
            RegionCall<T> call
    ) {
        if (!submission.claim()) {
            return;
        }
        recordScheduleDelay(submission, metric);
        RegionTicket ticket = null;
        T result = null;
        Throwable failure = null;
        try {
            ticket = ticketAuthority.mintRegion(world, cx, cz);
            ticketMinted(metric);
            long callbackStart = nanoTime.getAsLong();
            try {
                result = call.call(ticket);
            } catch (Throwable callbackFailure) {
                failure = callbackFailure;
            } finally {
                metric.recordRuntime(elapsed(callbackStart));
            }
        } catch (Throwable mintFailure) {
            failure = mintFailure;
        } finally {
            if (ticket != null) {
                try {
                    ticketAuthority.retire(ticket);
                    metric.ticketRetires.increment();
                } catch (Throwable retirementFailure) {
                    failure = combine(failure, retirementFailure);
                } finally {
                    if (!ticket.isLive()) {
                        ticketRetired();
                    }
                }
            }
        }
        completeClaimed(submission, result, failure);
    }

    private void runEntity(
            Submission<Void> submission,
            MutableMetrics metric,
            Entity entity,
            EntityTask task
    ) {
        if (!submission.claim()) {
            return;
        }
        recordScheduleDelay(submission, metric);
        EntityTicket ticket = null;
        Throwable failure = null;
        try {
            ticket = ticketAuthority.mintEntity(entity);
            ticketMinted(metric);
            long callbackStart = nanoTime.getAsLong();
            try {
                task.run(ticket);
            } catch (Throwable callbackFailure) {
                failure = callbackFailure;
            } finally {
                metric.recordRuntime(elapsed(callbackStart));
            }
        } catch (Throwable mintFailure) {
            failure = mintFailure;
        } finally {
            if (ticket != null) {
                try {
                    ticketAuthority.retire(ticket);
                    metric.ticketRetires.increment();
                } catch (Throwable retirementFailure) {
                    failure = combine(failure, retirementFailure);
                } finally {
                    if (!ticket.isLive()) {
                        ticketRetired();
                    }
                }
            }
        }
        completeClaimed(submission, null, failure);
    }

    private void runGlobal(Submission<Void> submission, MutableMetrics metric, GlobalTask task) {
        if (!submission.claim()) {
            return;
        }
        recordScheduleDelay(submission, metric);
        Throwable failure = null;
        long callbackStart = nanoTime.getAsLong();
        try {
            task.run();
        } catch (Throwable callbackFailure) {
            failure = callbackFailure;
        } finally {
            metric.recordRuntime(elapsed(callbackStart));
        }
        completeClaimed(submission, null, failure);
    }

    private void ticketMinted(MutableMetrics metric) {
        updateLiveTickets(1);
        metric.ticketMints.increment();
    }

    private void ticketRetired() {
        updateLiveTickets(-1);
    }

    private void updateLiveTickets(int delta) {
        lifecycleLock.lock();
        try {
            DrainSnapshot current = diagnosticSnapshot.get();
            publishSnapshot(newSnapshot(
                    current.unresolvedTasks,
                    Math.addExact(current.liveTickets, delta),
                    current.labels
            ));
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void recordScheduleDelay(Submission<?> submission, MutableMetrics metric) {
        metric.recordScheduleDelay(elapsed(submission.submittedNanos));
    }

    private <T> void complete(Submission<T> submission, T result, Throwable failure) {
        if (submission.claim()) {
            completeClaimed(submission, result, failure);
        }
    }

    private <T> void completeClaimed(Submission<T> submission, T result, Throwable failure) {
        submitCompletion(() -> {
            settle(submission);
            if (failure == null) {
                submission.future.complete(result);
            } else {
                submission.future.completeExceptionally(failure);
            }
        });
    }

    private void settle(Submission<?> submission) {
        if (submission.id == 0 || !submission.settled.compareAndSet(false, true)) {
            return;
        }
        int remaining;
        lifecycleLock.lock();
        try {
            DrainSnapshot current = diagnosticSnapshot.get();
            PersistentLabelList labels = current.labels.removeLabel(submission.id);
            remaining = current.unresolvedTasks - 1;
            publishSnapshot(newSnapshot(remaining, current.liveTickets, labels));
        } finally {
            lifecycleLock.unlock();
        }
        if (remaining == 0) {
            for (DrainWaiter waiter : drainWaiters.values()) {
                requestFinishDrain(waiter);
            }
        }
    }

    private void requestFinishDrain(DrainWaiter waiter) {
        if (waiter.finished.get() || !waiter.finishRequested.compareAndSet(false, true)) {
            return;
        }
        DrainReport report = diagnosticSnapshot.get().report;
        try {
            waiter.producer.complete(() -> finishDrain(waiter, report));
        } catch (RuntimeException failure) {
            failDrain(waiter, failure);
        }
    }

    private void expireDrain(AtomicReference<DrainWaiter> waiterReference) {
        synchronized (waiterReference) {
            requestFinishDrain(Objects.requireNonNull(waiterReference.get(), "drain waiter"));
        }
    }

    private void finishDrain(DrainWaiter waiter, DrainReport report) {
        drainWaiters.remove(waiter.key, waiter);
        ScheduledTask deadlineTask = waiter.deadlineTask;
        if (deadlineTask != null) {
            deadlineTask.cancel();
        }
        if (waiter.finished.compareAndSet(false, true)) {
            waiter.future.complete(report);
        }
    }

    private void failDrain(DrainWaiter waiter, Throwable failure) {
        Objects.requireNonNull(waiter, "waiter");
        Throwable terminalFailure = Objects.requireNonNull(failure, "failure");
        if (!waiter.finished.compareAndSet(false, true)) {
            return;
        }
        drainWaiters.remove(waiter.key, waiter);
        ScheduledTask deadlineTask = waiter.deadlineTask;
        try {
            if (deadlineTask != null) {
                deadlineTask.cancel();
            }
        } catch (Throwable cancellationFailure) {
            terminalFailure.addSuppressed(cancellationFailure);
        } finally {
            waiter.future.completeExceptionally(terminalFailure);
        }
    }

    private <T> CompletionStage<T> stage(Submission<T> submission) {
        return completionService.isolateStage(submission.future);
    }

    private void submitCompletion(Runnable transition) {
        if (FaweThreadContext.current().isTickThread()) {
            // A concurrent TERMINATED transition must not make the service run this inline tick-side.
            completionService.isolateStage(CompletableFuture.<Void>completedFuture(null))
                    .thenRun(() -> completionService.execute(transition));
            return;
        }
        completionService.execute(transition);
    }

    private MutableMetrics metric(String target, TaskKind kind) {
        return metrics.computeIfAbsent(new MetricKey(target, kind), ignored -> new MutableMetrics());
    }

    private DrainSnapshot newSnapshot(
            int unresolvedTasks,
            int liveTickets,
            PersistentLabelList unresolvedLabels
    ) {
        snapshotWorkProbe.run();
        if (unresolvedTasks < 0 || liveTickets < 0) {
            throw new IllegalStateException("Incoherent dispatcher drain counts");
        }
        if (unresolvedLabels.size() != unresolvedTasks) {
            throw new IllegalStateException("Dispatcher drain labels do not match unresolved count");
        }
        return new DrainSnapshot(
                unresolvedTasks,
                liveTickets,
                unresolvedLabels,
                new DrainReport(unresolvedTasks, liveTickets, unresolvedLabels)
        );
    }

    private void publishSnapshot(DrainSnapshot snapshot) {
        diagnosticSnapshot.set(snapshot);
    }

    private long nextSubmissionId() {
        long id = submissionSequence.incrementAndGet();
        if (id <= 0) {
            throw new IllegalStateException("Dispatcher submission sequence exhausted");
        }
        return id;
    }

    private long nextDrainId() {
        long id = drainSequence.incrementAndGet();
        if (id <= 0) {
            throw new IllegalStateException("Dispatcher drain sequence exhausted");
        }
        return id;
    }

    private long elapsed(long startNanos) {
        return Math.max(0, nanoTime.getAsLong() - startNanos);
    }

    private static long durationNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static Throwable combine(Throwable first, Throwable second) {
        if (first == null) {
            return second;
        }
        if (first != second) {
            first.addSuppressed(second);
        }
        return first;
    }

    record DispatchMetrics(
            String target,
            TaskKind kind,
            long submissions,
            long callbacks,
            long scheduleDelayNanos,
            long maxScheduleDelayNanos,
            long callbackRuntimeNanos,
            long maxCallbackRuntimeNanos,
            long ticketMints,
            long ticketRetires
    ) {
    }

    private record MetricKey(String target, TaskKind kind) {
    }

    private record DrainProducerKey(long drainId) {
    }

    private record DrainSnapshot(
            int unresolvedTasks,
            int liveTickets,
            PersistentLabelList labels,
            DrainReport report
    ) {
    }

    private static final class PersistentLabelList extends AbstractList<String> implements RandomAccess {

        private final LabelNode root;
        private final Runnable traversalProbe;

        private PersistentLabelList(LabelNode root, Runnable traversalProbe) {
            this.root = root;
            this.traversalProbe = traversalProbe;
        }

        private static PersistentLabelList empty(Runnable traversalProbe) {
            return new PersistentLabelList(null, traversalProbe);
        }

        private PersistentLabelList addLabel(long id, String label) {
            return new PersistentLabelList(insert(root, id, label), traversalProbe);
        }

        private PersistentLabelList removeLabel(long id) {
            LabelNode next = remove(root, id);
            if (size(next) != size() - 1) {
                throw new IllegalStateException("Missing dispatcher label " + id);
            }
            return new PersistentLabelList(next, traversalProbe);
        }

        @Override
        public String get(int index) {
            traversalProbe.run();
            Objects.checkIndex(index, size());
            LabelNode current = root;
            int remaining = index;
            while (current != null) {
                int leftSize = size(current.left);
                if (remaining < leftSize) {
                    current = current.left;
                } else if (remaining == leftSize) {
                    return current.label;
                } else {
                    remaining -= leftSize + 1;
                    current = current.right;
                }
            }
            throw new AssertionError("Label index traversal failed");
        }

        @Override
        public int size() {
            return size(root);
        }

        private static LabelNode insert(LabelNode node, long id, String label) {
            if (node == null) {
                return new LabelNode(id, label, null, null);
            }
            if (id < node.id) {
                return balance(new LabelNode(node.id, node.label, insert(node.left, id, label), node.right));
            }
            if (id > node.id) {
                return balance(new LabelNode(node.id, node.label, node.left, insert(node.right, id, label)));
            }
            throw new IllegalStateException("Duplicate dispatcher label " + id);
        }

        private static LabelNode remove(LabelNode node, long id) {
            if (node == null) {
                return null;
            }
            if (id < node.id) {
                LabelNode nextLeft = remove(node.left, id);
                if (nextLeft == node.left) {
                    return node;
                }
                return balance(new LabelNode(node.id, node.label, nextLeft, node.right));
            }
            if (id > node.id) {
                LabelNode nextRight = remove(node.right, id);
                if (nextRight == node.right) {
                    return node;
                }
                return balance(new LabelNode(node.id, node.label, node.left, nextRight));
            }
            if (node.left == null) {
                return node.right;
            }
            if (node.right == null) {
                return node.left;
            }
            LabelNode successor = minimum(node.right);
            return balance(new LabelNode(
                    successor.id,
                    successor.label,
                    node.left,
                    remove(node.right, successor.id)
            ));
        }

        private static LabelNode minimum(LabelNode node) {
            LabelNode current = node;
            while (current.left != null) {
                current = current.left;
            }
            return current;
        }

        private static LabelNode balance(LabelNode node) {
            int balance = height(node.left) - height(node.right);
            if (balance > 1) {
                LabelNode left = node.left;
                if (height(left.left) < height(left.right)) {
                    left = rotateLeft(left);
                }
                return rotateRight(new LabelNode(node.id, node.label, left, node.right));
            }
            if (balance < -1) {
                LabelNode right = node.right;
                if (height(right.right) < height(right.left)) {
                    right = rotateRight(right);
                }
                return rotateLeft(new LabelNode(node.id, node.label, node.left, right));
            }
            return node;
        }

        private static LabelNode rotateLeft(LabelNode node) {
            LabelNode pivot = node.right;
            LabelNode moved = new LabelNode(node.id, node.label, node.left, pivot.left);
            return new LabelNode(pivot.id, pivot.label, moved, pivot.right);
        }

        private static LabelNode rotateRight(LabelNode node) {
            LabelNode pivot = node.left;
            LabelNode moved = new LabelNode(node.id, node.label, pivot.right, node.right);
            return new LabelNode(pivot.id, pivot.label, pivot.left, moved);
        }

        private static int height(LabelNode node) {
            return node == null ? 0 : node.height;
        }

        private static int size(LabelNode node) {
            return node == null ? 0 : node.size;
        }

    }

    private static final class LabelNode {

        private final long id;
        private final String label;
        private final LabelNode left;
        private final LabelNode right;
        private final int height;
        private final int size;

        private LabelNode(long id, String label, LabelNode left, LabelNode right) {
            this.id = id;
            this.label = label;
            this.left = left;
            this.right = right;
            this.height = 1 + Math.max(PersistentLabelList.height(left), PersistentLabelList.height(right));
            this.size = 1 + PersistentLabelList.size(left) + PersistentLabelList.size(right);
        }

    }

    private static final class MutableMetrics {

        private final LongAdder submissions = new LongAdder();
        private final LongAdder callbacks = new LongAdder();
        private final LongAdder scheduleDelayNanos = new LongAdder();
        private final LongAccumulator maxScheduleDelayNanos = new LongAccumulator(Long::max, 0);
        private final LongAdder callbackRuntimeNanos = new LongAdder();
        private final LongAccumulator maxCallbackRuntimeNanos = new LongAccumulator(Long::max, 0);
        private final LongAdder ticketMints = new LongAdder();
        private final LongAdder ticketRetires = new LongAdder();

        private void recordScheduleDelay(long delayNanos) {
            callbacks.increment();
            scheduleDelayNanos.add(delayNanos);
            maxScheduleDelayNanos.accumulate(delayNanos);
        }

        private void recordRuntime(long runtimeNanos) {
            callbackRuntimeNanos.add(runtimeNanos);
            maxCallbackRuntimeNanos.accumulate(runtimeNanos);
        }

        private DispatchMetrics snapshot(MetricKey key) {
            return new DispatchMetrics(
                    key.target,
                    key.kind,
                    submissions.sum(),
                    callbacks.sum(),
                    scheduleDelayNanos.sum(),
                    maxScheduleDelayNanos.get(),
                    callbackRuntimeNanos.sum(),
                    maxCallbackRuntimeNanos.get(),
                    ticketMints.sum(),
                    ticketRetires.sum()
            );
        }

    }

    private static final class Submission<T> {

        private final long id;
        private final String target;
        private final TaskKind kind;
        private final long submittedNanos;
        private final CompletableFuture<T> future;
        private final Throwable rejection;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean settled = new AtomicBoolean();

        private Submission(
                long id,
                String target,
                TaskKind kind,
                long submittedNanos,
                CompletableFuture<T> future,
                Throwable rejection
        ) {
            this.id = id;
            this.target = target;
            this.kind = kind;
            this.submittedNanos = submittedNanos;
            this.future = future;
            this.rejection = rejection;
        }

        private boolean accepted() {
            return rejection == null;
        }

        private boolean claim() {
            return claimed.compareAndSet(false, true);
        }

    }

    private static final class DrainWaiter {

        private final DrainProducerKey key;
        private final OperationCompletionService.Producer producer;
        private final CompletableFuture<DrainReport> future = new CompletableFuture<>();
        private final AtomicBoolean finishRequested = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile ScheduledTask deadlineTask;

        private DrainWaiter(DrainProducerKey key, OperationCompletionService.Producer producer) {
            this.key = key;
            this.producer = producer;
        }

    }

}
