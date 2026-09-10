package com.fastasyncworldedit.core.util.task;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;

/** Thread-safe exactly-once coordinator for one operation. */
public final class DefaultOperationCompletion implements OperationCompletion {

    private static final Runnable NO_TRANSITION = () -> {
    };

    private final UUID operationId;
    private final OperationCompletionService completionService;
    private final Runnable coordinatorContextGuard;
    private final Duration finalizerTimeout;
    private final HistoryPersistencePolicy persistencePolicy;
    private final PersistenceBoundary persistenceBoundary;
    private final Runnable admissionCloseDispatchProbe;
    private final OperationCompletionService.Producer admissionProducer;
    private final Map<PlanRegistrationKey, RegisteredPlan> registrations = new LinkedHashMap<>();
    private final List<Throwable> failures = new ArrayList<>();
    private final CompletableFuture<OperationResult> result = new CompletableFuture<>();
    private final CompletionStage<OperationResult> resultView;

    private State state = State.OPEN;
    private boolean registrationOpen = true;
    private boolean admissionClosed;
    private boolean finalizersSettled;
    private FinalizerSettlement finalizerSettlement;
    private long outstandingTerminals;
    private int pendingPersistence;
    private long duplicateTerminals;
    private long contradictoryTerminals;

    /**
     * Creates a coordinator and registers the finalizer producer before initiating its boundary.
     */
    public DefaultOperationCompletion(
            UUID operationId,
            OperationCompletionService completionService,
            Runnable coordinatorContextGuard,
            FinalizerBoundary requiredFinalizers,
            Duration finalizerTimeout,
            HistoryPersistencePolicy persistencePolicy,
            PersistenceBoundary persistenceBoundary
    ) {
        this(
                operationId,
                completionService,
                coordinatorContextGuard,
                requiredFinalizers,
                finalizerTimeout,
                persistencePolicy,
                persistenceBoundary,
                () -> {
                }
        );
    }

    DefaultOperationCompletion(
            UUID operationId,
            OperationCompletionService completionService,
            Runnable coordinatorContextGuard,
            FinalizerBoundary requiredFinalizers,
            Duration finalizerTimeout,
            HistoryPersistencePolicy persistencePolicy,
            PersistenceBoundary persistenceBoundary,
            Runnable admissionCloseDispatchProbe
    ) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.completionService = Objects.requireNonNull(completionService, "completionService");
        this.coordinatorContextGuard = Objects.requireNonNull(coordinatorContextGuard, "coordinatorContextGuard");
        this.finalizerTimeout = requirePositiveDuration(finalizerTimeout, "finalizerTimeout");
        this.persistencePolicy = Objects.requireNonNull(persistencePolicy, "persistencePolicy");
        this.persistenceBoundary = Objects.requireNonNull(persistenceBoundary, "persistenceBoundary");
        this.admissionCloseDispatchProbe = Objects.requireNonNull(
                admissionCloseDispatchProbe,
                "admissionCloseDispatchProbe"
        );
        this.resultView = completionService.isolateStage(result);
        this.admissionProducer = completionService.registerProducer(
                new LifecycleProducerKey(operationId, "admission"),
                this::admissionDrainExpired,
                this::admissionTransitionFailed
        );
        startFinalizers(Objects.requireNonNull(requiredFinalizers, "requiredFinalizers"));
    }

    @Override
    public void register(long chunkKey, long planSequence) {
        coordinatorContextGuard.run();
        registrationKey(chunkKey, planSequence);
        throw new IllegalStateException("Plan registration requires a PlanProducerToken");
    }

    /** Consumes one preflight-enrolled plan registration capability transactionally. */
    public synchronized void register(OperationCompletionService.PlanProducerToken token) {
        Objects.requireNonNull(token, "token");
        PlanRegistrationKey insertedKey = null;
        boolean inserted = false;
        boolean transferred = false;
        try {
            coordinatorContextGuard.run();
            if (!token.belongsTo(completionService)) {
                throw new IllegalArgumentException("Plan producer token belongs to a different completion service");
            }
            if (!operationId.equals(token.operationId())) {
                throw new IllegalArgumentException("Plan producer token belongs to a different operation");
            }
            if (!registrationOpen) {
                throw new IllegalStateException("Admission is already closed");
            }
            PlanRegistrationKey key = registrationKey(token.chunkKey(), token.planSequence());
            if (registrations.containsKey(key)) {
                throw new IllegalStateException("Chunk plan is already registered: " + key);
            }
            registrations.put(key, new RegisteredPlan(token, token.registrationProducer()));
            insertedKey = key;
            inserted = true;
            token.consumeRegistration(
                    this::terminalOnService,
                    deadlineNanos -> planDrainExpired(key, deadlineNanos),
                    failure -> planTransitionFailed(key, failure)
            );
            transferred = true;
            outstandingTerminals++;
        } catch (RuntimeException | Error failure) {
            if (!transferred) {
                if (inserted) {
                    registrations.remove(insertedKey);
                }
                try {
                    token.abort(failure);
                } catch (IllegalStateException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public void closeAdmission() {
        coordinatorContextGuard.run();
        synchronized (this) {
            if (!registrationOpen) {
                return;
            }
            registrationOpen = false;
        }
        admissionCloseDispatchProbe.run();
        admissionProducer.tryComplete(this::closeAdmissionOnService);
    }

    @Override
    public void terminal(ChunkTerminalRecord record) {
        coordinatorContextGuard.run();
        ChunkTerminalRecord checkedRecord = validateRecord(record);
        OperationCompletionService.PlanProducerToken token;
        synchronized (this) {
            PlanRegistrationKey key = registrationKey(checkedRecord.chunkKey(), checkedRecord.planSequence());
            RegisteredPlan plan = registrations.get(key);
            if (plan == null) {
                throw new IllegalStateException("Chunk plan was not registered: " + key);
            }
            if (plan.record != null) {
                recordDuplicateTerminal(plan.record, checkedRecord);
                return;
            }
            token = plan.token;
        }
        if (!token.terminal(checkedRecord)) {
            synchronized (this) {
                PlanRegistrationKey key = registrationKey(checkedRecord.chunkKey(), checkedRecord.planSequence());
                RegisteredPlan plan = registrations.get(key);
                ChunkTerminalRecord settled = plan.record == null ? token.claimedRecord() : plan.record;
                if (settled == null) {
                    throw new IllegalStateException("Plan terminalization is no longer available: " + key);
                }
                recordDuplicateTerminal(settled, checkedRecord);
            }
        }
    }

    @Override
    public CompletionStage<OperationResult> future() {
        return resultView;
    }

    public UUID operationId() {
        return operationId;
    }

    /** Matching key supplied to admission preflight before this plan is registered. */
    public PlanRegistrationKey registrationKey(long chunkKey, long planSequence) {
        if (planSequence <= 0) {
            throw new IllegalArgumentException("planSequence must be positive");
        }
        return new PlanRegistrationKey(operationId, chunkKey, planSequence);
    }

    public synchronized State state() {
        return state;
    }

    public synchronized long duplicateTerminalCount() {
        return duplicateTerminals;
    }

    public synchronized long contradictoryTerminalCount() {
        return contradictoryTerminals;
    }

    public synchronized long outstandingTerminalCount() {
        return outstandingTerminals;
    }

    private void startFinalizers(FinalizerBoundary requiredFinalizers) {
        OperationCompletionService.Producer producer = completionService.registerProducer(
                new LifecycleProducerKey(operationId, "finalizers"),
                this::finalizerDrainExpired,
                this::finalizerTransitionFailed
        );
        finalizerSettlement = new FinalizerSettlement(requiredFinalizers, producer);
        producer.execute(finalizerSettlement::start);
    }

    private synchronized void closeAdmissionOnService() {
        admissionClosed = true;
        maybeComplete();
    }

    private synchronized void recordDuplicateTerminal(
            ChunkTerminalRecord settled,
            ChunkTerminalRecord discarded
    ) {
        duplicateTerminals++;
        if (settled.status() != discarded.status() || !settled.applied().equals(discarded.applied())) {
            contradictoryTerminals++;
        }
    }

    private void terminalOnService(ChunkTerminalRecord record) {
        PersistenceSettlement settlement = null;
        OperationCompletionService.Producer producer;
        synchronized (this) {
            PlanRegistrationKey key = registrationKey(record.chunkKey(), record.planSequence());
            RegisteredPlan plan = registrations.get(key);
            if (plan.record != null) {
                recordDuplicateTerminal(plan.record, record);
                return;
            }
            producer = plan.producer;
            plan.record = record;
            outstandingTerminals--;
            record.failure().ifPresent(failures::add);
            boolean needsSettlementDecision = isApplied(record.status())
                    && record.applied().historySettlement() == HistorySettlement.NOT_REQUIRED;
            if (needsSettlementDecision) {
                plan.persistencePending = true;
                pendingPersistence++;
                settlement = new PersistenceSettlement(record, producer);
                plan.persistenceSettlement = settlement;
            } else {
                recordUnavailableSettlement(record);
                maybeComplete();
            }
        }
        if (settlement == null) {
            producer.tryComplete(NO_TRANSITION);
            return;
        }
        settlement.start();
    }

    private synchronized void finalizersSettled(Throwable failure) {
        if (finalizersSettled) {
            return;
        }
        finalizerSettlement = null;
        finalizersSettled = true;
        if (failure != null) {
            failures.add(unwrap(failure));
        }
        maybeComplete();
    }

    private synchronized void persistenceSettled(ChunkTerminalRecord record, Throwable failure) {
        PlanRegistrationKey key = registrationKey(record.chunkKey(), record.planSequence());
        RegisteredPlan plan = registrations.get(key);
        plan.record = record;
        plan.persistenceSettlement = null;
        if (plan.persistencePending) {
            plan.persistencePending = false;
            pendingPersistence--;
        }
        if (failure != null) {
            failures.add(failure);
        }
        maybeComplete();
    }

    private synchronized void planTransitionFailed(PlanRegistrationKey key, Throwable failure) {
        RegisteredPlan plan = registrations.get(key);
        if (plan == null) {
            failures.add(unwrap(failure));
            maybeComplete();
            return;
        }
        Throwable terminalFailure = unwrap(failure);
        if (plan.persistenceSettlement != null) {
            plan.persistenceSettlement.abort();
            plan.persistenceSettlement = null;
        }
        if (plan.record == null) {
            plan.record = new ChunkTerminalRecord(
                    operationId,
                    key.chunkKey(),
                    key.planSequence(),
                    TerminalStatus.FAILED_BEFORE_MUTATION,
                    emptyReceipt(),
                    Optional.of(terminalFailure)
            );
            outstandingTerminals--;
        } else if (isApplied(plan.record.status())
                && plan.record.applied().historySettlement() == HistorySettlement.NOT_REQUIRED) {
            plan.record = withSettlement(plan.record, HistorySettlement.UNAVAILABLE);
            terminalFailure = new HistoryUnavailableException(key.chunkKey(), terminalFailure);
        }
        if (plan.persistencePending) {
            plan.persistencePending = false;
            pendingPersistence--;
        }
        failures.add(terminalFailure);
        maybeComplete();
    }

    private void planDrainExpired(PlanRegistrationKey key, long deadlineNanos) {
        synchronized (this) {
            RegisteredPlan plan = registrations.get(key);
            if (plan == null) {
                failures.add(new IllegalStateException("Drain expiry could not find registered plan " + key));
            } else {
                if (plan.persistenceSettlement != null) {
                    plan.persistenceSettlement.abort();
                    plan.persistenceSettlement = null;
                }
                TimeoutException expiry = new TimeoutException("Plan drain deadline expired at " + deadlineNanos);
                if (plan.record == null) {
                    plan.record = new ChunkTerminalRecord(
                            operationId,
                            key.chunkKey(),
                            key.planSequence(),
                            TerminalStatus.CANCELLED_BEFORE_MUTATION,
                            emptyReceipt(),
                            Optional.of(expiry)
                    );
                    outstandingTerminals--;
                    failures.add(expiry);
                } else if (isApplied(plan.record.status())
                        && plan.record.applied().historySettlement() == HistorySettlement.NOT_REQUIRED) {
                    plan.record = withSettlement(plan.record, HistorySettlement.UNAVAILABLE);
                    failures.add(new HistoryUnavailableException(key.chunkKey(), expiry));
                }
                if (plan.persistencePending) {
                    plan.persistencePending = false;
                    pendingPersistence--;
                }
            }
        }
        requestCompletionCheck();
    }

    private void admissionDrainExpired(long deadlineNanos) {
        synchronized (this) {
            registrationOpen = false;
            admissionClosed = true;
        }
        requestCompletionCheck();
    }

    private void finalizerDrainExpired(long deadlineNanos) {
        FinalizerSettlement settlement;
        synchronized (this) {
            settlement = finalizerSettlement;
        }
        if (settlement != null) {
            settlement.abort();
        }
        synchronized (this) {
            if (finalizersSettled) {
                return;
            }
            finalizerSettlement = null;
            finalizersSettled = true;
            failures.add(new TimeoutException("Required finalizer drain deadline expired at " + deadlineNanos));
        }
        requestCompletionCheck();
    }

    private synchronized void admissionTransitionFailed(Throwable failure) {
        registrationOpen = false;
        admissionClosed = true;
        failures.add(unwrap(failure));
        maybeComplete();
    }

    private void finalizerTransitionFailed(Throwable failure) {
        FinalizerSettlement settlement = finalizerSettlement;
        if (settlement != null) {
            settlement.abort();
        }
        finalizersSettled(failure);
    }

    private void requestCompletionCheck() {
        completionService.submit(() -> {
            synchronized (DefaultOperationCompletion.this) {
                maybeComplete();
            }
        });
    }

    private void maybeComplete() {
        if (state != State.OPEN || !admissionClosed || !finalizersSettled || pendingPersistence != 0
                || outstandingTerminals != 0) {
            return;
        }
        state = State.COMPLETING;
        OperationResult operationResult = buildResult();
        State terminalState = switch (operationResult.classification()) {
            case SUCCEEDED -> State.SUCCEEDED;
            case FAILED -> State.FAILED;
            case PARTIAL -> State.PARTIAL;
        };
        // Checks are producer-owned or pre-counted before release, so termination cannot precede this reservation.
        completionService.publishOutcome(result, operationResult, () -> state = terminalState);
    }

    private OperationResult buildResult() {
        List<ChunkTerminalRecord> terminalRecords = registrations.values().stream()
                .map(plan -> plan.record)
                .toList();
        boolean committed = terminalRecords.stream().anyMatch(record -> isApplied(record.status()));
        boolean failed = !failures.isEmpty() || terminalRecords.stream().anyMatch(this::isFailed);
        OperationResult.Classification classification;
        if (!failed) {
            classification = OperationResult.Classification.SUCCEEDED;
        } else if (committed) {
            classification = OperationResult.Classification.PARTIAL;
        } else {
            classification = OperationResult.Classification.FAILED;
        }
        return new OperationResult(operationId, classification, terminalRecords, failures);
    }

    private boolean isFailed(ChunkTerminalRecord record) {
        if (record.failure().isPresent()) {
            return true;
        }
        return switch (record.status()) {
            case NO_CHANGE -> false;
            case COMMITTED -> !record.applied().packetResult().allEnqueued()
                    || record.applied().historySettlement() == HistorySettlement.UNAVAILABLE;
            case NOT_ACCEPTED, FAILED_BEFORE_MUTATION, PARTIALLY_COMMITTED, CANCELLED_BEFORE_MUTATION -> true;
        };
    }

    private ChunkTerminalRecord validateRecord(ChunkTerminalRecord record) {
        Objects.requireNonNull(record, "record");
        if (!operationId.equals(record.operationId())) {
            throw new IllegalArgumentException("Terminal record belongs to a different operation");
        }
        if (record.planSequence() <= 0) {
            throw new IllegalArgumentException("record.planSequence must be positive");
        }
        Objects.requireNonNull(record.status(), "record.status");
        AppliedReceipt applied = Objects.requireNonNull(record.applied(), "record.applied");
        Objects.requireNonNull(record.failure(), "record.failure");
        if (record.status() == TerminalStatus.NOT_ACCEPTED && record.failure().isEmpty()) {
            throw new IllegalArgumentException("NOT_ACCEPTED requires the aggregate rejection cause");
        }
        if (!isApplied(record.status()) && !isEmpty(applied)) {
            throw new IllegalArgumentException("Pre-mutation terminal status requires an empty applied receipt");
        }
        return record;
    }

    private void recordUnavailableSettlement(ChunkTerminalRecord record) {
        if (isApplied(record.status())
                && record.applied().historySettlement() == HistorySettlement.UNAVAILABLE) {
            failures.add(new HistoryUnavailableException(record.chunkKey(), null));
        }
    }

    private static boolean isApplied(TerminalStatus status) {
        return status == TerminalStatus.COMMITTED || status == TerminalStatus.PARTIALLY_COMMITTED;
    }

    private static boolean isEmpty(AppliedReceipt receipt) {
        return receipt.appliedSectionBitmap() == 0
                && receipt.appliedTileIds().isEmpty()
                && receipt.appliedEntities().isEmpty()
                && receipt.appliedPoiNeighborIds().isEmpty()
                && receipt.appliedLightSections() == 0
                && receipt.packetResult().requiredSends() == 0
                && receipt.packetResult().enqueuedSends() == 0
                && receipt.historySettlement() == HistorySettlement.NOT_REQUIRED;
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

    private static ChunkTerminalRecord withSettlement(
            ChunkTerminalRecord record,
            HistorySettlement settlement
    ) {
        AppliedReceipt applied = record.applied();
        AppliedReceipt settled = new AppliedReceipt(
                applied.appliedSectionBitmap(),
                applied.appliedTileIds(),
                applied.appliedEntities(),
                applied.appliedPoiNeighborIds(),
                applied.appliedLightSections(),
                applied.packetResult(),
                settlement
        );
        return new ChunkTerminalRecord(
                record.operationId(),
                record.chunkKey(),
                record.planSequence(),
                record.status(),
                settled,
                record.failure()
        );
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static Duration requirePositiveDuration(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        duration.toNanos();
        return duration;
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    public enum State {
        OPEN,
        COMPLETING,
        SUCCEEDED,
        FAILED,
        PARTIAL
    }

    @FunctionalInterface
    public interface FinalizerBoundary {

        /**
         * Initiates the finalizer boundary on a service-owned virtual thread, never the serialized control thread.
         * Its synchronous prefix and returned stage are both covered by the finite finalizer deadline.
         */
        CompletionStage<?> begin();

        static FinalizerBoundary none() {
            return () -> CompletableFuture.completedFuture(null);
        }

    }

    @FunctionalInterface
    public interface PersistenceBoundary {

        /**
         * Initiates one persistence attempt on a service-owned virtual thread after its finite timeout is armed.
         * Its synchronous prefix and returned stage are both covered by the attempt and settlement deadlines.
         */
        CompletionStage<?> attempt(ChunkTerminalRecord record, int attempt);

        /** Evaluates persistence need on the same off-control boundary thread under the settlement deadline. */
        default boolean requiresPersistence(ChunkTerminalRecord record) {
            return true;
        }

        static PersistenceBoundary notRequired() {
            return new PersistenceBoundary() {
                @Override
                public CompletionStage<?> attempt(ChunkTerminalRecord record, int attempt) {
                    throw new IllegalStateException("Persistence is not required");
                }

                @Override
                public boolean requiresPersistence(ChunkTerminalRecord record) {
                    return false;
                }
            };
        }

    }

    public static final class HistoryUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final long chunkKey;

        HistoryUnavailableException(long chunkKey, Throwable cause) {
            super("Undo/history is unavailable for committed chunk " + chunkKey, cause);
            this.chunkKey = chunkKey;
        }

        public long chunkKey() {
            return chunkKey;
        }

    }

    private final class SettlementDeadline {

        private final OperationCompletionService.Producer producer;
        private final Duration timeout;
        private final Runnable expired;
        private long originalDeadlineNanos;
        private long generation;
        private ScheduledFuture<?> task;
        private boolean cancelled;

        private SettlementDeadline(
                OperationCompletionService.Producer producer,
                Duration timeout,
                Runnable expired
        ) {
            this.producer = producer;
            this.timeout = timeout;
            this.expired = expired;
        }

        private void start() {
            originalDeadlineNanos = saturatedAdd(System.nanoTime(), timeout.toNanos());
            producer.onFlushing(this::reschedule);
            reschedule();
        }

        private long remainingNanos() {
            long deadline = completionService.effectiveDeadlineNanos(originalDeadlineNanos);
            return deadline - System.nanoTime();
        }

        private void reschedule() {
            if (cancelled) {
                return;
            }
            long scheduledGeneration = ++generation;
            DefaultOperationCompletion.cancel(task);
            long remainingNanos = remainingNanos();
            if (remainingNanos <= 0) {
                cancel();
                expired.run();
                return;
            }
            task = producer.trySchedule(
                    () -> deadlineReached(scheduledGeneration),
                    Duration.ofNanos(remainingNanos)
            );
        }

        private void deadlineReached(long scheduledGeneration) {
            if (cancelled || scheduledGeneration != generation) {
                return;
            }
            if (remainingNanos() > 0) {
                reschedule();
                return;
            }
            cancel();
            expired.run();
        }

        private void cancel() {
            cancelled = true;
            generation++;
            DefaultOperationCompletion.cancel(task);
            task = null;
        }

    }

    private final class FinalizerSettlement {

        private final FinalizerBoundary boundary;
        private final OperationCompletionService.Producer producer;
        private final SettlementDeadline deadline;
        private volatile boolean settled;

        private FinalizerSettlement(
                FinalizerBoundary boundary,
                OperationCompletionService.Producer producer
        ) {
            this.boundary = boundary;
            this.producer = producer;
            this.deadline = new SettlementDeadline(
                    producer,
                    finalizerTimeout,
                    () -> settle(new TimeoutException("Required finalizer deadline expired"))
            );
        }

        private void start() {
            deadline.start();
            completionService.executeBoundary(this::invokeBoundary);
        }

        private void invokeBoundary() {
            CompletionStage<?> finalizers;
            try {
                finalizers = Objects.requireNonNull(
                        boundary.begin(),
                        "Finalizer boundary returned a null stage"
                );
            } catch (Throwable failure) {
                reenter(() -> settle(failure));
                return;
            }
            try {
                finalizers.whenComplete((ignored, failure) -> reenter(() -> settle(failure)));
            } catch (Throwable failure) {
                reenter(() -> settle(failure));
            }
        }

        private void settle(Throwable failure) {
            if (settled) {
                return;
            }
            abort();
            producer.tryComplete(() -> finalizersSettled(failure));
        }

        private void abort() {
            settled = true;
            deadline.cancel();
        }

        private void reenter(Runnable transition) {
            try {
                producer.execute(transition);
            } catch (IllegalStateException failure) {
                if (!settled) {
                    throw failure;
                }
            }
        }

    }

    private final class PersistenceSettlement {

        private final ChunkTerminalRecord record;
        private final OperationCompletionService.Producer producer;
        private final SettlementDeadline deadline;
        private int attempts;
        private long attemptGeneration;
        private volatile boolean settled;
        private Throwable lastFailure;
        private ScheduledFuture<?> attemptTimeoutTask;
        private ScheduledFuture<?> retryTask;

        private PersistenceSettlement(
                ChunkTerminalRecord record,
                OperationCompletionService.Producer producer
        ) {
            this.record = record;
            this.producer = producer;
            this.deadline = new SettlementDeadline(
                    producer,
                    persistencePolicy.settlementTimeout(),
                    () -> unavailable(new TimeoutException("History persistence settlement deadline expired"))
            );
        }

        private void start() {
            deadline.start();
            completionService.executeBoundary(this::decideRequirement);
        }

        private void decideRequirement() {
            boolean required;
            try {
                required = persistenceBoundary.requiresPersistence(record);
            } catch (Throwable failure) {
                reenter(() -> unavailable(failure));
                return;
            }
            reenter(() -> {
                if (required) {
                    startAttempt();
                } else {
                    settle(HistorySettlement.NOT_REQUIRED, null);
                }
            });
        }

        private void startAttempt() {
            if (settled) {
                return;
            }
            retryTask = null;
            long remainingNanos = deadline.remainingNanos();
            if (remainingNanos <= 0) {
                unavailable(new TimeoutException("History persistence settlement deadline expired"));
                return;
            }
            attempts++;
            long generation = ++attemptGeneration;
            int attemptNumber = attempts;
            Duration timeout = Duration.ofNanos(Math.min(
                    persistencePolicy.attemptTimeout().toNanos(),
                    remainingNanos
            ));
            attemptTimeoutTask = producer.trySchedule(() -> attemptFinished(
                    generation,
                    new TimeoutException("History persistence attempt " + attemptNumber + " timed out")
            ), timeout);
            if (attemptTimeoutTask == null) {
                return;
            }
            completionService.executeBoundary(() -> invokeAttempt(generation, attemptNumber));
        }

        private void invokeAttempt(long generation, int attemptNumber) {
            CompletionStage<?> attempt;
            try {
                attempt = Objects.requireNonNull(
                        persistenceBoundary.attempt(record, attemptNumber),
                        "Persistence boundary returned a null stage"
                );
            } catch (Throwable failure) {
                reenter(() -> attemptFinished(generation, failure));
                return;
            }
            try {
                attempt.whenComplete((ignored, failure) ->
                        reenter(() -> attemptFinished(generation, failure))
                );
            } catch (Throwable failure) {
                reenter(() -> attemptFinished(generation, failure));
            }
        }

        private void attemptFinished(long generation, Throwable failure) {
            if (settled || generation != attemptGeneration) {
                return;
            }
            cancel(attemptTimeoutTask);
            attemptTimeoutTask = null;
            attemptGeneration++;
            if (failure == null) {
                settle(HistorySettlement.DURABLE, null);
                return;
            }
            lastFailure = unwrap(failure);
            if (attempts >= persistencePolicy.maxAttempts()) {
                unavailable(lastFailure);
                return;
            }
            long remainingNanos = deadline.remainingNanos();
            long backoffNanos = persistencePolicy.retryBackoff().toNanos();
            if (remainingNanos <= backoffNanos) {
                unavailable(new TimeoutException("History persistence settlement deadline exhausted"));
                return;
            }
            retryTask = producer.trySchedule(this::startAttempt, persistencePolicy.retryBackoff());
        }

        private void unavailable(Throwable failure) {
            Throwable cause = failure == null ? lastFailure : failure;
            settle(HistorySettlement.UNAVAILABLE, new HistoryUnavailableException(record.chunkKey(), cause));
        }

        private void settle(HistorySettlement settlement, Throwable failure) {
            if (settled) {
                return;
            }
            abort();
            ChunkTerminalRecord settledRecord = withSettlement(record, settlement);
            producer.tryComplete(() -> persistenceSettled(settledRecord, failure));
        }

        private void abort() {
            settled = true;
            attemptGeneration++;
            deadline.cancel();
            cancel(attemptTimeoutTask);
            cancel(retryTask);
            attemptTimeoutTask = null;
            retryTask = null;
        }

        private void reenter(Runnable transition) {
            try {
                producer.execute(transition);
            } catch (IllegalStateException failure) {
                if (!settled) {
                    throw failure;
                }
            }
        }

    }

    public record PlanRegistrationKey(UUID operationId, long chunkKey, long planSequence) {

        public PlanRegistrationKey {
            Objects.requireNonNull(operationId, "operationId");
            if (planSequence <= 0) {
                throw new IllegalArgumentException("planSequence must be positive");
            }
        }

    }

    private record LifecycleProducerKey(UUID operationId, String phase) {
    }

    private static final class RegisteredPlan {

        private final OperationCompletionService.PlanProducerToken token;
        private final OperationCompletionService.Producer producer;
        private ChunkTerminalRecord record;
        private boolean persistencePending;
        private PersistenceSettlement persistenceSettlement;

        private RegisteredPlan(
                OperationCompletionService.PlanProducerToken token,
                OperationCompletionService.Producer producer
        ) {
            this.token = token;
            this.producer = producer;
        }

    }

}
