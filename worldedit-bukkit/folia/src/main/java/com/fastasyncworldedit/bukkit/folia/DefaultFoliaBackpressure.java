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

import com.fastasyncworldedit.core.util.task.OperationCompletionService;
import com.fastasyncworldedit.core.util.task.OperationCompletionService.AdmissionDeliveryLease;
import com.fastasyncworldedit.core.util.task.OperationCompletionService.AdmissionProducer;
import com.fastasyncworldedit.core.util.task.OperationCompletionService.PlanProducerToken;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Shared accounting engine. A registered plan must obtain a per-plan SPI view through
 * {@link #bind(PlanProducerToken)} before attempting admission.
 *
 * <p>{@link RegionKey} is required to be an immutable value key with stable equality and hashing for the
 * lifetime of every permit and waiter. Task 14 owns the concrete key shape.</p>
 */
final class DefaultFoliaBackpressure {

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final long MEBIBYTE = 1L << 20;
    private static final long GIBIBYTE = 1L << 30;
    private static final WaiterDeliveryProbe NOOP_DELIVERY_PROBE = () -> {
    };
    private static final FoliaBackpressure.Pressure ZERO_PRESSURE = new FoliaBackpressure.Pressure(
            0, 0, 0, 0, 0, 0, 0, 0
    );

    private final FoliaBackpressure.Limits limits;
    private final Tuning tuning;
    private final int continuationFinalizerLimit;
    private final int regularFinalizerLimit;
    private final int continuationWaiterLimit;
    private final int regularWaiterLimit;
    private final int regularGlobalFinalizerLimit;
    private final int regularGlobalWaiterLimit;
    private final OperationCompletionService completionService;
    private final AdmissionRejectionHandler rejectionHandler;
    private final LongSupplier nanoTime;
    private final WaiterDeliveryProbe waiterDeliveryProbe;
    private final AtomicLong admissionAttemptSequence = new AtomicLong();
    // Settlement only: never held across producer submission, outcome publication, or user code.
    private final ReentrantLock accountingLock = new ReentrantLock();
    private final Map<RegionKey, RegionState> regions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PruneRequest> pruneRequests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingPruneRequests = new AtomicInteger();
    private final AtomicBoolean pruneSweepRequested = new AtomicBoolean();
    private final LinkedHashSet<Waiter> continuationWaiters = new LinkedHashSet<>();
    private final LinkedHashSet<Waiter> regularWaiters = new LinkedHashSet<>();
    private final AtomicBoolean waiterServiceScheduled = new AtomicBoolean();
    private final AtomicBoolean waiterRescanRequested = new AtomicBoolean();
    private final Runnable waiterServiceCommand = this::serviceWaiters;

    private final AtomicLong globalReadyChunks = new AtomicLong();
    private final AtomicLong globalReadyBytes = new AtomicLong();
    private final AtomicLong globalFinalizers = new AtomicLong();
    private final AtomicLong globalRegularFinalizers = new AtomicLong();
    private int globalWaiters;
    private int globalRegularWaiters;
    private int continuationScanRemaining;
    private int regularScanRemaining;
    private volatile Waiter continuationWakeCandidate;
    private volatile Waiter regularWakeCandidate;
    private Iterator<Map.Entry<RegionKey, RegionState>> pruneSweep;
    private Throwable stoppedReason;

    DefaultFoliaBackpressure(
            FoliaBackpressure.Limits limits,
            int continuationFinalizerLimit,
            OperationCompletionService completionService,
            AdmissionRejectionHandler rejectionHandler
    ) {
        this(
                limits,
                continuationFinalizerLimit,
                completionService,
                rejectionHandler,
                System::nanoTime,
                Tuning.initial(),
                NOOP_DELIVERY_PROBE
        );
    }

    DefaultFoliaBackpressure(
            FoliaBackpressure.Limits limits,
            int continuationFinalizerLimit,
            OperationCompletionService completionService,
            AdmissionRejectionHandler rejectionHandler,
            LongSupplier nanoTime
    ) {
        this(
                limits,
                continuationFinalizerLimit,
                completionService,
                rejectionHandler,
                nanoTime,
                Tuning.initial(),
                NOOP_DELIVERY_PROBE
        );
    }

    DefaultFoliaBackpressure(
            FoliaBackpressure.Limits limits,
            int continuationFinalizerLimit,
            OperationCompletionService completionService,
            AdmissionRejectionHandler rejectionHandler,
            LongSupplier nanoTime,
            Tuning tuning,
            WaiterDeliveryProbe waiterDeliveryProbe
    ) {
        this.limits = validateLimits(Objects.requireNonNull(limits, "limits"));
        this.tuning = Objects.requireNonNull(tuning, "tuning").validated();
        if (continuationFinalizerLimit <= 0
                || continuationFinalizerLimit >= limits.maxFinalizersPerRegion()) {
            throw new IllegalArgumentException(
                    "continuation finalizer limit must be between zero and maxFinalizersPerRegion"
            );
        }
        if (continuationFinalizerLimit >= limits.maxWaitersPerRegion()) {
            throw new IllegalArgumentException(
                    "continuation finalizer limit must be below maxWaitersPerRegion"
            );
        }
        if (continuationFinalizerLimit >= limits.maxGlobalFinalizers()) {
            throw new IllegalArgumentException(
                    "continuation finalizer limit must be below maxGlobalFinalizers"
            );
        }
        int globalContinuationWaiterReserve = Math.min(
                limits.maxGlobalFinalizers(),
                this.tuning.maxGlobalWaiters() - 1
        );
        this.continuationFinalizerLimit = continuationFinalizerLimit;
        this.regularFinalizerLimit = limits.maxFinalizersPerRegion() - continuationFinalizerLimit;
        this.continuationWaiterLimit = continuationFinalizerLimit;
        this.regularWaiterLimit = limits.maxWaitersPerRegion() - continuationFinalizerLimit;
        // Continuations may use all remaining global capacity; regular work cannot consume the reserve.
        this.regularGlobalFinalizerLimit = limits.maxGlobalFinalizers() - continuationFinalizerLimit;
        this.regularGlobalWaiterLimit = this.tuning.maxGlobalWaiters() - globalContinuationWaiterReserve;
        this.completionService = Objects.requireNonNull(completionService, "completionService");
        this.rejectionHandler = Objects.requireNonNull(rejectionHandler, "rejectionHandler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.waiterDeliveryProbe = Objects.requireNonNull(
                waiterDeliveryProbe,
                "waiterDeliveryProbe"
        );
    }

    static FoliaBackpressure.Limits initialLimits(long maximumHeapBytes) {
        if (maximumHeapBytes <= 0) {
            throw new IllegalArgumentException("maximumHeapBytes must be positive");
        }
        return new FoliaBackpressure.Limits(
                256,
                64 * MEBIBYTE,
                64,
                256,
                65_536,
                Math.min(GIBIBYTE, maximumHeapBytes / 4),
                4_096
        );
    }

    /**
     * Returns the frozen SPI view for exactly one already-registered plan.
     *
     * <p>The caller must acquire and consume the token outside every owner-thread fast path. This method performs
     * no completion-service lookup and does not validate lifecycle state.</p>
     */
    BoundAdmission bind(PlanProducerToken registeredPlan) {
        return new BoundBackpressure(Objects.requireNonNull(registeredPlan, "registeredPlan"));
    }

    private FoliaBackpressure.Pressure pressure(RegionKey region) {
        Objects.requireNonNull(region, "region");
        RegionState state = regions.get(region);
        if (state == null) {
            return ZERO_PRESSURE;
        }
        Map.Entry<Long, AtomicInteger> firstReady = state.readySince.firstEntry();
        Long readySinceNanos = firstReady == null ? null : firstReady.getKey();
        long oldestReadyNanos = readySinceNanos == null
                ? 0
                : Math.max(0, nanoTime.getAsLong() - readySinceNanos);
        return new FoliaBackpressure.Pressure(
                state.readyChunks.get(),
                state.readyBytes.get(),
                state.activeFinalizers.get(),
                state.regularWaiters + state.continuationWaiters,
                state.scheduledChunks.get(),
                oldestReadyNanos,
                state.scheduleDelayEwmaNanos.get(),
                state.sliceRuntimeEwmaNanos.get()
        );
    }

    private void recordScheduleDelay(RegionKey region, long delayNanos) {
        Objects.requireNonNull(region, "region");
        if (delayNanos < 0) {
            throw new IllegalArgumentException("delayNanos must not be negative");
        }
        RegionState state = regions.get(region);
        if (state != null) {
            updateEwma(state.scheduleDelayEwmaNanos, delayNanos);
        }
    }

    private void recordSlice(RegionKey region, int chunks, long runtimeNanos) {
        Objects.requireNonNull(region, "region");
        if (chunks <= 0) {
            throw new IllegalArgumentException("chunks must be positive");
        }
        if (runtimeNanos < 0) {
            throw new IllegalArgumentException("runtimeNanos must not be negative");
        }
        RegionState state = regions.get(region);
        if (state != null) {
            updateEwma(state.sliceChunksEwma, chunks);
            updateEwma(state.sliceRuntimeEwmaNanos, runtimeNanos);
        }
    }

    void stopAccepting(Throwable reason) {
        Objects.requireNonNull(reason, "reason");
        Waiter sponsor = null;
        accountingLock.lock();
        try {
            if (stoppedReason == null) {
                stoppedReason = reason;
                sponsor = requestWaiterServiceLocked();
            }
        } finally {
            drainPrunesAndUnlockAccounting();
        }
        submitWaiterService(sponsor);
    }

    private Optional<FoliaBackpressure.Permit> tryAcquire(
            PlanProducerToken registeredPlan,
            RegionKey region,
            FoliaBackpressure.Demand demand
    ) {
        validateDemand(registeredPlan, region, demand);
        requireUnaccepted(demand);
        long now = nanoTime.getAsLong();
        if (deadlineStatus(demand, now) != DeadlineStatus.ACTIVE || !accountingLock.tryLock()) {
            return Optional.empty();
        }
        try {
            if (stoppedReason != null) {
                return Optional.empty();
            }
            RegionState state = regions.get(region);
            if (!canAdmitRegular(state, demand)) {
                return Optional.empty();
            }
            return Optional.of(grant(region, demand, AdmissionKind.REGULAR, now));
        } finally {
            drainPrunesAndUnlockAccounting();
        }
    }

    private CompletionStage<FoliaBackpressure.Permit> acquire(
            PlanProducerToken registeredPlan,
            RegionKey region,
            FoliaBackpressure.Demand demand,
            CompletionStage<?> cancellationSignal,
            AdmissionKind kind
    ) {
        validateDemand(registeredPlan, region, demand);
        Objects.requireNonNull(cancellationSignal, "cancellationSignal");
        long attemptId = nextAdmissionAttemptId();
        AdmissionProducer<FoliaBackpressure.Permit> producer = completionService
                .<FoliaBackpressure.Permit>tryAcquireAdmissionProducer(attemptId, registeredPlan)
                .orElse(null);
        if (producer == null) {
            RejectedExecutionException refusal = new RejectedExecutionException(
                    "completion service is not accepting admission attempt " + attemptId
            );
            registeredPlan.rejectAdmission(refusal);
            throw refusal;
        }
        AdmissionDeliveryLease<FoliaBackpressure.Permit> delivery = producer.delivery();
        Waiter waiter = new Waiter(attemptId, region, demand, kind, producer, delivery);
        if (!producer.arm(waiter::flushExpired, waiter::transitionFailed)) {
            notifyFailure(waiter, AdmissionFailureOutcome.REJECTED);
            return delivery.future();
        }

        try {
            cancellationSignal.whenComplete((ignored, failure) -> producer.submit(waiter.cancellationSettlement));
        } catch (RuntimeException | Error failure) {
            rejectAttempt(waiter, failure);
            return delivery.future();
        }

        long now = nanoTime.getAsLong();
        List<Waiter> deliveries = new ArrayList<>(1);
        accountingLock.lock();
        try {
            if (waiter.state.get() != WaiterState.NEW) {
                return delivery.future();
            }
            Throwable rejection = initialRejection(demand, now);
            if (rejection != null) {
                settleRejectedLocked(waiter, rejection);
                deliveries.add(waiter);
            } else {
                RegionState state = regions.get(region);
                boolean admitted = kind == AdmissionKind.CONTINUATION
                        ? canAdmitContinuation(state, demand)
                        : canAdmitRegular(state, demand);
                if (admitted) {
                    waiter.permit = grant(region, demand, kind, now);
                    waiter.state.set(WaiterState.GRANTED);
                    deliveries.add(waiter);
                } else if (canQueue(state, kind)) {
                    waiter.state.set(WaiterState.QUEUED);
                    queue(kind).add(waiter);
                    installWaiterAccounting(region, kind);
                    refreshWakeCandidatesLocked();
                } else {
                    settleRejectedLocked(
                            waiter,
                            new RejectedExecutionException("backpressure admission waiter limit reached")
                    );
                    deliveries.add(waiter);
                }
            }
        } catch (RuntimeException | Error failure) {
            settleFailureLocked(waiter, failure);
            deliveries.add(waiter);
        } finally {
            drainPrunesAndUnlockAccounting();
        }

        scheduleDeadline(waiter, nanoTime.getAsLong());
        deliverAll(deliveries);
        return delivery.future();
    }

    private void scheduleDeadline(Waiter waiter, long observedNow) {
        if (waiter.state.get() != WaiterState.QUEUED) {
            return;
        }
        long delayNanos = remainingNanos(waiter.demand, observedNow);
        try {
            waiter.deadlineTask = waiter.producer.schedule(
                    Duration.ofNanos(delayNanos),
                    waiter::linearizeExpiry,
                    waiter.expirySettlement
            );
        } catch (RuntimeException | Error failure) {
            if (waiter.producer.submit(() -> settleProducerFailure(waiter, failure))) {
                return;
            }
            settleProducerFailure(waiter, failure);
        }
    }

    private void settleCancellation(Waiter waiter) {
        Waiter sponsor;
        boolean cancelled;
        accountingLock.lock();
        try {
            cancelled = switch (waiter.state.get()) {
                case NEW -> {
                    settleRejectedLocked(waiter, waiter.cancellationFailure);
                    yield true;
                }
                case QUEUED -> {
                    removeQueued(waiter);
                    settleRejectedLocked(waiter, waiter.cancellationFailure);
                    yield true;
                }
                case EXPIRY_LINEARIZED -> false;
                case GRANTED -> {
                    release(waiter.permit);
                    settleRejectedLocked(waiter, waiter.cancellationFailure);
                    yield true;
                }
                case DELIVERED, REJECTED -> false;
            };
            sponsor = cancelled ? requestWaiterServiceLocked() : null;
        } finally {
            drainPrunesAndUnlockAccounting();
        }
        if (cancelled && !cancelAttempt(waiter, waiter.cancellationFailure)) {
            LOGGER.debug("Admission cancellation was already claimed: " + waiter.attemptId);
        }
        submitWaiterService(sponsor);
    }

    private void settleExpiry(Waiter waiter) {
        Waiter sponsor = null;
        boolean reject = false;
        accountingLock.lock();
        try {
            if (waiter.state.compareAndSet(WaiterState.EXPIRY_LINEARIZED, WaiterState.REJECTED)) {
                queue(waiter.kind).remove(waiter);
                removeQueuedAccounting(waiter);
                refreshWakeCandidatesLocked();
                waiter.failure = waiter.deadlineFailure;
                reject = true;
                sponsor = requestWaiterServiceLocked();
            }
        } finally {
            drainPrunesAndUnlockAccounting();
        }
        if (reject) {
            rejectAttempt(waiter, waiter.deadlineFailure);
        }
        submitWaiterService(sponsor);
    }

    private void settleProducerFailure(Waiter waiter, Throwable failure) {
        Waiter sponsor;
        accountingLock.lock();
        try {
            if (!settleFailureLocked(waiter, failure)) {
                return;
            }
            sponsor = requestWaiterServiceLocked();
        } finally {
            drainPrunesAndUnlockAccounting();
        }
        rejectAttempt(waiter, failure);
        submitWaiterService(sponsor);
    }

    private void flushExpired(Waiter waiter, long deadlineNanos) {
        if (!waiter.flushExpirySettled.compareAndSet(false, true)) {
            return;
        }
        TimeoutException failure = new TimeoutException(
                "admission attempt " + waiter.attemptId + " drain deadline expired at " + deadlineNanos
        );
        boolean settled;
        accountingLock.lock();
        try {
            settled = settleFailureLocked(waiter, failure);
        } finally {
            drainPrunesAndUnlockAccounting();
        }
        if (settled) {
            notifyFailure(waiter, AdmissionFailureOutcome.REJECTED);
        }
        // OperationCompletionService publishes the lifecycle rejection after this hook returns.
    }

    private void transitionFailed(Waiter waiter, Throwable failure) {
        if (!waiter.transitionFailureSettled.compareAndSet(false, true)) {
            return;
        }
        Waiter sponsor;
        boolean settled;
        accountingLock.lock();
        try {
            settled = settleFailureLocked(waiter, failure);
            sponsor = requestWaiterServiceLocked();
        } finally {
            drainPrunesAndUnlockAccounting();
        }
        if (settled) {
            notifyFailure(waiter, AdmissionFailureOutcome.REJECTED);
        }
        submitWaiterService(sponsor);
    }

    private boolean settleFailureLocked(Waiter waiter, Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return switch (waiter.state.get()) {
            case NEW -> {
                settleRejectedLocked(waiter, failure);
                yield true;
            }
            case QUEUED, EXPIRY_LINEARIZED -> {
                removeQueuedAccounting(waiter);
                queue(waiter.kind).remove(waiter);
                refreshWakeCandidatesLocked();
                settleRejectedLocked(waiter, failure);
                yield true;
            }
            case GRANTED -> {
                release(waiter.permit);
                settleRejectedLocked(waiter, failure);
                yield true;
            }
            case DELIVERED, REJECTED -> false;
        };
    }

    private void settleRejectedLocked(Waiter waiter, Throwable failure) {
        waiter.failure = Objects.requireNonNull(failure, "failure");
        waiter.state.set(WaiterState.REJECTED);
    }

    private void deliverAll(List<Waiter> deliveries) {
        for (Waiter waiter : deliveries) {
            try {
                waiterDeliveryProbe.beforeAttemptSubmission(waiter.attemptId);
                if (!waiter.deliverySubmitted.compareAndSet(false, true)) {
                    continue;
                }
                if (!waiter.producer.submit(waiter.deliverySettlement)) {
                    recoverDeliveryFailure(waiter, new IllegalStateException(
                            "admission delivery producer was already closed: " + waiter.attemptId
                    ));
                }
            } catch (Throwable failure) {
                LOGGER.error("Failed to submit admission delivery " + waiter.attemptId, failure);
                recoverDeliveryFailure(waiter, failure);
            }
        }
    }

    private void recoverDeliveryFailure(Waiter waiter, Throwable failure) {
        try {
            settleLostDelivery(waiter, failure);
        } catch (Throwable settlementFailure) {
            failure.addSuppressed(settlementFailure);
            LOGGER.error("Failed to settle admission delivery " + waiter.attemptId, failure);
        }
        try {
            rejectAttempt(waiter, failure);
        } catch (Throwable rejectionFailure) {
            failure.addSuppressed(rejectionFailure);
            LOGGER.error("Failed to reject admission delivery " + waiter.attemptId, failure);
        }
    }

    private void deliverSettled(Waiter waiter) {
        BackpressurePermit granted = null;
        Throwable failure = null;
        boolean capacityReleased = false;
        accountingLock.lock();
        try {
            switch (waiter.state.get()) {
                case GRANTED -> granted = waiter.permit;
                case REJECTED -> failure = waiter.failure;
                case EXPIRY_LINEARIZED -> {
                    removeQueuedAccounting(waiter);
                    queue(waiter.kind).remove(waiter);
                    refreshWakeCandidatesLocked();
                    settleRejectedLocked(waiter, waiter.deadlineFailure);
                    failure = waiter.deadlineFailure;
                }
                case NEW, QUEUED, DELIVERED -> {
                    failure = new IllegalStateException(
                            "admission delivery has invalid settlement state " + waiter.state.get()
                    );
                    settleFailureLocked(waiter, failure);
                }
            }
        } finally {
            drainPrunesAndUnlockAccounting();
        }

        if (granted != null) {
            // Attempt settlement and flush expiry share the completion service's control serialization, so no
            // waiter-state command can cross this publication/reconciliation gap.
            boolean delivered = false;
            try {
                delivered = waiter.delivery.deliver(granted);
            } catch (Throwable deliveryFailure) {
                failure = deliveryFailure;
            }
            accountingLock.lock();
            try {
                if (delivered) {
                    if (waiter.state.get() != WaiterState.GRANTED) {
                        throw new IllegalStateException(
                                "grant outcome won from invalid state " + waiter.state.get()
                        );
                    }
                    waiter.state.set(WaiterState.DELIVERED);
                } else {
                    if (failure == null) {
                        failure = new IllegalStateException(
                                "grant delivery was already claimed: " + waiter.attemptId
                        );
                    }
                    release(granted);
                    capacityReleased = true;
                    settleRejectedLocked(waiter, failure);
                }
            } finally {
                drainPrunesAndUnlockAccounting();
            }
        }
        if (capacityReleased) {
            signalCapacityReleased();
        }
        if (failure != null && !rejectAttempt(waiter, failure)) {
            LOGGER.debug("Admission rejection was already claimed: " + waiter.attemptId);
        }
    }

    private void settleLostDelivery(Waiter waiter, Throwable failure) {
        Waiter sponsor;
        accountingLock.lock();
        try {
            if (waiter.state.get() == WaiterState.DELIVERED && waiter.permit != null) {
                release(waiter.permit);
                settleRejectedLocked(waiter, failure);
            } else {
                settleFailureLocked(waiter, failure);
            }
            sponsor = requestWaiterServiceLocked();
        } finally {
            drainPrunesAndUnlockAccounting();
        }
        submitWaiterService(sponsor);
    }

    private void serviceWaiters() {
        long now = nanoTime.getAsLong();
        List<Waiter> deliveries = new ArrayList<>(tuning.waiterServiceBatch() * 2);
        Waiter sponsor = null;
        accountingLock.lock();
        try {
            waiterServiceScheduled.set(false);
            if (waiterRescanRequested.getAndSet(false)) {
                continuationScanRemaining = continuationWaiters.size();
                regularScanRemaining = regularWaiters.size();
            }
            serviceWaiterQueue(
                    continuationWaiters,
                    AdmissionKind.CONTINUATION,
                    Math.min(tuning.waiterServiceBatch(), continuationScanRemaining),
                    now,
                    deliveries
            );
            serviceWaiterQueue(
                    regularWaiters,
                    AdmissionKind.REGULAR,
                    Math.min(tuning.waiterServiceBatch(), regularScanRemaining),
                    now,
                    deliveries
            );
            refreshWakeCandidatesLocked();
            sponsor = scheduleNextWaiterServiceLocked();
        } finally {
            try {
                drainPrunesAndUnlockAccounting();
            } finally {
                publishSettledBatch(deliveries, sponsor);
            }
        }
    }

    private void publishSettledBatch(List<Waiter> deliveries, Waiter sponsor) {
        try {
            try {
                if (!deliveries.isEmpty()) {
                    waiterDeliveryProbe.beforeBatchSubmission();
                }
            } finally {
                deliverAll(deliveries);
            }
        } catch (Throwable submissionFailure) {
            LOGGER.error("Waiter-delivery batch publication failed", submissionFailure);
        } finally {
            submitWaiterService(sponsor);
        }
    }

    private void serviceWaiterQueue(
            LinkedHashSet<Waiter> waiters,
            AdmissionKind kind,
            int scanLimit,
            long now,
            List<Waiter> deliveries
    ) {
        if (scanLimit <= 0 || waiters.isEmpty()) {
            return;
        }
        List<Waiter> deferred = new ArrayList<>(scanLimit);
        Iterator<Waiter> iterator = waiters.iterator();
        for (int examined = 0; examined < scanLimit && iterator.hasNext(); examined++) {
            Waiter waiter = iterator.next();
            iterator.remove();
            decrementScan(kind);
            WaiterState state = waiter.state.get();
            if (state == WaiterState.EXPIRY_LINEARIZED) {
                RegionState regionState = requiredState(waiter.region);
                decrementWaiters(regionState, kind);
                settleRejectedLocked(waiter, waiter.deadlineFailure);
                deliveries.add(waiter);
                pruneIfIdle(waiter.region, regionState);
                continue;
            }
            if (state != WaiterState.QUEUED) {
                RegionState regionState = requiredState(waiter.region);
                decrementWaiters(regionState, kind);
                if (state == WaiterState.NEW) {
                    settleRejectedLocked(waiter, new IllegalStateException(
                            "new admission attempt was present in a waiter queue"
                    ));
                    deliveries.add(waiter);
                } else if (state == WaiterState.GRANTED || state == WaiterState.REJECTED) {
                    deliveries.add(waiter);
                }
                pruneIfIdle(waiter.region, regionState);
                continue;
            }
            Throwable rejection = initialRejection(waiter.demand, now);
            if (rejection != null) {
                RegionState regionState = requiredState(waiter.region);
                decrementWaiters(regionState, kind);
                settleRejectedLocked(waiter, rejection);
                deliveries.add(waiter);
                pruneIfIdle(waiter.region, regionState);
                continue;
            }
            RegionState regionState = requiredState(waiter.region);
            boolean admitted = kind == AdmissionKind.CONTINUATION
                    ? canAdmitContinuation(regionState, waiter.demand)
                    : canAdmitRegular(regionState, waiter.demand);
            if (!admitted) {
                deferred.add(waiter);
                continue;
            }
            if (!waiter.state.compareAndSet(WaiterState.QUEUED, WaiterState.GRANTED)) {
                deferred.add(waiter);
                continue;
            }
            decrementWaiters(regionState, kind);
            waiter.permit = grant(waiter.region, waiter.demand, kind, now);
            deliveries.add(waiter);
        }
        waiters.addAll(deferred);
    }

    private Waiter requestWaiterServiceLocked() {
        continuationScanRemaining = continuationWaiters.size();
        regularScanRemaining = regularWaiters.size();
        refreshWakeCandidatesLocked();
        return scheduleNextWaiterServiceLocked();
    }

    private void signalCapacityReleased() {
        waiterRescanRequested.set(true);
        if (accountingLock.tryLock()) {
            drainPrunesAndUnlockAccounting();
        }
        if (!waiterServiceScheduled.compareAndSet(false, true)) {
            return;
        }
        Waiter sponsor = continuationWakeCandidate;
        if (sponsor == null) {
            sponsor = regularWakeCandidate;
        }
        if (sponsor == null) {
            waiterServiceScheduled.set(false);
            return;
        }
        submitWaiterService(sponsor);
    }

    private void refreshWakeCandidatesLocked() {
        continuationWakeCandidate = firstWaiter(continuationWaiters);
        regularWakeCandidate = firstWaiter(regularWaiters);
    }

    private Waiter scheduleNextWaiterServiceLocked() {
        if ((continuationScanRemaining == 0 && regularScanRemaining == 0)
                || !waiterServiceScheduled.compareAndSet(false, true)) {
            return null;
        }
        Waiter sponsor = firstWaiter(continuationWaiters);
        if (sponsor == null) {
            sponsor = firstWaiter(regularWaiters);
        }
        if (sponsor == null) {
            waiterServiceScheduled.set(false);
        }
        return sponsor;
    }

    private void submitWaiterService(Waiter sponsor) {
        Waiter candidate = sponsor;
        for (int attempt = 0; candidate != null && attempt < 2; attempt++) {
            try {
                if (candidate.producer.submit(waiterServiceCommand)) {
                    return;
                }
            } catch (Throwable failure) {
                LOGGER.error("Failed to submit waiter service through attempt " + candidate.attemptId, failure);
            }
            waiterServiceScheduled.set(false);
            waiterRescanRequested.set(true);
            Waiter previous = candidate;
            candidate = continuationWakeCandidate;
            if (candidate == null || candidate == previous) {
                candidate = regularWakeCandidate;
            }
            if (candidate == null || candidate == previous
                    || !waiterServiceScheduled.compareAndSet(false, true)) {
                return;
            }
        }
        waiterServiceScheduled.set(false);
    }

    private static Waiter firstWaiter(LinkedHashSet<Waiter> waiters) {
        Iterator<Waiter> iterator = waiters.iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    private void decrementScan(AdmissionKind kind) {
        if (kind == AdmissionKind.CONTINUATION) {
            continuationScanRemaining--;
        } else {
            regularScanRemaining--;
        }
    }

    private BackpressurePermit grant(
            RegionKey region,
            FoliaBackpressure.Demand demand,
            AdmissionKind kind,
            long readySinceNanos
    ) {
        RegionState state = regions.compute(region, (ignored, current) -> {
            RegionState selected = current == null ? new RegionState() : current;
            selected.livePermits.incrementAndGet();
            return selected;
        });
        state.readyChunks.addAndGet(demand.chunks());
        state.readyBytes.addAndGet(demand.preparedBytes());
        addReadySince(state, readySinceNanos);
        globalReadyChunks.addAndGet(demand.chunks());
        globalReadyBytes.addAndGet(demand.preparedBytes());
        globalFinalizers.addAndGet(demand.finalizerChains());
        if (kind == AdmissionKind.CONTINUATION) {
            state.reservedContinuationFinalizers.addAndGet(demand.finalizerChains());
        } else {
            state.reservedRegularFinalizers.addAndGet(demand.finalizerChains());
            globalRegularFinalizers.addAndGet(demand.finalizerChains());
        }
        return new BackpressurePermit(region, demand, kind, readySinceNanos);
    }

    private TransferResult transfer(BackpressurePermit permit, RegionKey actualRegion) {
        Objects.requireNonNull(actualRegion, "actualRegion");
        if (!claimPermitOperation(permit)) {
            return permit.closed || permit.closeRequested.get()
                    || permit.operation.get() == PermitOperation.CLOSED
                    ? TransferResult.PERMIT_CLOSED
                    : TransferResult.RETRY_BUSY;
        }
        try {
            if (permit.closeRequested.get()) {
                return TransferResult.PERMIT_CLOSED;
            }
            if (!accountingLock.tryLock()) {
                return TransferResult.RETRY_BUSY;
            }
            boolean moved = false;
            try {
                RegionKey previousRegion = permit.region;
                if (previousRegion.equals(actualRegion)) {
                    return TransferResult.TRANSFERRED;
                }
                RegionState target = regions.get(actualRegion);
                if (!canTransfer(target, permit)) {
                    return TransferResult.TARGET_SATURATED;
                }
                RegionState previous = requiredState(previousRegion);
                if (target == null) {
                    target = new RegionState();
                    regions.put(actualRegion, target);
                }
                moveRegionalAccounting(permit, previous, target);
                permit.region = actualRegion;
                pruneIfIdle(previousRegion, previous);
                moved = true;
                return TransferResult.TRANSFERRED;
            } finally {
                drainPrunesAndUnlockAccounting();
                if (moved) {
                    signalCapacityReleased();
                }
            }
        } finally {
            finishPermitOperation(permit);
        }
    }

    private void enter(BackpressurePermit permit, FoliaBackpressure.Stage next) {
        Objects.requireNonNull(next, "next");
        if (!claimPermitOperation(permit)) {
            throw new IllegalStateException("permit lifecycle transition is closed or already in progress");
        }
        boolean capacityReleased = false;
        try {
            if (permit.closed || next.ordinal() != permit.stage.ordinal() + 1) {
                FoliaBackpressure.Stage previous = permit.stage;
                if (!permit.closed) {
                    release(permit);
                    capacityReleased = true;
                }
                throw new IllegalStateException(
                        "invalid backpressure permit transition from " + previous + " to " + next
                );
            }
            RegionState state = requiredState(permit.region);
            switch (next) {
                case SCHEDULED -> state.scheduledChunks.addAndGet(permit.demand.chunks());
                case COMMITTING -> {
                }
                case FINALIZING -> {
                    state.scheduledChunks.addAndGet(-permit.demand.chunks());
                    state.activeFinalizers.addAndGet(permit.demand.finalizerChains());
                    releaseReadyCapacity(permit, state);
                    capacityReleased = true;
                }
                case READY -> throw new IllegalStateException("READY cannot be entered");
            }
            permit.stage = next;
        } finally {
            if (capacityReleased) {
                signalCapacityReleased();
            }
            finishPermitOperation(permit);
        }
    }

    private void close(BackpressurePermit permit) {
        permit.closeRequested.set(true);
        if (permit.operation.compareAndSet(PermitOperation.IDLE, PermitOperation.ACTIVE)) {
            closeClaimedPermit(permit);
        }
    }

    private static boolean claimPermitOperation(BackpressurePermit permit) {
        return !permit.closeRequested.get()
                && permit.operation.compareAndSet(PermitOperation.IDLE, PermitOperation.ACTIVE);
    }

    private void finishPermitOperation(BackpressurePermit permit) {
        if (permit.closed) {
            permit.operation.set(PermitOperation.CLOSED);
            return;
        }
        if (permit.closeRequested.get()) {
            closeClaimedPermit(permit);
            return;
        }
        permit.operation.set(PermitOperation.IDLE);
        if (permit.closeRequested.get()
                && permit.operation.compareAndSet(PermitOperation.IDLE, PermitOperation.ACTIVE)) {
            closeClaimedPermit(permit);
        }
    }

    private void closeClaimedPermit(BackpressurePermit permit) {
        if (!permit.closed) {
            release(permit);
            signalCapacityReleased();
        }
        permit.operation.set(PermitOperation.CLOSED);
    }

    private void release(BackpressurePermit permit) {
        if (permit.closed) {
            return;
        }
        RegionState state = requiredState(permit.region);
        switch (permit.stage) {
            case READY -> {
            }
            case SCHEDULED, COMMITTING -> state.scheduledChunks.addAndGet(-permit.demand.chunks());
            case FINALIZING -> state.activeFinalizers.addAndGet(-permit.demand.finalizerChains());
        }
        releaseReadyCapacity(permit, state);
        if (permit.kind == AdmissionKind.CONTINUATION) {
            state.reservedContinuationFinalizers.addAndGet(-permit.demand.finalizerChains());
        } else {
            state.reservedRegularFinalizers.addAndGet(-permit.demand.finalizerChains());
            globalRegularFinalizers.addAndGet(-permit.demand.finalizerChains());
        }
        if (permit.globalFinalizersHeld) {
            globalFinalizers.addAndGet(-permit.demand.finalizerChains());
            permit.globalFinalizersHeld = false;
        }
        state.livePermits.decrementAndGet();
        permit.closed = true;
        requestPrune(permit.region, state);
    }

    private void releaseReadyCapacity(BackpressurePermit permit, RegionState state) {
        if (permit.readyCapacityHeld) {
            state.readyChunks.addAndGet(-permit.demand.chunks());
            state.readyBytes.addAndGet(-permit.demand.preparedBytes());
            removeReadySince(state, permit.readySinceNanos);
            permit.readyCapacityHeld = false;
        }
        if (permit.globalReadyHeld) {
            globalReadyChunks.addAndGet(-permit.demand.chunks());
            globalReadyBytes.addAndGet(-permit.demand.preparedBytes());
            permit.globalReadyHeld = false;
        }
    }

    private boolean canQueue(RegionState state, AdmissionKind kind) {
        if (globalWaiters >= tuning.maxGlobalWaiters()) {
            return false;
        }
        if (kind == AdmissionKind.REGULAR && globalRegularWaiters >= regularGlobalWaiterLimit) {
            return false;
        }
        if (state == null) {
            return true;
        }
        return kind == AdmissionKind.CONTINUATION
                ? state.continuationWaiters < continuationWaiterLimit
                : state.regularWaiters < regularWaiterLimit;
    }

    private boolean canAdmitRegular(RegionState state, FoliaBackpressure.Demand demand) {
        long readyChunks = state == null ? 0 : state.readyChunks.get();
        long readyBytes = state == null ? 0 : state.readyBytes.get();
        long finalizers = state == null ? 0 : state.reservedRegularFinalizers.get();
        if (!fits(readyChunks, demand.chunks(), limits.maxReadyChunksPerRegion())
                || !fits(readyBytes, demand.preparedBytes(), limits.maxReadyBytesPerRegion())
                || !fits(finalizers, demand.finalizerChains(), regularFinalizerLimit)) {
            return false;
        }
        return fits(globalReadyChunks.get(), demand.chunks(), limits.maxGlobalReadyChunks())
                && fits(globalReadyBytes.get(), demand.preparedBytes(), limits.maxGlobalReadyBytes())
                && fits(globalFinalizers.get(), demand.finalizerChains(), limits.maxGlobalFinalizers())
                && fits(
                        globalRegularFinalizers.get(),
                        demand.finalizerChains(),
                        regularGlobalFinalizerLimit
                );
    }

    private boolean canAdmitContinuation(RegionState state, FoliaBackpressure.Demand demand) {
        long readyChunks = state == null ? 0 : state.readyChunks.get();
        long readyBytes = state == null ? 0 : state.readyBytes.get();
        long finalizers = state == null ? 0 : state.reservedContinuationFinalizers.get();
        if (!fits(readyChunks, demand.chunks(), limits.maxReadyChunksPerRegion())
                || !fits(readyBytes, demand.preparedBytes(), limits.maxReadyBytesPerRegion())
                || !fits(finalizers, demand.finalizerChains(), continuationFinalizerLimit)) {
            return false;
        }
        return fits(globalReadyChunks.get(), demand.chunks(), limits.maxGlobalReadyChunks())
                && fits(globalReadyBytes.get(), demand.preparedBytes(), limits.maxGlobalReadyBytes())
                && fits(globalFinalizers.get(), demand.finalizerChains(), limits.maxGlobalFinalizers());
    }

    private boolean canTransfer(RegionState target, BackpressurePermit permit) {
        long readyChunks = target == null ? 0 : target.readyChunks.get();
        long readyBytes = target == null ? 0 : target.readyBytes.get();
        if (permit.readyCapacityHeld
                && (!fits(readyChunks, permit.demand.chunks(), limits.maxReadyChunksPerRegion())
                || !fits(readyBytes, permit.demand.preparedBytes(), limits.maxReadyBytesPerRegion()))) {
            return false;
        }
        long finalizers = permit.kind == AdmissionKind.CONTINUATION
                ? target == null ? 0 : target.reservedContinuationFinalizers.get()
                : target == null ? 0 : target.reservedRegularFinalizers.get();
        int limit = permit.kind == AdmissionKind.CONTINUATION
                ? continuationFinalizerLimit
                : regularFinalizerLimit;
        return fits(finalizers, permit.demand.finalizerChains(), limit);
    }

    private void moveRegionalAccounting(
            BackpressurePermit permit,
            RegionState previous,
            RegionState target
    ) {
        if (permit.readyCapacityHeld) {
            previous.readyChunks.addAndGet(-permit.demand.chunks());
            previous.readyBytes.addAndGet(-permit.demand.preparedBytes());
            removeReadySince(previous, permit.readySinceNanos);
            target.readyChunks.addAndGet(permit.demand.chunks());
            target.readyBytes.addAndGet(permit.demand.preparedBytes());
            addReadySince(target, permit.readySinceNanos);
        }
        if (permit.kind == AdmissionKind.CONTINUATION) {
            previous.reservedContinuationFinalizers.addAndGet(-permit.demand.finalizerChains());
            target.reservedContinuationFinalizers.addAndGet(permit.demand.finalizerChains());
        } else {
            previous.reservedRegularFinalizers.addAndGet(-permit.demand.finalizerChains());
            target.reservedRegularFinalizers.addAndGet(permit.demand.finalizerChains());
        }
        switch (permit.stage) {
            case READY -> {
            }
            case SCHEDULED, COMMITTING -> {
                previous.scheduledChunks.addAndGet(-permit.demand.chunks());
                target.scheduledChunks.addAndGet(permit.demand.chunks());
            }
            case FINALIZING -> {
                previous.activeFinalizers.addAndGet(-permit.demand.finalizerChains());
                target.activeFinalizers.addAndGet(permit.demand.finalizerChains());
            }
        }
        previous.livePermits.decrementAndGet();
        target.livePermits.incrementAndGet();
    }

    private Throwable initialRejection(FoliaBackpressure.Demand demand, long now) {
        if (stoppedReason != null) {
            return stoppedReason;
        }
        return deadlineStatus(demand, now) == DeadlineStatus.ACTIVE
                ? null
                : new TimeoutException("backpressure admission deadline expired");
    }

    private DeadlineStatus deadlineStatus(FoliaBackpressure.Demand demand, long now) {
        long remaining = demand.deadlineNanos() - now;
        if (remaining <= 0) {
            return DeadlineStatus.EXPIRED;
        }
        if (remaining > tuning.maxAdmissionWaitNanos()) {
            throw new IllegalArgumentException("admission deadline exceeds the configured maximum wait");
        }
        return DeadlineStatus.ACTIVE;
    }

    private long remainingNanos(FoliaBackpressure.Demand demand, long now) {
        DeadlineStatus status = deadlineStatus(demand, now);
        if (status != DeadlineStatus.ACTIVE) {
            return 0;
        }
        return demand.deadlineNanos() - now;
    }

    private void validateDemand(
            PlanProducerToken registeredPlan,
            RegionKey region,
            FoliaBackpressure.Demand demand
    ) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(demand, "demand");
        Objects.requireNonNull(demand.operationId(), "demand.operationId");
        Objects.requireNonNull(demand.priority(), "demand.priority");
        if (!registeredPlan.operationId().equals(demand.operationId())) {
            throw new IllegalArgumentException("demand operation does not match the bound plan producer");
        }
        deadlineStatus(demand, nanoTime.getAsLong());
    }

    private static void requireUnaccepted(FoliaBackpressure.Demand demand) {
        if (demand.previouslyAccepted()) {
            throw new IllegalArgumentException("previously accepted work must use continuation admission");
        }
    }

    private RegionState requiredState(RegionKey region) {
        RegionState state = regions.get(region);
        if (state == null) {
            throw new IllegalStateException("missing backpressure accounting for region");
        }
        return state;
    }

    private void pruneIfIdle(RegionKey region, RegionState state) {
        regions.computeIfPresent(region, (ignored, current) -> current == state && isIdle(current) ? null : current);
    }

    private void requestPrune(RegionKey region, RegionState state) {
        if (!state.pruneQueued.compareAndSet(false, true)) {
            return;
        }
        // Tick paths reserve at most one bounded queue slot and otherwise request a sweep. Every accounting-lock
        // owner advances the queue/sweep before unlock, so the contention that creates new states also evicts them.
        int slot = pendingPruneRequests.getAndIncrement();
        if (slot >= tuning.maxPendingPrunes()) {
            pendingPruneRequests.decrementAndGet();
            pruneSweepRequested.set(true);
            state.pruneQueued.set(false);
            return;
        }
        pruneRequests.offer(new PruneRequest(region, state));
    }

    private void drainPruneRequestsLocked() {
        int remaining = tuning.waiterServiceBatch();
        while (remaining > 0) {
            PruneRequest request = pruneRequests.poll();
            if (request == null) {
                break;
            }
            pendingPruneRequests.decrementAndGet();
            request.state().pruneQueued.set(false);
            pruneIfIdle(request.region(), request.state());
            remaining--;
        }
        if (remaining == 0) {
            return;
        }
        if (pruneSweep == null && pruneSweepRequested.getAndSet(false)) {
            pruneSweep = regions.entrySet().iterator();
        }
        while (remaining > 0 && pruneSweep != null) {
            if (!pruneSweep.hasNext()) {
                pruneSweep = null;
                break;
            }
            Map.Entry<RegionKey, RegionState> entry = pruneSweep.next();
            pruneIfIdle(entry.getKey(), entry.getValue());
            remaining--;
        }
    }

    private void drainPrunesAndUnlockAccounting() {
        try {
            drainPruneRequestsLocked();
        } finally {
            accountingLock.unlock();
        }
    }

    private static boolean isIdle(RegionState state) {
        return state.livePermits.get() == 0
                && state.readyChunks.get() == 0
                && state.readyBytes.get() == 0
                && state.reservedRegularFinalizers.get() == 0
                && state.reservedContinuationFinalizers.get() == 0
                && state.activeFinalizers.get() == 0
                && state.scheduledChunks.get() == 0
                && state.regularWaiters == 0
                && state.continuationWaiters == 0
                && state.readySince.isEmpty();
    }

    private LinkedHashSet<Waiter> queue(AdmissionKind kind) {
        return kind == AdmissionKind.CONTINUATION ? continuationWaiters : regularWaiters;
    }

    private void installWaiterAccounting(RegionKey region, AdmissionKind kind) {
        globalWaiters++;
        if (kind == AdmissionKind.REGULAR) {
            globalRegularWaiters++;
        }
        regions.compute(region, (ignored, current) -> {
            RegionState state = current == null ? new RegionState() : current;
            if (kind == AdmissionKind.CONTINUATION) {
                state.continuationWaiters++;
            } else {
                state.regularWaiters++;
            }
            return state;
        });
    }

    private void decrementWaiters(RegionState state, AdmissionKind kind) {
        globalWaiters--;
        if (kind == AdmissionKind.CONTINUATION) {
            state.continuationWaiters--;
        } else {
            globalRegularWaiters--;
            state.regularWaiters--;
        }
    }

    private void removeQueued(Waiter waiter) {
        if (!queue(waiter.kind).remove(waiter)) {
            throw new IllegalStateException("queued waiter was not present: " + waiter.attemptId);
        }
        refreshWakeCandidatesLocked();
        removeQueuedAccounting(waiter);
    }

    private void removeQueuedAccounting(Waiter waiter) {
        RegionState state = requiredState(waiter.region);
        decrementWaiters(state, waiter.kind);
        pruneIfIdle(waiter.region, state);
    }

    private static void addReadySince(RegionState state, long readySinceNanos) {
        state.readySince.compute(readySinceNanos, (ignored, count) -> {
            if (count == null) {
                return new AtomicInteger(1);
            }
            count.incrementAndGet();
            return count;
        });
    }

    private static void removeReadySince(RegionState state, long readySinceNanos) {
        state.readySince.compute(readySinceNanos, (ignored, count) -> {
            if (count == null) {
                throw new IllegalStateException("missing ready timestamp");
            }
            return count.decrementAndGet() == 0 ? null : count;
        });
    }

    private boolean rejectAttempt(Waiter waiter, Throwable reason) {
        boolean won = waiter.producer.reject(reason);
        if (won) {
            notifyFailure(waiter, AdmissionFailureOutcome.REJECTED);
        }
        return won;
    }

    private boolean cancelAttempt(Waiter waiter, CancellationException reason) {
        boolean won = waiter.producer.cancel(reason);
        if (won) {
            notifyFailure(waiter, AdmissionFailureOutcome.CANCELLED);
        }
        return won;
    }

    private void notifyFailure(Waiter waiter, AdmissionFailureOutcome outcome) {
        waiter.delivery.future().whenComplete((ignored, failure) -> {
            if (failure == null) {
                LOGGER.error("Admission failure hook observed a successful outcome for " + waiter.attemptId);
                return;
            }
            Throwable reason = failure instanceof CompletionException completionFailure
                    && completionFailure.getCause() != null
                    ? completionFailure.getCause()
                    : failure;
            try {
                rejectionHandler.failed(waiter.region, waiter.demand, outcome, reason);
            } catch (Throwable hookFailure) {
                LOGGER.error("Folia backpressure rejection hook failed for " + waiter.attemptId, hookFailure);
            }
        });
    }

    private long nextAdmissionAttemptId() {
        long sequence = admissionAttemptSequence.incrementAndGet();
        if (sequence <= 0) {
            throw new IllegalStateException("admission attempt identity space exhausted");
        }
        return sequence;
    }

    private static boolean fits(long current, long additional, long limit) {
        return additional <= limit && current <= limit - additional;
    }

    private void updateEwma(AtomicLong target, long sample) {
        while (true) {
            long previous = target.get();
            long updated = previous == 0
                    ? sample
                    : sample >= previous
                    ? previous + ((sample - previous) >> tuning.ewmaWeightShift())
                    : previous - ((previous - sample) >> tuning.ewmaWeightShift());
            if (target.compareAndSet(previous, updated)) {
                return;
            }
        }
    }

    private static FoliaBackpressure.Limits validateLimits(FoliaBackpressure.Limits limits) {
        if (limits.maxReadyChunksPerRegion() <= 0
                || limits.maxReadyBytesPerRegion() <= 0
                || limits.maxFinalizersPerRegion() <= 1
                || limits.maxWaitersPerRegion() <= 1
                || limits.maxGlobalReadyChunks() <= 0
                || limits.maxGlobalReadyBytes() <= 0
                || limits.maxGlobalFinalizers() <= 1) {
            throw new IllegalArgumentException("all backpressure limits must be positive");
        }
        return limits;
    }

    int trackedRegionCount() {
        return regions.size();
    }

    int pendingPruneRequestCount() {
        return pendingPruneRequests.get();
    }

    long sliceChunksEwma(RegionKey region) {
        RegionState state = regions.get(Objects.requireNonNull(region, "region"));
        return state == null ? 0 : state.sliceChunksEwma.get();
    }

    @FunctionalInterface
    interface AdmissionRejectionHandler {

        void failed(
                RegionKey region,
                FoliaBackpressure.Demand demand,
                AdmissionFailureOutcome outcome,
                Throwable reason
        );

    }

    enum AdmissionFailureOutcome {
        CANCELLED,
        REJECTED
    }

    enum TransferResult {
        TRANSFERRED,
        RETRY_BUSY,
        TARGET_SATURATED,
        PERMIT_CLOSED
    }

    interface BoundAdmission extends FoliaBackpressure {

        /** Internal task-14 adapter: RETRY_BUSY is never an admission or saturation refusal. */
        TransferResult transferResult(Permit permit, RegionKey actualRegion);

    }

    @FunctionalInterface
    interface WaiterDeliveryProbe {

        void beforeBatchSubmission();

        default void beforeAttemptSubmission(long attemptId) {
        }

    }

    record Tuning(
            int waiterServiceBatch,
            int maxGlobalWaiters,
            int maxPendingPrunes,
            int ewmaWeightShift,
            long maxAdmissionWaitNanos
    ) {

        static Tuning initial() {
            return new Tuning(64, 65_536, 4_096, 3, TimeUnit.SECONDS.toNanos(30));
        }

        private Tuning validated() {
            if (waiterServiceBatch <= 0
                    || maxGlobalWaiters <= 1
                    || maxPendingPrunes <= 0
                    || ewmaWeightShift <= 0
                    || ewmaWeightShift >= Long.SIZE
                    || maxAdmissionWaitNanos <= 0) {
                throw new IllegalArgumentException("invalid internal backpressure tuning");
            }
            return this;
        }

    }

    private enum AdmissionKind {
        REGULAR,
        CONTINUATION
    }

    private enum DeadlineStatus {
        ACTIVE,
        EXPIRED
    }

    private enum WaiterState {
        NEW,
        QUEUED,
        EXPIRY_LINEARIZED,
        GRANTED,
        DELIVERED,
        REJECTED
    }

    private enum PermitOperation {
        IDLE,
        ACTIVE,
        CLOSED
    }

    private record PruneRequest(RegionKey region, RegionState state) {
    }

    private static final class RegionState {

        private final ConcurrentSkipListMap<Long, AtomicInteger> readySince = new ConcurrentSkipListMap<>();
        private final AtomicLong scheduleDelayEwmaNanos = new AtomicLong();
        private final AtomicLong sliceRuntimeEwmaNanos = new AtomicLong();
        private final AtomicLong sliceChunksEwma = new AtomicLong();
        private final AtomicInteger reservedRegularFinalizers = new AtomicInteger();
        private final AtomicInteger reservedContinuationFinalizers = new AtomicInteger();
        private final AtomicInteger readyChunks = new AtomicInteger();
        private final AtomicLong readyBytes = new AtomicLong();
        private final AtomicInteger activeFinalizers = new AtomicInteger();
        private final AtomicInteger scheduledChunks = new AtomicInteger();
        private final AtomicInteger livePermits = new AtomicInteger();
        private final AtomicBoolean pruneQueued = new AtomicBoolean();
        private volatile int regularWaiters;
        private volatile int continuationWaiters;

    }

    private final class Waiter {

        private final long attemptId;
        private final RegionKey region;
        private final FoliaBackpressure.Demand demand;
        private final AdmissionKind kind;
        private final AdmissionProducer<FoliaBackpressure.Permit> producer;
        private final AdmissionDeliveryLease<FoliaBackpressure.Permit> delivery;
        private final AtomicReference<WaiterState> state = new AtomicReference<>(WaiterState.NEW);
        private final AtomicBoolean deliverySubmitted = new AtomicBoolean();
        private final AtomicBoolean flushExpirySettled = new AtomicBoolean();
        private final AtomicBoolean transitionFailureSettled = new AtomicBoolean();
        private final CancellationException cancellationFailure = new CancellationException(
                "backpressure admission cancelled"
        );
        private final TimeoutException deadlineFailure = new TimeoutException(
                "backpressure admission deadline expired"
        );
        private final Runnable cancellationSettlement = () -> settleCancellation(this);
        private final Runnable expirySettlement = () -> settleExpiry(this);
        private final Runnable deliverySettlement = () -> deliverSettled(this);
        private volatile Throwable failure;
        private volatile ScheduledFuture<?> deadlineTask;
        private BackpressurePermit permit;

        private Waiter(
                long attemptId,
                RegionKey region,
                FoliaBackpressure.Demand demand,
                AdmissionKind kind,
                AdmissionProducer<FoliaBackpressure.Permit> producer,
                AdmissionDeliveryLease<FoliaBackpressure.Permit> delivery
        ) {
            this.attemptId = attemptId;
            this.region = region;
            this.demand = demand;
            this.kind = kind;
            this.producer = producer;
            this.delivery = delivery;
        }

        private boolean linearizeExpiry() {
            return state.compareAndSet(WaiterState.QUEUED, WaiterState.EXPIRY_LINEARIZED);
        }

        private void flushExpired(long deadlineNanos) {
            DefaultFoliaBackpressure.this.flushExpired(this, deadlineNanos);
        }

        private void transitionFailed(Throwable failure) {
            DefaultFoliaBackpressure.this.transitionFailed(this, failure);
        }

    }

    private final class BackpressurePermit implements FoliaBackpressure.Permit {

        private volatile RegionKey region;
        private final FoliaBackpressure.Demand demand;
        private final AdmissionKind kind;
        private final long readySinceNanos;
        private final AtomicReference<PermitOperation> operation = new AtomicReference<>(PermitOperation.IDLE);
        private final AtomicBoolean closeRequested = new AtomicBoolean();
        private volatile FoliaBackpressure.Stage stage = FoliaBackpressure.Stage.READY;
        private boolean globalReadyHeld = true;
        private boolean globalFinalizersHeld = true;
        private boolean readyCapacityHeld = true;
        private volatile boolean closed;

        private BackpressurePermit(
                RegionKey region,
                FoliaBackpressure.Demand demand,
                AdmissionKind kind,
                long readySinceNanos
        ) {
            this.region = region;
            this.demand = demand;
            this.kind = kind;
            this.readySinceNanos = readySinceNanos;
        }

        private DefaultFoliaBackpressure owner() {
            return DefaultFoliaBackpressure.this;
        }

        @Override
        public RegionKey region() {
            return region;
        }

        @Override
        public FoliaBackpressure.Demand demand() {
            return demand;
        }

        @Override
        public FoliaBackpressure.Stage stage() {
            return stage;
        }

        @Override
        public void enter(FoliaBackpressure.Stage next) {
            DefaultFoliaBackpressure.this.enter(this, next);
        }

        @Override
        public void close() {
            DefaultFoliaBackpressure.this.close(this);
        }

    }

    private final class BoundBackpressure implements BoundAdmission {

        private final PlanProducerToken registeredPlan;

        private BoundBackpressure(PlanProducerToken registeredPlan) {
            this.registeredPlan = registeredPlan;
        }

        @Override
        public Limits limits() {
            return limits;
        }

        @Override
        public CompletionStage<Permit> acquire(
                RegionKey region,
                Demand demand,
                CompletionStage<?> cancellationSignal
        ) {
            requireUnaccepted(demand);
            return DefaultFoliaBackpressure.this.acquire(
                    registeredPlan,
                    region,
                    demand,
                    cancellationSignal,
                    AdmissionKind.REGULAR
            );
        }

        @Override
        public Optional<Permit> tryAcquire(RegionKey region, Demand demand) {
            return DefaultFoliaBackpressure.this.tryAcquire(registeredPlan, region, demand);
        }

        @Override
        public CompletionStage<Permit> acquireContinuation(
                RegionKey region,
                Demand demand,
                CompletionStage<?> cancellationSignal
        ) {
            if (!demand.previouslyAccepted()) {
                throw new IllegalArgumentException(
                        "continuation demand must belong to previously accepted work"
                );
            }
            if (demand.finalizerChains() <= 0) {
                throw new IllegalArgumentException("continuation demand must reserve a finalizer chain");
            }
            return DefaultFoliaBackpressure.this.acquire(
                    registeredPlan,
                    region,
                    demand,
                    cancellationSignal,
                    AdmissionKind.CONTINUATION
            );
        }

        @Override
        public boolean transfer(Permit permit, RegionKey actualRegion) {
            return transferResult(permit, actualRegion) == TransferResult.TRANSFERRED;
        }

        @Override
        public TransferResult transferResult(Permit permit, RegionKey actualRegion) {
            Objects.requireNonNull(permit, "permit");
            if (!(permit instanceof DefaultFoliaBackpressure.BackpressurePermit backpressurePermit)
                    || backpressurePermit.owner() != DefaultFoliaBackpressure.this) {
                throw new IllegalArgumentException("permit was not issued by this backpressure instance");
            }
            return DefaultFoliaBackpressure.this.transfer(backpressurePermit, actualRegion);
        }

        @Override
        public Pressure pressure(RegionKey region) {
            return DefaultFoliaBackpressure.this.pressure(region);
        }

        @Override
        public void recordScheduleDelay(RegionKey region, long delayNanos) {
            DefaultFoliaBackpressure.this.recordScheduleDelay(region, delayNanos);
        }

        @Override
        public void recordSlice(RegionKey region, int chunks, long runtimeNanos) {
            DefaultFoliaBackpressure.this.recordSlice(region, chunks, runtimeNanos);
        }

        @Override
        public void stopAccepting(Throwable reason) {
            DefaultFoliaBackpressure.this.stopAccepting(reason);
        }

    }

}
