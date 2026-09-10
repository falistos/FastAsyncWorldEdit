package com.fastasyncworldedit.core.util.task;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Backend-owned serialization and lifecycle service for operation completion state. */
public final class OperationCompletionService implements Executor {

    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final ReentrantLock serializationLock = new ReentrantLock(true);
    private final Set<Producer> producers = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<PlanProducerKey, PlanProducerToken> planProducers = new HashMap<>();
    private final Map<Long, AdmissionProducer<?>> admissionProducers = new HashMap<>();
    private final AtomicReference<Thread> completionThread = new AtomicReference<>();
    private final AtomicReference<Thread> lastOutcomePublicationThread = new AtomicReference<>();
    private final AtomicLong notificationSequence = new AtomicLong();
    private final AtomicLong boundarySequence = new AtomicLong();
    private final AtomicLong unconsumedPlanTokenDiagnostics = new AtomicLong();
    private final AtomicLong abandonedPlanTokenDiagnostics = new AtomicLong();
    private final AtomicLong unarmedAdmissionProducerDiagnostics = new AtomicLong();
    private final AtomicLong releasedProducers = new AtomicLong();
    private final AtomicReference<Throwable> lastUnconsumedPlanTokenFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> lastUnarmedAdmissionProducerFailure = new AtomicReference<>();
    private final ThreadLocal<Boolean> notificationContext = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Producer> flushExpiryContext = new ThreadLocal<>();
    private final ThreadLocal<PlanProducerToken> deferredPlanClaim = new ThreadLocal<>();
    private final BooleanSupplier tickThreadContext;
    private final Consumer<Runnable> notificationStarter;
    private final Runnable outcomeEntryProbe;
    private final Executor notificationExecutor;
    private final ExecutorService executor;
    private final ScheduledThreadPoolExecutor retryTimer;
    private final String threadName;
    private final CompletableFuture<Void> flushFuture = new CompletableFuture<>();
    private final CompletionStage<Void> flushView;

    private State state = State.ACCEPTING;
    private final AtomicLong queuedTransitions = new AtomicLong();
    private final AtomicLong pendingOutcomePublications = new AtomicLong();
    private volatile long flushDeadlineNanos = Long.MAX_VALUE;
    private boolean flushExpired;
    private ScheduledFuture<?> flushExpiryTask;

    public OperationCompletionService(String threadName) {
        this(threadName, OperationCompletionService::isCurrentTickThread, null);
    }

    OperationCompletionService(
            String threadName,
            BooleanSupplier tickThreadContext,
            Consumer<Runnable> notificationStarter
    ) {
        this(threadName, tickThreadContext, notificationStarter, () -> {
        });
    }

    OperationCompletionService(
            String threadName,
            BooleanSupplier tickThreadContext,
            Consumer<Runnable> notificationStarter,
            Runnable outcomeEntryProbe
    ) {
        this.threadName = Objects.requireNonNull(threadName, "threadName");
        if (threadName.isBlank()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        this.tickThreadContext = Objects.requireNonNull(tickThreadContext, "tickThreadContext");
        this.outcomeEntryProbe = Objects.requireNonNull(outcomeEntryProbe, "outcomeEntryProbe");
        this.notificationStarter = notificationStarter == null
                ? command -> Thread.ofVirtual()
                        .name(threadName + " Notification " + notificationSequence.incrementAndGet())
                        .start(command)
                : notificationStarter;
        this.notificationExecutor = this::startNotification;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            completionThread.set(thread);
            return thread;
        });
        this.retryTimer = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, threadName + " Retry Timer");
            thread.setDaemon(true);
            return thread;
        });
        this.retryTimer.setRemoveOnCancelPolicy(true);
        this.flushView = isolateStage(flushFuture);
    }

    /**
     * Registers an internal lifecycle producer while admission is open.
     *
     * <p>The expiry hook is bounded coordinator state work. It must not invoke user code or read live world state;
     * the service invokes it once at the published drain deadline and deregisters the producer afterwards.</p>
     */
    public Producer registerProducer(
            Object producerKey,
            FlushExpiryHook flushExpiryHook,
            Consumer<Throwable> transitionFailure
    ) {
        Objects.requireNonNull(producerKey, "producerKey");
        Objects.requireNonNull(flushExpiryHook, "flushExpiryHook");
        Objects.requireNonNull(transitionFailure, "transitionFailure");
        lifecycleLock.lock();
        try {
            if (state != State.ACCEPTING) {
                throw new RejectedExecutionException("Completion producer registration is closed: " + state);
            }
            return registerProducerLocked(producerKey, flushExpiryHook, transitionFailure);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Acquires a plan producer and its single-use registration capability before plan registration.
     */
    public Optional<PlanProducerToken> tryAcquirePlanProducer(
            UUID operationId,
            long chunkKey,
            long planSequence,
            FlushExpiryHook ownerFlushExpiry,
            Consumer<Throwable> transitionFailure
    ) {
        Objects.requireNonNull(operationId, "operationId");
        if (planSequence <= 0) {
            throw new IllegalArgumentException("planSequence must be positive");
        }
        Objects.requireNonNull(ownerFlushExpiry, "ownerFlushExpiry");
        Objects.requireNonNull(transitionFailure, "transitionFailure");
        PlanProducerKey key = new PlanProducerKey(operationId, chunkKey, planSequence);
        lifecycleLock.lock();
        try {
            if (state != State.ACCEPTING) {
                return Optional.empty();
            }
            if (planProducers.containsKey(key)) {
                throw new IllegalStateException("Plan already holds a producer token: " + key);
            }
            PlanProducerToken token = new PlanProducerToken(
                    key,
                    ownerFlushExpiry,
                    transitionFailure
            );
            planProducers.put(key, token);
            producers.add(token.producer);
            return Optional.of(token);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Acquires one asynchronous waiter producer and creates its isolated delivery lease atomically.
     */
    public <T> Optional<AdmissionProducer<T>> tryAcquireAdmissionProducer(
            long admissionAttemptId,
            PlanProducerToken registeredPlan
    ) {
        if (admissionAttemptId <= 0) {
            throw new IllegalArgumentException("admissionAttemptId must be positive");
        }
        Objects.requireNonNull(registeredPlan, "registeredPlan");
        lifecycleLock.lock();
        try {
            if (state != State.ACCEPTING) {
                return Optional.empty();
            }
            if (!registeredPlan.belongsTo(this) || !registeredPlan.acceptsAdmissionLocked()) {
                throw new IllegalStateException("Admission producer requires a consumed, live plan token");
            }
            if (admissionProducers.containsKey(admissionAttemptId)) {
                throw new IllegalStateException("Admission attempt already holds a producer: "
                        + admissionAttemptId);
            }
            AdmissionProducer<T> producer = new AdmissionProducer<>(admissionAttemptId, registeredPlan);
            admissionProducers.put(admissionAttemptId, producer);
            producers.add(producer.producer);
            return Optional.of(producer);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void execute(Runnable command) {
        submit(command);
    }

    /**
     * Submits counted internal completion work.
     *
     * <p>After termination only a non-tick asynchronous callback may use the inline fallback. Such a command must be
     * bounded internal state work: no user code, live-state access, or unbounded registration scan.</p>
     */
    public CompletionStage<Void> submit(Runnable command) {
        Objects.requireNonNull(command, "command");
        boolean runInline;
        lifecycleLock.lock();
        try {
            runInline = state == State.TERMINATED;
            if (!runInline) {
                return enqueue(command, null, false);
            }
        } finally {
            lifecycleLock.unlock();
        }
        if (tickThreadContext.getAsBoolean()) {
            throw new RejectedExecutionException("Tick threads cannot enter completion serialization after termination");
        }
        return runInline(command);
    }

    /**
     * Closes producer registration and flushes producers, transitions, and outcome publications by the deadline.
     * Consumer continuations are isolated and are not part of the flush fence.
     *
     * @param drainRemaining finite time remaining before the shutdown drain deadline
     */
    public CompletionStage<Void> flush(Duration drainRemaining) {
        Objects.requireNonNull(drainRemaining, "drainRemaining");
        if (drainRemaining.isZero() || drainRemaining.isNegative()) {
            throw new IllegalArgumentException("drainRemaining must be positive");
        }
        long remainingNanos = drainRemaining.toNanos();
        lifecycleLock.lock();
        try {
            if (state != State.ACCEPTING) {
                return flushView;
            }
            flushDeadlineNanos = saturatedAdd(System.nanoTime(), remainingNanos);
            state = State.FLUSHING;
            enqueue(() -> {
            }, null, false);
            for (Producer producer : List.copyOf(producers)) {
                producer.publishFlushDeadline(flushDeadlineNanos);
                for (Runnable hook : producer.takeFlushHooks()) {
                    enqueue(hook, producer, false);
                }
            }
            flushExpiryTask = retryTimer.schedule(
                    this::expireFlush,
                    Math.max(0, flushDeadlineNanos - System.nanoTime()),
                    TimeUnit.NANOSECONDS
            );
            return flushView;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public State state() {
        lifecycleLock.lock();
        try {
            return state;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public long registeredProducerCount() {
        lifecycleLock.lock();
        try {
            return producers.size();
        } finally {
            lifecycleLock.unlock();
        }
    }

    public long outstandingAdmissionProducerCount() {
        lifecycleLock.lock();
        try {
            return admissionProducers.size();
        } finally {
            lifecycleLock.unlock();
        }
    }

    public long unconsumedPlanTokenDiagnosticCount() {
        return unconsumedPlanTokenDiagnostics.get();
    }

    public long abandonedPlanTokenDiagnosticCount() {
        return abandonedPlanTokenDiagnostics.get();
    }

    public Optional<Throwable> lastUnconsumedPlanTokenFailure() {
        return Optional.ofNullable(lastUnconsumedPlanTokenFailure.get());
    }

    public long unarmedAdmissionProducerDiagnosticCount() {
        return unarmedAdmissionProducerDiagnostics.get();
    }

    public Optional<Throwable> lastUnarmedAdmissionProducerFailure() {
        return Optional.ofNullable(lastUnarmedAdmissionProducerFailure.get());
    }

    long releasedProducerCount() {
        return releasedProducers.get();
    }

    private void recordUnconsumedPlanToken(Throwable failure, boolean abandoned) {
        unconsumedPlanTokenDiagnostics.incrementAndGet();
        if (abandoned) {
            abandonedPlanTokenDiagnostics.incrementAndGet();
        }
        lastUnconsumedPlanTokenFailure.set(failure);
    }

    public long queuedTransitionCount() {
        return queuedTransitions.get();
    }

    public long pendingOutcomePublicationCount() {
        return pendingOutcomePublications.get();
    }

    boolean drainDeadlineExpired() {
        lifecycleLock.lock();
        try {
            return flushExpired;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public boolean isCompletionThread() {
        return Thread.currentThread() == completionThread.get();
    }

    public boolean isNotificationThread() {
        return notificationContext.get();
    }

    /**
     * Returns a read-only view whose continuations and CompletableFuture bridge publish on isolated notification
     * tasks. The supplied stage must not otherwise escape to consumers.
     */
    public <T> CompletionStage<T> isolateStage(CompletionStage<T> stage) {
        return new CompletionServiceStage<>(Objects.requireNonNull(stage, "stage"), this);
    }

    long scheduledTimerTaskCount() {
        return retryTimer.getQueue().size();
    }

    Thread lastOutcomePublicationThread() {
        return lastOutcomePublicationThread.get();
    }

    Thread completionThread() {
        return completionThread.get();
    }

    long effectiveDeadlineNanos(long requestedDeadlineNanos) {
        return Math.min(requestedDeadlineNanos, flushDeadlineNanos);
    }

    <T> void publishOutcome(CompletableFuture<T> target, T immutableOutcome) {
        publishOutcome(target, immutableOutcome, () -> {
        });
    }

    <T> void publishOutcome(
            CompletableFuture<T> target,
            T immutableOutcome,
            Runnable publicationReserved
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(publicationReserved, "publicationReserved");
        Publication publication;
        lifecycleLock.lock();
        try {
            if (state == State.TERMINATED) {
                throw new IllegalStateException("Cannot publish a new outcome after completion-service termination");
            }
            publication = reservePublication(new StageOutcome<>(immutableOutcome, null), target, null, true);
            publicationReserved.run();
        } finally {
            lifecycleLock.unlock();
        }
        startPublication(publication);
    }

    <T> void publishFailure(CompletableFuture<T> target, Throwable failure) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(failure, "failure");
        Publication publication;
        lifecycleLock.lock();
        try {
            if (state == State.TERMINATED) {
                throw new IllegalStateException("Cannot publish a new outcome after completion-service termination");
            }
            publication = reservePublication(new StageOutcome<>(null, failure), target, null, true);
        } finally {
            lifecycleLock.unlock();
        }
        startPublication(publication);
    }

    void executeBoundary(Runnable invocation) {
        Objects.requireNonNull(invocation, "invocation");
        Thread.ofVirtual()
                .name(threadName + " Boundary " + boundarySequence.incrementAndGet())
                .start(invocation);
    }

    Executor notificationExecutor() {
        return notificationExecutor;
    }

    Executor isolatedContinuationExecutor(Executor requested) {
        Objects.requireNonNull(requested, "executor");
        if (requested == this || requested == notificationExecutor) {
            return notificationExecutor;
        }
        return command -> notificationExecutor.execute(() -> {
            try {
                requested.execute(command);
            } catch (RejectedExecutionException ignored) {
                command.run();
            }
        });
    }

    private Producer registerProducerLocked(
            Object producerKey,
            FlushExpiryHook flushExpiryHook,
            Consumer<Throwable> transitionFailure
    ) {
        Producer producer = new Producer(
                producerKey,
                () -> {
                },
                flushExpiryHook,
                transitionFailure,
                true,
                true
        );
        producers.add(producer);
        return producer;
    }

    private CompletionStage<Void> enqueue(Runnable command, Producer producer, boolean completeProducer) {
        CompletableFuture<Void> submitted = new CompletableFuture<>();
        queuedTransitions.incrementAndGet();
        try {
            executor.execute(() -> runQueued(command, producer, completeProducer, submitted));
        } catch (RejectedExecutionException failure) {
            queuedTransitions.decrementAndGet();
            notificationExecutor.execute(() -> submitted.completeExceptionally(failure));
            throw failure;
        }
        return isolateStage(submitted);
    }

    private void enqueueCollaborator(Runnable command, Producer producer, boolean completeProducer) {
        queuedTransitions.incrementAndGet();
        try {
            executor.execute(() -> runQueued(command, producer, completeProducer, null));
        } catch (RejectedExecutionException failure) {
            queuedTransitions.decrementAndGet();
            throw failure;
        }
    }

    private void runQueued(
            Runnable command,
            Producer producer,
            boolean completeProducer,
            CompletableFuture<Void> submitted
    ) {
        boolean deregisterProducer = completeProducer;
        Throwable outcomeFailure = null;
        serializationLock.lock();
        try {
            if (producer != null && !producer.isRegistered()) {
                outcomeFailure = new IllegalStateException("Completion producer is no longer registered: "
                        + producer.producerKey);
            } else {
                command.run();
            }
        } catch (Throwable failure) {
            if (producer != null) {
                deregisterProducer = producer.releaseOnTransitionFailure;
                failure = producer.transitionFailed(failure);
            }
            outcomeFailure = failure;
        } finally {
            serializationLock.unlock();
        }
        try {
            if (submitted != null) {
                publishStageOutcome(submitted, new StageOutcome<>(null, outcomeFailure));
            }
        } finally {
            transitionFinished(producer, deregisterProducer);
        }
    }

    private CompletionStage<Void> runInline(Runnable command) {
        CompletableFuture<Void> submitted = new CompletableFuture<>();
        Throwable outcomeFailure = null;
        serializationLock.lock();
        try {
            command.run();
        } catch (Throwable failure) {
            outcomeFailure = failure;
        } finally {
            serializationLock.unlock();
        }
        StageOutcome<Void> outcome = new StageOutcome<>(null, outcomeFailure);
        notificationExecutor.execute(() -> outcome.publishTo(submitted));
        return isolateStage(submitted);
    }

    private <T> void publishStageOutcome(CompletableFuture<T> target, StageOutcome<T> outcome) {
        Publication publication;
        lifecycleLock.lock();
        try {
            if (state == State.TERMINATED) {
                throw new IllegalStateException("Counted transition reached publication after termination");
            }
            publication = reservePublication(outcome, target, null, false);
        } finally {
            lifecycleLock.unlock();
        }
        startPublication(publication);
    }

    private <T> Publication reservePublication(
            StageOutcome<T> outcome,
            CompletableFuture<T> target,
            AdmissionProducer<?> completedAdmission,
            boolean observableOutcome
    ) {
        pendingOutcomePublications.incrementAndGet();
        return new Publication(() -> {
            try {
                if (observableOutcome) {
                    lastOutcomePublicationThread.set(Thread.currentThread());
                }
                outcome.publishTo(target);
            } finally {
                publicationFinished(completedAdmission);
            }
        }, completedAdmission);
    }

    private void startPublication(Publication publication) {
        try {
            notificationExecutor.execute(publication.command);
        } catch (Throwable failure) {
            publicationStartFailed(publication.completedAdmission);
            throw failure;
        }
    }

    private void publicationStartFailed(AdmissionProducer<?> completedAdmission) {
        boolean terminated;
        lifecycleLock.lock();
        try {
            pendingOutcomePublications.decrementAndGet();
            if (completedAdmission != null) {
                completedAdmission.releaseAfterPublicationLocked();
            }
            terminated = terminateIfQuiescent();
        } finally {
            lifecycleLock.unlock();
        }
        if (terminated) {
            publishFlushOutcome();
        }
    }

    private void transitionFinished(Producer producer, boolean completeProducer) {
        boolean terminated;
        lifecycleLock.lock();
        try {
            queuedTransitions.decrementAndGet();
            if (completeProducer) {
                releaseProducerLocked(producer);
            }
            terminated = terminateIfQuiescent();
        } finally {
            lifecycleLock.unlock();
        }
        if (terminated) {
            publishFlushOutcome();
        }
    }

    private void publicationFinished(AdmissionProducer<?> completedAdmission) {
        boolean terminated;
        lifecycleLock.lock();
        try {
            pendingOutcomePublications.decrementAndGet();
            if (completedAdmission != null) {
                completedAdmission.releaseAfterPublicationLocked();
            }
            terminated = terminateIfQuiescent();
        } finally {
            lifecycleLock.unlock();
        }
        if (terminated) {
            publishFlushOutcome();
        }
    }

    private void expireFlush() {
        lifecycleLock.lock();
        try {
            if (state != State.FLUSHING || flushExpired) {
                return;
            }
            flushExpired = true;
            List<Producer> snapshot = List.copyOf(producers);
            // Unarmed admission expiry must reject its registered plan before plan drain terminalization competes.
            for (Producer producer : snapshot) {
                if (producer.producerKey instanceof AdmissionProducerKey) {
                    requestFlushExpiryLocked(producer);
                }
            }
            for (Producer producer : snapshot) {
                if (producer.producerKey instanceof AdmissionProducerKey) {
                    continue;
                }
                requestFlushExpiryLocked(producer);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void requestFlushExpiryLocked(Producer producer) {
        if (!producer.lifecycle.compareAndSet(Producer.OPEN, Producer.EXPIRING)) {
            return;
        }
        producer.reserveFlushExpiry();
        List<FlushExpiryHook> hooks = List.copyOf(producer.flushExpiryHooks);
        queuedTransitions.incrementAndGet();
        try {
            executor.execute(() -> runQueued(
                    () -> runFlushExpiry(producer, hooks),
                    producer,
                    producer.completeOnFlushExpiry,
                    null
            ));
        } catch (RejectedExecutionException failure) {
            queuedTransitions.decrementAndGet();
            throw failure;
        }
    }

    private void runFlushExpiry(Producer producer, List<FlushExpiryHook> hooks) {
        Throwable failure = null;
        flushExpiryContext.set(producer);
        try {
            for (FlushExpiryHook hook : hooks) {
                try {
                    hook.expire(producer.flushDeadlineNanos);
                } catch (Throwable hookFailure) {
                    if (failure == null) {
                        failure = hookFailure;
                    } else {
                        failure.addSuppressed(hookFailure);
                    }
                }
            }
        } finally {
            flushExpiryContext.remove();
        }
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Flush-expiry hook failed", failure);
        }
    }

    private boolean terminateIfQuiescent() {
        // Collaborator maps and the producer set are updated together under lifecycleLock.
        if (state != State.FLUSHING || !producers.isEmpty() || queuedTransitions.get() != 0
                || pendingOutcomePublications.get() != 0) {
            return false;
        }
        terminate();
        return true;
    }

    private void terminate() {
        state = State.TERMINATED;
        if (flushExpiryTask != null) {
            flushExpiryTask.cancel(false);
        }
        retryTimer.shutdownNow();
        executor.shutdown();
    }

    private void releaseProducerLocked(Producer producer) {
        producer.lifecycle.set(Producer.RELEASED);
        producers.remove(producer);
        planProducers.values().removeIf(token -> token.producer == producer);
        admissionProducers.values().removeIf(admission -> admission.producer == producer);
        releasedProducers.incrementAndGet();
    }

    private void publishFlushOutcome() {
        notificationExecutor.execute(() -> flushFuture.complete(null));
    }

    private void startNotification(Runnable command) {
        notificationStarter.accept(() -> {
            notificationContext.set(true);
            try {
                command.run();
            } finally {
                notificationContext.remove();
            }
        });
    }

    private static boolean isCurrentTickThread() {
        try {
            return FaweThreadContext.current().isTickThread();
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public enum State {
        ACCEPTING,
        FLUSHING,
        TERMINATED
    }

    /** Bounded idempotent terminalizer invoked once with the absolute flush deadline. */
    @FunctionalInterface
    public interface FlushExpiryHook {

        void expire(long deadlineNanos);

    }

    public final class Producer {

        private static final int OPEN = 0;
        private static final int COMPLETING = 1;
        private static final int EXPIRING = 2;
        private static final int RELEASED = 3;

        private final Object producerKey;
        private final Runnable flushExpiryReservation;
        private final List<FlushExpiryHook> flushExpiryHooks = new ArrayList<>();
        private final List<Runnable> flushHooks = new ArrayList<>();
        private final List<Consumer<Throwable>> transitionFailures = new ArrayList<>();
        private final boolean completeOnFlushExpiry;
        private final boolean releaseOnTransitionFailure;
        private final AtomicInteger lifecycle = new AtomicInteger(OPEN);
        private volatile long flushDeadlineNanos = Long.MAX_VALUE;

        private Producer(
                Object producerKey,
                Runnable flushExpiryReservation,
                FlushExpiryHook flushExpiryHook,
                Consumer<Throwable> transitionFailure,
                boolean completeOnFlushExpiry,
                boolean releaseOnTransitionFailure
        ) {
            this.producerKey = producerKey;
            this.flushExpiryReservation = flushExpiryReservation;
            this.flushExpiryHooks.add(flushExpiryHook);
            this.transitionFailures.add(transitionFailure);
            this.completeOnFlushExpiry = completeOnFlushExpiry;
            this.releaseOnTransitionFailure = releaseOnTransitionFailure;
        }

        /** Queues a coordinator transition without deregistering this producer. */
        public CompletionStage<Void> execute(Runnable transition) {
            Objects.requireNonNull(transition, "transition");
            if (flushExpiryContext.get() == this) {
                transition.run();
                return isolateStage(CompletableFuture.completedFuture(null));
            }
            lifecycleLock.lock();
            try {
                if (lifecycle.get() != OPEN || !producers.contains(this)) {
                    throw new IllegalStateException("Completion producer is completing or complete: " + producerKey);
                }
                return enqueue(transition, this, false);
            } finally {
                lifecycleLock.unlock();
            }
        }

        /** Deregisters only after the supplied coordinator transition executes. */
        public CompletionStage<Void> complete(Runnable transition) {
            Objects.requireNonNull(transition, "transition");
            if (flushExpiryContext.get() == this) {
                transition.run();
                return isolateStage(CompletableFuture.completedFuture(null));
            }
            lifecycleLock.lock();
            try {
                if (!producers.contains(this) || !lifecycle.compareAndSet(OPEN, COMPLETING)) {
                    throw new IllegalStateException("Completion producer is already complete: " + producerKey);
                }
                return enqueue(transition, this, true);
            } finally {
                lifecycleLock.unlock();
            }
        }

        /** Attempts idempotent completion, returning false when expiry or another completion already won. */
        public boolean tryComplete(Runnable transition) {
            Objects.requireNonNull(transition, "transition");
            if (flushExpiryContext.get() == this) {
                transition.run();
                return true;
            }
            if (!lifecycle.compareAndSet(OPEN, COMPLETING)) {
                return false;
            }
            try {
                enqueueCollaborator(transition, this, true);
                return true;
            } catch (RuntimeException | Error failure) {
                lifecycle.compareAndSet(COMPLETING, OPEN);
                throw failure;
            }
        }

        /** Runs the transition when completion flush begins, used to clamp settlement deadlines. */
        public void onFlushing(Runnable transition) {
            Objects.requireNonNull(transition, "transition");
            if (flushExpiryContext.get() == this) {
                transition.run();
                return;
            }
            lifecycleLock.lock();
            try {
                if (lifecycle.get() != OPEN || !producers.contains(this)) {
                    return;
                }
                if (state == State.ACCEPTING) {
                    flushHooks.add(transition);
                } else if (state == State.FLUSHING) {
                    enqueue(transition, this, false);
                }
            } finally {
                lifecycleLock.unlock();
            }
        }

        /** Schedules a detached timer which re-enters through this registered producer. */
        public ScheduledFuture<?> schedule(Runnable transition, Duration delay) {
            ScheduledFuture<?> scheduled = trySchedule(transition, delay);
            if (scheduled == null) {
                throw new IllegalStateException("Completion producer is already complete: " + producerKey);
            }
            return scheduled;
        }

        ScheduledFuture<?> trySchedule(Runnable transition, Duration delay) {
            Objects.requireNonNull(transition, "transition");
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must be non-negative");
            }
            long delayNanos = delay.toNanos();
            lifecycleLock.lock();
            try {
                if (lifecycle.get() != OPEN || !producers.contains(this)) {
                    return null;
                }
                return retryTimer.schedule(() -> execute(transition), delayNanos, TimeUnit.NANOSECONDS);
            } finally {
                lifecycleLock.unlock();
            }
        }

        long flushDeadlineNanos() {
            return flushDeadlineNanos;
        }

        private boolean isRegistered() {
            lifecycleLock.lock();
            try {
                return producers.contains(this);
            } finally {
                lifecycleLock.unlock();
            }
        }

        private Throwable transitionFailed(Throwable failure) {
            lifecycle.set(COMPLETING);
            for (Consumer<Throwable> transitionFailure : List.copyOf(transitionFailures)) {
                try {
                    transitionFailure.accept(failure);
                } catch (Throwable settlementFailure) {
                    failure.addSuppressed(settlementFailure);
                }
            }
            return failure;
        }

        private void publishFlushDeadline(long deadlineNanos) {
            flushDeadlineNanos = deadlineNanos;
        }

        private void reserveFlushExpiry() {
            flushExpiryReservation.run();
        }

        private List<Runnable> takeFlushHooks() {
            List<Runnable> hooks = List.copyOf(flushHooks);
            flushHooks.clear();
            return hooks;
        }

    }

    public final class PlanProducerToken {

        private final Object acquired = new Object();
        private final Object aborted = new Object();
        private final Object unconsumedExpiring = new Object();
        private final Object planExpired = new Object();
        private final PlanProducerKey key;
        private final FlushExpiryHook ownerFlushExpiry;
        private final Consumer<Throwable> ownerTransitionFailure;
        private final AtomicReference<Object> planState = new AtomicReference<>(acquired);
        private final Producer producer;

        private PlanProducerToken(
                PlanProducerKey key,
                FlushExpiryHook ownerFlushExpiry,
                Consumer<Throwable> ownerTransitionFailure
        ) {
            this.key = key;
            this.ownerFlushExpiry = ownerFlushExpiry;
            this.ownerTransitionFailure = ownerTransitionFailure;
            this.producer = new Producer(
                    key,
                    this::reserveFlushExpiry,
                    this::runFlushExpiry,
                    this::transitionFailed,
                    true,
                    true
            );
        }

        public UUID operationId() {
            return key.operationId();
        }

        public long chunkKey() {
            return key.chunkKey();
        }

        public long planSequence() {
            return key.planSequence();
        }

        /**
         * Releases an unconsumed token without registering or terminalizing a plan and without publishing an
         * outcome.
         *
         * @return true when this call changed ACQUIRED to ABORTED; false when abort or flush expiry already won
         * @throws IllegalStateException when registration already consumed this token
         */
        public boolean abort(Throwable cause) {
            Throwable checkedCause = Objects.requireNonNull(cause, "cause");
            boolean terminated;
            lifecycleLock.lock();
            try {
                Object current = planState.get();
                if (current != acquired) {
                    if (isConsumed(current)) {
                        throw new IllegalStateException("Registered plan producer token cannot be aborted: " + key);
                    }
                    return false;
                }
                if (!planState.compareAndSet(acquired, aborted)) {
                    Object winner = planState.get();
                    if (isConsumed(winner)) {
                        throw new IllegalStateException(
                                "Registered plan producer token cannot be aborted: " + key
                        );
                    }
                    return false;
                }
                recordUnconsumedPlanToken(checkedCause, false);
                releaseProducerLocked(producer);
                terminated = terminateIfQuiescent();
            } finally {
                lifecycleLock.unlock();
            }
            if (terminated) {
                publishFlushOutcome();
            }
            return true;
        }

        /** Queues the exact terminal record. True only when this call claims terminalization. */
        public boolean terminal(ChunkTerminalRecord record) {
            ChunkTerminalRecord checked = validateTerminalIdentity(record);
            while (true) {
                Object current = planState.get();
                PlanBinding binding;
                if (current instanceof PlanBinding registered) {
                    binding = registered;
                } else if (current instanceof PlanExpiring expiring
                        && (flushExpiryContext.get() == producer || deferredPlanClaim.get() == this)) {
                    binding = expiring.binding();
                } else if (current == acquired) {
                    throw new IllegalStateException("Plan producer token is not registered: " + key);
                } else {
                    return false;
                }
                PlanClaim claim = new PlanClaim(binding, checked, new AtomicBoolean());
                if (planState.compareAndSet(current, claim)) {
                    if (deferredPlanClaim.get() != this) {
                        enqueueCollaborator(() -> processClaim(claim), null, false);
                    }
                    return true;
                }
            }
        }

        /** Terminalizes as NOT_ACCEPTED with an empty receipt and the supplied cause. */
        public boolean rejectAdmission(Throwable cause) {
            Throwable checkedCause = Objects.requireNonNull(cause, "cause");
            return terminal(new ChunkTerminalRecord(
                    operationId(),
                    chunkKey(),
                    planSequence(),
                    TerminalStatus.NOT_ACCEPTED,
                    emptyReceipt(),
                    Optional.of(checkedCause)
            ));
        }

        boolean belongsTo(OperationCompletionService service) {
            return OperationCompletionService.this == service;
        }

        private boolean acceptsAdmissionLocked() {
            return planState.get() instanceof PlanBinding
                    && producer.lifecycle.get() == Producer.OPEN
                    && producers.contains(producer);
        }

        Producer consumeRegistration(
                Consumer<ChunkTerminalRecord> terminalTransition,
                FlushExpiryHook coordinatorFlushExpiry,
                Consumer<Throwable> coordinatorTransitionFailure
        ) {
            PlanBinding binding = new PlanBinding(
                    Objects.requireNonNull(terminalTransition, "terminalTransition"),
                    Objects.requireNonNull(coordinatorFlushExpiry, "coordinatorFlushExpiry"),
                    Objects.requireNonNull(coordinatorTransitionFailure, "coordinatorTransitionFailure")
            );
            if (!planState.compareAndSet(acquired, binding)) {
                throw new IllegalStateException("Plan producer registration capability is unavailable: " + key);
            }
            return producer;
        }

        Producer registrationProducer() {
            return producer;
        }

        ChunkTerminalRecord claimedRecord() {
            Object current = planState.get();
            return current instanceof PlanClaim claim ? claim.record() : null;
        }

        private void reserveFlushExpiry() {
            while (true) {
                Object current = planState.get();
                if (current == acquired) {
                    if (planState.compareAndSet(current, unconsumedExpiring)) {
                        return;
                    }
                } else if (current instanceof PlanBinding binding) {
                    if (planState.compareAndSet(current, new PlanExpiring(binding))) {
                        return;
                    }
                } else {
                    return;
                }
            }
        }

        private void runFlushExpiry(long deadlineNanos) {
            Object current = planState.get();
            if (current == unconsumedExpiring) {
                TimeoutException abandonment = new TimeoutException(
                        "Unconsumed plan producer token " + key + " drain deadline expired at " + deadlineNanos
                );
                recordUnconsumedPlanToken(abandonment, true);
                planState.compareAndSet(unconsumedExpiring, aborted);
                return;
            }
            if (current == aborted || current == planExpired) {
                return;
            }
            if (current instanceof PlanExpiring expiring) {
                Throwable failure = null;
                try {
                    ownerFlushExpiry.expire(deadlineNanos);
                } catch (Throwable ownerFailure) {
                    failure = ownerFailure;
                }
                processCurrentClaim();
                try {
                    expiring.binding().coordinatorFlushExpiry().expire(deadlineNanos);
                } catch (Throwable coordinatorFailure) {
                    if (failure == null) {
                        failure = coordinatorFailure;
                    } else {
                        failure.addSuppressed(coordinatorFailure);
                    }
                }
                planState.compareAndSet(expiring, planExpired);
                rethrow(failure);
                return;
            }
            if (current instanceof PlanClaim claim) {
                processClaim(claim);
                claim.binding().coordinatorFlushExpiry().expire(deadlineNanos);
            }
        }

        private void processCurrentClaim() {
            Object current = planState.get();
            if (current instanceof PlanClaim claim) {
                processClaim(claim);
            }
        }

        private void processClaim(PlanClaim claim) {
            if (!claim.processed().compareAndSet(false, true)) {
                return;
            }
            try {
                claim.binding().terminalTransition().accept(claim.record());
            } catch (Throwable failure) {
                transitionFailed(failure);
                producer.tryComplete(() -> {
                });
            }
        }

        private void transitionFailed(Throwable failure) {
            Throwable reported = failure;
            try {
                ownerTransitionFailure.accept(reported);
            } catch (Throwable ownerFailure) {
                reported.addSuppressed(ownerFailure);
            }
            PlanBinding binding = binding();
            if (binding != null) {
                try {
                    binding.coordinatorTransitionFailure().accept(reported);
                } catch (Throwable coordinatorFailure) {
                    reported.addSuppressed(coordinatorFailure);
                }
            }
        }

        private PlanBinding binding() {
            Object current = planState.get();
            if (current instanceof PlanBinding binding) {
                return binding;
            }
            if (current instanceof PlanExpiring expiring) {
                return expiring.binding();
            }
            if (current instanceof PlanClaim claim) {
                return claim.binding();
            }
            return null;
        }

        private boolean isConsumed(Object state) {
            return state instanceof PlanBinding
                    || state instanceof PlanExpiring
                    || state instanceof PlanClaim
                    || state == planExpired;
        }

        private ChunkTerminalRecord validateTerminalIdentity(ChunkTerminalRecord record) {
            ChunkTerminalRecord checked = Objects.requireNonNull(record, "record");
            if (!operationId().equals(checked.operationId())
                    || chunkKey() != checked.chunkKey()
                    || planSequence() != checked.planSequence()) {
                throw new IllegalArgumentException("Terminal record identity does not match plan producer token");
            }
            return checked;
        }

    }

    public final class AdmissionProducer<T> {

        private final Object expiredBeforeArm = new Object();
        private final Object releasedArm = new Object();
        private final long admissionAttemptId;
        private final PlanProducerToken registeredPlan;
        private final AtomicReference<Object> armState = new AtomicReference<>();
        private final AtomicReference<AdmissionCommandNode> commandTail;
        private final AtomicBoolean drainScheduled = new AtomicBoolean();
        private final AtomicBoolean timerScheduled = new AtomicBoolean();
        private final AtomicBoolean outcomeReserved = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final AdmissionCommandNode closedMarker = new AdmissionCommandNode(null);
        private final AdmissionDeliveryLease<T> delivery = new AdmissionDeliveryLease<>(this);
        private final Producer producer;
        private volatile AdmissionCommandNode commandHead;
        private volatile ScheduledFuture<?> deadlineTask;
        private volatile ScheduledFuture<?> drainDeadlineTask;
        private volatile AdmissionDeadline deadline;

        private AdmissionProducer(long admissionAttemptId, PlanProducerToken registeredPlan) {
            this.admissionAttemptId = admissionAttemptId;
            this.registeredPlan = registeredPlan;
            AdmissionCommandNode head = new AdmissionCommandNode(null);
            this.commandHead = head;
            this.commandTail = new AtomicReference<>(head);
            this.producer = new Producer(
                    new AdmissionProducerKey(admissionAttemptId),
                    this::reserveFlushExpiry,
                    this::runFlushExpiry,
                    this::transitionFailed,
                    false,
                    false
            );
            // Installed before registration so schedule() never needs lifecycleLock to gain the flush clamp.
            this.producer.flushHooks.add(this::clampDeadlineToFlush);
        }

        public AdmissionDeliveryLease<T> delivery() {
            return delivery;
        }

        /** Installs waiter settlement exactly once before accounting begins. */
        public boolean arm(
                FlushExpiryHook waiterFlushExpiry,
                Consumer<Throwable> transitionFailure
        ) {
            AdmissionArm arm = new AdmissionArm(
                    Objects.requireNonNull(waiterFlushExpiry, "waiterFlushExpiry"),
                    Objects.requireNonNull(transitionFailure, "transitionFailure")
            );
            while (true) {
                Object current = armState.get();
                if (current == expiredBeforeArm) {
                    return false;
                }
                if (commandTail.get() == closedMarker) {
                    throw new IllegalStateException("Admission producer is already settled: " + admissionAttemptId);
                }
                if (current != null) {
                    throw new IllegalStateException("Admission producer is already armed: " + admissionAttemptId);
                }
                if (armState.compareAndSet(null, arm)) {
                    return true;
                }
            }
        }

        /** O(1), nonblocking submission to the serialized control path. */
        public boolean submit(Runnable transition) {
            Objects.requireNonNull(transition, "transition");
            return appendCommand(transition, false, false, false);
        }

        /** Schedules the sole admission deadline, clamped to the current drain deadline. */
        public ScheduledFuture<?> schedule(
                Duration delay,
                BooleanSupplier linearizeExpiry,
                Runnable expirySettlement
        ) {
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(linearizeExpiry, "linearizeExpiry");
            Objects.requireNonNull(expirySettlement, "expirySettlement");
            requireArmed();
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must be non-negative");
            }
            if (!timerScheduled.compareAndSet(false, true)) {
                throw new IllegalStateException("Admission deadline is already scheduled: " + admissionAttemptId);
            }
            deadline = new AdmissionDeadline(linearizeExpiry, expirySettlement);
            long now = System.nanoTime();
            long requestedDeadline = saturatedAdd(now, delay.toNanos());
            long effectiveDeadline = Math.min(requestedDeadline, producer.flushDeadlineNanos());
            try {
                ScheduledFuture<?> scheduled = scheduleDeadline(
                        effectiveDeadline,
                        linearizeExpiry,
                        expirySettlement
                );
                deadlineTask = scheduled;
                if (commandTail.get() == closedMarker) {
                    scheduled.cancel(false);
                }
                return scheduled;
            } catch (RuntimeException | Error failure) {
                timerScheduled.set(false);
                deadline = null;
                throw failure;
            }
        }

        private ScheduledFuture<?> scheduleDeadline(
                long deadlineNanos,
                BooleanSupplier linearizeExpiry,
                Runnable expirySettlement
        ) {
            long delayNanos = Math.max(0, deadlineNanos - System.nanoTime());
            return retryTimer.schedule(() -> {
                try {
                    if (linearizeExpiry.getAsBoolean()) {
                        submit(expirySettlement);
                    }
                } catch (Throwable failure) {
                    reject(failure);
                }
            }, delayNanos, TimeUnit.NANOSECONDS);
        }

        private void clampDeadlineToFlush() {
            AdmissionDeadline deadlineAction = deadline;
            if (!timerScheduled.get() || deadlineAction == null || commandTail.get() == closedMarker) {
                return;
            }
            ScheduledFuture<?> scheduled = scheduleDeadline(
                    producer.flushDeadlineNanos(),
                    deadlineAction.linearizeExpiry(),
                    deadlineAction.expirySettlement()
            );
            drainDeadlineTask = scheduled;
            if (commandTail.get() == closedMarker) {
                scheduled.cancel(false);
            }
        }

        /** Orders plan rejection before exceptional admission notification. */
        public boolean reject(Throwable cause) {
            Throwable checkedCause = Objects.requireNonNull(cause, "cause");
            while (true) {
                Object armed = armState.get();
                if (armed == null) {
                    PreArmRejection rejection = new PreArmRejection(checkedCause);
                    if (!armState.compareAndSet(null, rejection)) {
                        continue;
                    }
                    queueRejection(checkedCause, false, true);
                    return true;
                }
                if (armed instanceof PreArmRejection) {
                    return false;
                }
                return queueRejection(checkedCause, false, false);
            }
        }

        /**
         * Publishes cancellation exceptionally without terminalizing the associated plan as
         * NOT_ACCEPTED. Competes with deliver(...) and reject(...) on the same attempt-level CAS.
         */
        public boolean cancel(CancellationException cause) {
            CancellationException checkedCause = Objects.requireNonNull(cause, "cause");
            return appendCommand(
                    () -> publishOutcome(new StageOutcome<>(null, checkedCause)),
                    true,
                    false,
                    false
            );
        }

        private boolean deliver(T immutableOutcome) {
            return appendCommand(
                    () -> publishOutcome(new StageOutcome<>(immutableOutcome, null)),
                    true,
                    false,
                    false
            );
        }

        private boolean queueRejection(
                Throwable cause,
                boolean allowExpiredBeforeArm,
                boolean allowUnarmed
        ) {
            return appendCommand(() -> rejectOnControl(cause), true, allowExpiredBeforeArm, allowUnarmed);
        }

        private boolean appendCommand(
                Runnable transition,
                boolean closesAttempt,
                boolean allowExpiredBeforeArm,
                boolean allowPreArmRejection
        ) {
            if (commandTail.get() == closedMarker) {
                return false;
            }
            if (closesAttempt) {
                outcomeEntryProbe.run();
            }
            Object armed = armState.get();
            if (!(armed instanceof AdmissionArm)
                    && !(allowExpiredBeforeArm && armed == expiredBeforeArm)
                    && !(allowPreArmRejection && armed instanceof PreArmRejection)) {
                if (armed == expiredBeforeArm || commandTail.get() == closedMarker) {
                    return false;
                }
                throw new IllegalStateException("Admission producer must be armed before use: " + admissionAttemptId);
            }
            AdmissionCommandNode node = new AdmissionCommandNode(transition);
            while (true) {
                AdmissionCommandNode tail = commandTail.get();
                if (tail == closedMarker) {
                    return false;
                }
                AdmissionCommandNode replacement = closesAttempt ? closedMarker : node;
                if (commandTail.compareAndSet(tail, replacement)) {
                    tail.next = node;
                    scheduleDrain();
                    return true;
                }
            }
        }

        private void scheduleDrain() {
            if (drainScheduled.compareAndSet(false, true)) {
                enqueueCollaborator(this::drainOne, producer, false);
            }
        }

        private void drainOne() {
            AdmissionCommandNode next = commandHead.next;
            if (next == null) {
                drainScheduled.set(false);
                if (commandHead.next != null) {
                    scheduleDrain();
                }
                return;
            }
            commandHead = next;
            try {
                next.transition.run();
            } catch (Throwable failure) {
                transitionFailed(failure);
                return;
            } finally {
                drainScheduled.set(false);
            }
            if (commandHead.next != null) {
                scheduleDrain();
            }
        }

        private void reserveFlushExpiry() {
            armState.compareAndSet(null, expiredBeforeArm);
        }

        private void runFlushExpiry(long deadlineNanos) {
            TimeoutException expiry = new TimeoutException(
                    "Admission attempt " + admissionAttemptId + " drain deadline expired at " + deadlineNanos
            );
            Object armed = armState.get();
            if (armed instanceof AdmissionArm arm) {
                try {
                    arm.waiterFlushExpiry().expire(deadlineNanos);
                } catch (Throwable failure) {
                    expiry.addSuppressed(failure);
                }
            } else if (armed == expiredBeforeArm) {
                unarmedAdmissionProducerDiagnostics.incrementAndGet();
                lastUnarmedAdmissionProducerFailure.set(expiry);
                forceRejectBeforeArm(expiry);
                return;
            } else if (armed instanceof PreArmRejection rejection) {
                forceRejectBeforeArm(rejection.cause());
                return;
            }
            queueRejection(expiry, true, false);
        }

        private void forceRejectBeforeArm(Throwable cause) {
            outcomeEntryProbe.run();
            while (true) {
                AdmissionCommandNode tail = commandTail.get();
                if (tail == closedMarker) {
                    return;
                }
                if (commandTail.compareAndSet(tail, closedMarker)) {
                    rejectOnControl(cause);
                    return;
                }
            }
        }

        private void rejectOnControl(Throwable cause) {
            deferredPlanClaim.set(registeredPlan);
            try {
                registeredPlan.rejectAdmission(cause);
                registeredPlan.processCurrentClaim();
            } finally {
                deferredPlanClaim.remove();
            }
            publishOutcome(new StageOutcome<>(null, cause));
        }

        private void publishOutcome(StageOutcome<T> outcome) {
            if (!outcomeReserved.compareAndSet(false, true)) {
                return;
            }
            Publication publication = reservePublication(outcome, delivery.future, this, true);
            startPublication(publication);
        }

        private void transitionFailed(Throwable failure) {
            closeCommands();
            Object armed = armState.get();
            if (armed instanceof AdmissionArm arm) {
                try {
                    arm.transitionFailure().accept(failure);
                } catch (Throwable settlementFailure) {
                    failure.addSuppressed(settlementFailure);
                }
            }
            rejectOnControl(failure);
        }

        private void closeCommands() {
            while (true) {
                AdmissionCommandNode tail = commandTail.get();
                if (tail == closedMarker || commandTail.compareAndSet(tail, closedMarker)) {
                    return;
                }
            }
        }

        private AdmissionArm requireArmed() {
            Object armed = armState.get();
            if (armed instanceof AdmissionArm arm) {
                return arm;
            }
            throw new IllegalStateException("Admission producer is not armed: " + admissionAttemptId);
        }

        private void releaseAfterPublicationLocked() {
            if (!released.compareAndSet(false, true)) {
                throw new IllegalStateException("Admission producer was already released: " + admissionAttemptId);
            }
            ScheduledFuture<?> scheduled = deadlineTask;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            ScheduledFuture<?> drainScheduled = drainDeadlineTask;
            if (drainScheduled != null) {
                drainScheduled.cancel(false);
            }
            deadlineTask = null;
            drainDeadlineTask = null;
            deadline = null;
            armState.set(releasedArm);
            commandHead = closedMarker;
            if (!admissionProducers.remove(admissionAttemptId, this)) {
                throw new IllegalStateException("Admission producer was not registered: " + admissionAttemptId);
            }
            releaseProducerLocked(producer);
        }

    }

    public final class AdmissionDeliveryLease<T> {

        private final AdmissionProducer<T> producer;
        private final CompletableFuture<T> future = new CompletableFuture<>();
        private final CompletionStage<T> futureView = isolateStage(future);

        private AdmissionDeliveryLease(AdmissionProducer<T> producer) {
            this.producer = producer;
        }

        public CompletionStage<T> future() {
            return futureView;
        }

        /** Publishes the successful immutable outcome exactly once. */
        public boolean deliver(T immutableOutcome) {
            return producer.deliver(immutableOutcome);
        }

    }

    private static AppliedReceipt emptyReceipt() {
        return new AppliedReceipt(
                0,
                List.of(),
                List.of(),
                List.of(),
                0,
                PacketPhaseResult.noPackets(),
                HistorySettlement.NOT_REQUIRED
        );
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Producer collaborator transition failed", failure);
        }
    }

    private record Publication(Runnable command, AdmissionProducer<?> completedAdmission) {
    }

    private record PlanProducerKey(UUID operationId, long chunkKey, long planSequence) {
    }

    private record AdmissionProducerKey(long admissionAttemptId) {
    }

    private record PlanBinding(
            Consumer<ChunkTerminalRecord> terminalTransition,
            FlushExpiryHook coordinatorFlushExpiry,
            Consumer<Throwable> coordinatorTransitionFailure
    ) {
    }

    private record PlanExpiring(PlanBinding binding) {
    }

    private record PlanClaim(
            PlanBinding binding,
            ChunkTerminalRecord record,
            AtomicBoolean processed
    ) {
    }

    private record AdmissionArm(
            FlushExpiryHook waiterFlushExpiry,
            Consumer<Throwable> transitionFailure
    ) {
    }

    private record PreArmRejection(Throwable cause) {
    }

    private record AdmissionDeadline(
            BooleanSupplier linearizeExpiry,
            Runnable expirySettlement
    ) {
    }

    private static final class AdmissionCommandNode {

        private final Runnable transition;
        private volatile AdmissionCommandNode next;

        private AdmissionCommandNode(Runnable transition) {
            this.transition = transition;
        }

    }

    private record StageOutcome<T>(T value, Throwable failure) {

        private void publishTo(CompletableFuture<T> target) {
            if (failure == null) {
                target.complete(value);
            } else {
                target.completeExceptionally(failure);
            }
        }

    }

}
