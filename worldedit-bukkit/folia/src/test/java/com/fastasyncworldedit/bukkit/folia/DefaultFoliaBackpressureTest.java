package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.AppliedReceipt;
import com.fastasyncworldedit.core.util.task.ChunkTerminalRecord;
import com.fastasyncworldedit.core.util.task.DefaultOperationCompletion;
import com.fastasyncworldedit.core.util.task.HistoryPersistencePolicy;
import com.fastasyncworldedit.core.util.task.HistorySettlement;
import com.fastasyncworldedit.core.util.task.OperationCompletionService;
import com.fastasyncworldedit.core.util.task.OperationCompletionService.AdmissionProducer;
import com.fastasyncworldedit.core.util.task.PacketPhaseResult;
import com.fastasyncworldedit.core.util.task.TerminalStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultFoliaBackpressureTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(3);
    private static final DefaultFoliaBackpressure.Tuning TEST_TUNING = new DefaultFoliaBackpressure.Tuning(
            4,
            32,
            32,
            3,
            TimeUnit.SECONDS.toNanos(10)
    );

    public static void main(String[] args) throws Exception {
        TestCase[] tests = {
                DefaultFoliaBackpressureTest::exposesInitialCertificationLimits,
                DefaultFoliaBackpressureTest::rederivedRegionKeyReusesTheSameRegionalAccountingState,
                DefaultFoliaBackpressureTest::asyncAdmissionUsesBoundRegisteredProducer,
                DefaultFoliaBackpressureTest::flushingRejectsBeforeWaiterAccounting,
                DefaultFoliaBackpressureTest::tryAcquireNeverTouchesCompletionLifecycleLock,
                DefaultFoliaBackpressureTest::continuationsConsumeRegionalAndGlobalByteCapacity,
                DefaultFoliaBackpressureTest::globalReadyCapsBindAcrossRegions,
                DefaultFoliaBackpressureTest::continuationsMayUseMoreThanOneRegionsGlobalCarve,
                DefaultFoliaBackpressureTest::globalWaitersReserveContinuationSlots,
                DefaultFoliaBackpressureTest::cancellationHopNeverWaitsForAccounting,
                DefaultFoliaBackpressureTest::concurrentCancelSupersedesGrantedUndeliveredPermit,
                DefaultFoliaBackpressureTest::admissionProducerThreeOutcomeRaceHasOneMatchingWinner,
                DefaultFoliaBackpressureTest::cancelledAttemptCanRetryOnSamePlanSequence,
                DefaultFoliaBackpressureTest::lifecycleRejectionTerminalizesPlan,
                DefaultFoliaBackpressureTest::cancelDeliverRejectRaceProducesOneCoherentOutcome,
                DefaultFoliaBackpressureTest::deadlineExpiresWhileCommonPoolIsSaturated,
                DefaultFoliaBackpressureTest::settledBatchSurvivesSubmissionFailures,
                DefaultFoliaBackpressureTest::flushExpirySettlesEveryGrantedUndeliveredWaiter,
                DefaultFoliaBackpressureTest::stageMachineAndCloseAreConservative,
                DefaultFoliaBackpressureTest::continuationCapacityIsSubtractive,
                DefaultFoliaBackpressureTest::transferRefusesWithoutMovingAccounting,
                DefaultFoliaBackpressureTest::transferDistinguishesBusyFromSaturated,
                DefaultFoliaBackpressureTest::permitTransitionsNeverWaitForAccountingLock,
                DefaultFoliaBackpressureTest::concurrentCloseAndTransferReleaseExactlyOnce,
                DefaultFoliaBackpressureTest::releasedPermitWakesWaiterThroughWaiterProducer,
                DefaultFoliaBackpressureTest::pruneHandoffIsBoundedAndEventuallyEvicts,
                DefaultFoliaBackpressureTest::telemetryDoesNotDropContendedSamples,
                DefaultFoliaBackpressureTest::deadlineValidationRejectsUnboundedHorizons,
                DefaultFoliaBackpressureTest::stopSettlesMultipleProducerBackedBatches
        };
        for (TestCase test : tests) {
            DefaultFoliaBackpressureTest instance = new DefaultFoliaBackpressureTest();
            test.run(instance);
        }
        System.out.println("DefaultFoliaBackpressureTest: " + tests.length + " tests passed");
    }

    @Test
    void exposesInitialCertificationLimits() {
        FoliaBackpressure.Limits limits = DefaultFoliaBackpressure.initialLimits(8L << 30);

        assertEquals(256, limits.maxReadyChunksPerRegion());
        assertEquals(64L << 20, limits.maxReadyBytesPerRegion());
        assertEquals(64, limits.maxFinalizersPerRegion());
        assertEquals(256, limits.maxWaitersPerRegion());
        assertEquals(65_536, limits.maxGlobalReadyChunks());
        assertEquals(1L << 30, limits.maxGlobalReadyBytes());
        assertEquals(4_096, limits.maxGlobalFinalizers());
    }

    @Test
    void rederivedRegionKeyReusesTheSameRegionalAccountingState() {
        UUID worldId = UUID.randomUUID();
        RegionKey original = new RegionKey(worldId, 42);
        RegionKey rederived = new RegionKey(worldId, 42);
        try (Harness harness = harness(limits(1, 1_024, 4, 8, 16, 16_384, 16), 1)) {
            FoliaBackpressure.Permit holder = harness.plan().backpressure().tryAcquire(
                    original,
                    harness.demand(1, 16, 0, false)
            ).orElseThrow();

            assertEquals(original, rederived);
            assertEquals(original.hashCode(), rederived.hashCode());
            assertTrue(harness.plan().backpressure().tryAcquire(
                    rederived,
                    harness.demand(1, 16, 0, false)
            ).isEmpty());
            assertEquals(1, harness.backpressure.trackedRegionCount());

            holder.close();
        }
    }

    @Test
    void asyncAdmissionUsesBoundRegisteredProducer() throws Exception {
        try (Harness harness = harness(limits(2, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            BoundPlan plan = harness.plan();
            AtomicReference<Thread> consumerThread = new AtomicReference<>();
            CompletionStage<FoliaBackpressure.Permit> stage = plan.backpressure().acquire(
                    new RegionKey(),
                    harness.demand(1, 128, 1, false),
                    new CompletableFuture<>()
            );
            FoliaBackpressure.Permit permit = await(stage);
            CountDownLatch observed = new CountDownLatch(1);
            stage.whenComplete((ignored, failure) -> {
                consumerThread.set(Thread.currentThread());
                observed.countDown();
            });

            assertTrue(observed.await(1, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread(), consumerThread.get());
            FoliaBackpressure.Demand foreignOperation = new FoliaBackpressure.Demand(
                    UUID.randomUUID(),
                    1,
                    128,
                    1,
                    FoliaBackpressure.Priority.NORMAL,
                    false,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            );
            assertThrows(IllegalArgumentException.class, () -> plan.backpressure().acquire(
                    new RegionKey(),
                    foreignOperation,
                    new CompletableFuture<>()
            ));
            awaitCondition(() -> harness.service.outstandingAdmissionProducerCount() == 0);
            permit.close();
        }
    }

    @Test
    void flushingRejectsBeforeWaiterAccounting() {
        Harness harness = harness(limits(2, 1_024, 4, 4, 8, 8_192, 8), 1);
        try {
            BoundPlan plan = harness.plan();
            RegionKey region = new RegionKey();
            harness.completion.closeAdmission();
            CompletionStage<Void> flushed = harness.service.flush(Duration.ofMillis(30));

            assertThrows(RejectedExecutionException.class, () -> plan.backpressure().acquire(
                    region,
                    harness.demand(1, 64, 1, false),
                    new CompletableFuture<>()
            ));
            assertEquals(0, harness.planView.pressure(region).waiters());
            assertEquals(0, harness.planView.pressure(region).readyBytes());
            await(harness.completion.future());
            await(flushed);
            harness.markClosed();
        } finally {
            harness.close();
        }
    }

    @Test
    void tryAcquireNeverTouchesCompletionLifecycleLock() throws Exception {
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            BoundPlan plan = harness.plan();
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit holder = plan.backpressure().tryAcquire(
                    region,
                    harness.demand(1, 64, 1, false)
            ).orElseThrow();
            ReentrantLock lifecycleLock = reflectedLock(harness.service, "lifecycleLock");
            long producersBefore = harness.service.outstandingAdmissionProducerCount();
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread lockHolder = Thread.ofPlatform().start(() -> {
                lifecycleLock.lock();
                try {
                    locked.countDown();
                    awaitLatch(release);
                } finally {
                    lifecycleLock.unlock();
                }
            });
            assertTrue(locked.await(1, TimeUnit.SECONDS));

            long started = System.nanoTime();
            Optional<FoliaBackpressure.Permit> acquired = harness.planView.tryAcquire(
                    region,
                    harness.demand(1, 64, 1, false)
            );
            long elapsed = System.nanoTime() - started;

            assertTrue(acquired.isEmpty());
            assertTrue(elapsed < TimeUnit.MILLISECONDS.toNanos(200));
            release.countDown();
            lockHolder.join(1_000);
            assertFalse(lockHolder.isAlive());
            assertEquals(producersBefore, harness.service.outstandingAdmissionProducerCount());
            holder.close();
        }
    }

    @Test
    void continuationsConsumeRegionalAndGlobalByteCapacity() {
        try (Harness harness = harness(limits(4, 128, 4, 4, 8, 160, 8), 1)) {
            RegionKey firstRegion = new RegionKey();
            RegionKey secondRegion = new RegionKey();
            BoundPlan firstPlan = harness.plan();
            BoundPlan secondPlan = harness.plan();
            FoliaBackpressure.Permit first = await(firstPlan.backpressure().acquireContinuation(
                    firstRegion,
                    harness.demand(1, 100, 1, true),
                    new CompletableFuture<>()
            ));
            CompletionStage<FoliaBackpressure.Permit> blocked = secondPlan.backpressure().acquireContinuation(
                    secondRegion,
                    harness.demand(1, 80, 1, true),
                    new CompletableFuture<>()
            );

            assertEquals(100, firstPlan.backpressure().pressure(firstRegion).readyBytes());
            assertEquals(1, secondPlan.backpressure().pressure(secondRegion).waiters());
            assertFalse(blocked.toCompletableFuture().isDone());

            first.close();
            FoliaBackpressure.Permit second = await(blocked);
            assertEquals(80, secondPlan.backpressure().pressure(secondRegion).readyBytes());
            second.close();
        }
    }

    @Test
    void globalReadyCapsBindAcrossRegions() {
        try (Harness harness = harness(limits(4, 1_024, 4, 4, 2, 150, 8), 1)) {
            BoundPlan firstPlan = harness.plan();
            BoundPlan secondPlan = harness.plan();
            FoliaBackpressure.Permit first = firstPlan.backpressure().tryAcquire(
                    new RegionKey(),
                    harness.demand(1, 100, 1, false)
            ).orElseThrow();

            assertTrue(secondPlan.backpressure().tryAcquire(
                    new RegionKey(),
                    harness.demand(1, 60, 1, false)
            ).isEmpty());
            first.close();
        }
    }

    @Test
    void continuationsMayUseMoreThanOneRegionsGlobalCarve() {
        try (Harness harness = harness(limits(4, 1_024, 4, 4, 16, 16_384, 6), 1)) {
            List<FoliaBackpressure.Permit> permits = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                BoundPlan plan = harness.plan();
                permits.add(await(plan.backpressure().acquireContinuation(
                        new RegionKey(),
                        harness.demand(1, 16, 1, true),
                        new CompletableFuture<>()
                )));
            }

            permits.forEach(FoliaBackpressure.Permit::close);
        }
    }

    @Test
    void globalWaitersReserveContinuationSlots() {
        DefaultFoliaBackpressure.Tuning tuning = new DefaultFoliaBackpressure.Tuning(
                2,
                4,
                4,
                3,
                TimeUnit.SECONDS.toNanos(5)
        );
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 3), 1, tuning, () -> {
        })) {
            RegionKey regularRegion = new RegionKey();
            BoundPlan holderPlan = harness.plan();
            FoliaBackpressure.Permit holder = holderPlan.backpressure().tryAcquire(
                    regularRegion,
                    harness.demand(1, 16, 1, false)
            ).orElseThrow();
            CompletionStage<FoliaBackpressure.Permit> regularWaiter = harness.plan().backpressure().acquire(
                    regularRegion,
                    harness.demand(1, 16, 1, false),
                    new CompletableFuture<>()
            );
            CompletionStage<FoliaBackpressure.Permit> excludedRegular = harness.plan().backpressure().acquire(
                    new RegionKey(),
                    harness.demand(8, 16, 1, false),
                    new CompletableFuture<>()
            );
            assertInstanceOf(CompletionException.class, assertThrows(
                    CompletionException.class,
                    () -> excludedRegular.toCompletableFuture().join()
            ));

            RegionKey continuationRegion = new RegionKey();
            BoundPlan continuationHolderPlan = harness.plan();
            FoliaBackpressure.Permit continuationHolder = await(
                    continuationHolderPlan.backpressure().acquireContinuation(
                            continuationRegion,
                            harness.demand(1, 16, 1, true),
                            new CompletableFuture<>()
                    )
            );
            CompletionStage<FoliaBackpressure.Permit> continuationWaiter = harness.plan()
                    .backpressure()
                    .acquireContinuation(
                            continuationRegion,
                            harness.demand(1, 16, 1, true),
                            new CompletableFuture<>()
                    );

            assertFalse(regularWaiter.toCompletableFuture().isDone());
            assertFalse(continuationWaiter.toCompletableFuture().isDone());
            continuationHolder.close();
            await(continuationWaiter).close();
            holder.close();
            await(regularWaiter).close();
        }
    }

    @Test
    void cancellationHopNeverWaitsForAccounting() throws Exception {
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit holder = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 16, 1, false)
            ).orElseThrow();
            CompletableFuture<Void> cancellation = new CompletableFuture<>();
            BoundPlan waitingPlan = harness.plan();
            CompletionStage<FoliaBackpressure.Permit> waiting = waitingPlan.backpressure().acquire(
                    region,
                    harness.demand(1, 16, 1, false),
                    cancellation
            );
            ReentrantLock accountingLock = reflectedLock(harness.backpressure, "accountingLock");
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread lockHolder = Thread.ofPlatform().start(() -> {
                accountingLock.lock();
                try {
                    locked.countDown();
                    awaitLatch(release);
                } finally {
                    accountingLock.unlock();
                }
            });
            assertTrue(locked.await(1, TimeUnit.SECONDS));

            ExecutorService tick = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> completedInlineHop = tick.submit(() -> cancellation.complete(null));
                assertTrue(completedInlineHop.get(200, TimeUnit.MILLISECONDS));
            } finally {
                tick.shutdownNow();
            }

            release.countDown();
            lockHolder.join(1_000);
            assertThrows(CancellationException.class, () -> waiting.toCompletableFuture().join());
            holder.close();
        }
    }

    @Test
    void concurrentCancelSupersedesGrantedUndeliveredPermit() throws Exception {
        CountDownLatch grantSettled = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        AtomicReference<DefaultFoliaBackpressure.AdmissionFailureOutcome> observedOutcome =
                new AtomicReference<>();
        DefaultFoliaBackpressure.WaiterDeliveryProbe barrier = () -> {
            grantSettled.countDown();
            awaitLatch(releaseDelivery);
        };
        try (Harness harness = harness(
                limits(1, 1_024, 4, 4, 8, 8_192, 8),
                1,
                TEST_TUNING,
                barrier,
                outcomeHandler(observedOutcome)
        )) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit holder = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 128, 1, false)
            ).orElseThrow();
            CompletableFuture<Void> cancellation = new CompletableFuture<>();
            BoundPlan retryingPlan = harness.plan();
            CompletionStage<FoliaBackpressure.Permit> waiting = retryingPlan.backpressure().acquire(
                    region,
                    harness.demand(1, 128, 1, false),
                    cancellation
            );

            holder.close();
            assertTrue(grantSettled.await(1, TimeUnit.SECONDS));
            assertEquals(128, harness.planView.pressure(region).readyBytes());
            Thread tick = Thread.ofPlatform().start(() -> cancellation.complete(null));
            tick.join(200);
            assertFalse(tick.isAlive());
            releaseDelivery.countDown();

            assertThrows(CancellationException.class, () -> waiting.toCompletableFuture().join());
            awaitCondition(() -> harness.planView.pressure(region).readyBytes() == 0);
            awaitCondition(() -> observedOutcome.get() != null);
            assertSame(DefaultFoliaBackpressure.AdmissionFailureOutcome.CANCELLED, observedOutcome.get());
            FoliaBackpressure.Permit retried = await(retryingPlan.backpressure().acquire(
                    region,
                    harness.demand(1, 128, 1, false),
                    new CompletableFuture<>()
            ));
            retried.close();
        } finally {
            releaseDelivery.countDown();
        }
    }

    @Test
    void admissionProducerThreeOutcomeRaceHasOneMatchingWinner() throws Exception {
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            BoundPlan plan = harness.plan();
            AdmissionProducer<String> producer = harness.service
                    .<String>tryAcquireAdmissionProducer(10_000, plan.token())
                    .orElseThrow();
            assertTrue(producer.arm(deadlineNanos -> {
            }, failure -> {
                throw new AssertionError(failure);
            }));
            IllegalStateException rejection = new IllegalStateException("rejected");
            CancellationException cancellation = new CancellationException("cancelled");
            AtomicReference<Boolean> delivered = new AtomicReference<>();
            AtomicReference<Boolean> rejected = new AtomicReference<>();
            AtomicReference<Boolean> cancelled = new AtomicReference<>();
            CountDownLatch ready = new CountDownLatch(3);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService racers = Executors.newFixedThreadPool(3);
            try {
                Future<?> deliver = racers.submit(() -> race(
                        ready,
                        start,
                        () -> delivered.set(producer.delivery().deliver("granted"))
                ));
                Future<?> reject = racers.submit(() -> race(
                        ready,
                        start,
                        () -> rejected.set(producer.reject(rejection))
                ));
                Future<?> cancel = racers.submit(() -> race(
                        ready,
                        start,
                        () -> cancelled.set(producer.cancel(cancellation))
                ));
                assertTrue(ready.await(1, TimeUnit.SECONDS));
                start.countDown();
                deliver.get(1, TimeUnit.SECONDS);
                reject.get(1, TimeUnit.SECONDS);
                cancel.get(1, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                racers.shutdownNow();
            }

            assertEquals(1, winnerCount(delivered, rejected, cancelled));
            Object outcome = await(producer.delivery().future().handle(
                    (value, failure) -> failure == null ? value : failure
            ));
            if (Boolean.TRUE.equals(delivered.get())) {
                assertEquals("granted", outcome);
            } else if (Boolean.TRUE.equals(rejected.get())) {
                assertSame(rejection, outcome);
            } else {
                assertSame(cancellation, outcome);
            }
        }
    }

    @Test
    void cancelledAttemptCanRetryOnSamePlanSequence() {
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit holder = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 16, 1, false)
            ).orElseThrow();
            BoundPlan retryingPlan = harness.plan();
            CompletableFuture<Void> cancellation = new CompletableFuture<>();
            CompletionStage<FoliaBackpressure.Permit> cancelled = retryingPlan.backpressure().acquire(
                    region,
                    harness.demand(1, 16, 1, false),
                    cancellation
            );

            cancellation.complete(null);
            assertThrows(CancellationException.class, () -> cancelled.toCompletableFuture().join());
            holder.close();

            FoliaBackpressure.Permit retried = await(retryingPlan.backpressure().acquire(
                    region,
                    harness.demand(1, 16, 1, false),
                    new CompletableFuture<>()
            ));
            retried.close();
        }
    }

    @Test
    void lifecycleRejectionTerminalizesPlan() {
        AtomicReference<DefaultFoliaBackpressure.AdmissionFailureOutcome> observedOutcome =
                new AtomicReference<>();
        try (Harness harness = harness(
                limits(1, 1_024, 4, 4, 8, 8_192, 8),
                1,
                TEST_TUNING,
                () -> {
                },
                outcomeHandler(observedOutcome)
        )) {
            BoundPlan rejectedPlan = harness.plan();
            CancellationException stopped = new CancellationException("lifecycle stopped");
            harness.planView.stopAccepting(stopped);

            CancellationException failure = assertThrows(CancellationException.class, () -> rejectedPlan
                    .backpressure()
                    .acquire(
                            new RegionKey(),
                            harness.demand(1, 16, 1, false),
                            new CompletableFuture<>()
                    )
                    .toCompletableFuture()
                    .join());

            assertSame(stopped, failure.getCause());
            awaitCondition(() -> harness.completion.outstandingTerminalCount() == 1);
            assertFalse(harness.terminal(rejectedPlan, TerminalStatus.NO_CHANGE));
            awaitCondition(() -> observedOutcome.get() != null);
            assertSame(DefaultFoliaBackpressure.AdmissionFailureOutcome.REJECTED, observedOutcome.get());
        }
    }

    @Test
    void cancelDeliverRejectRaceProducesOneCoherentOutcome() throws Exception {
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit holder = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 16, 1, false)
            ).orElseThrow();
            BoundPlan racingPlan = harness.plan();
            CompletableFuture<Void> cancellation = new CompletableFuture<>();
            CompletionStage<FoliaBackpressure.Permit> racing = racingPlan.backpressure().acquire(
                    region,
                    harness.demand(1, 16, 1, false),
                    cancellation
            );
            CountDownLatch ready = new CountDownLatch(3);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService racers = Executors.newFixedThreadPool(3);
            try {
                Future<?> cancel = racers.submit(() -> race(ready, start, () -> cancellation.complete(null)));
                Future<?> deliver = racers.submit(() -> race(ready, start, holder::close));
                IllegalStateException stopped = new IllegalStateException("race rejection");
                Future<?> reject = racers.submit(() -> race(ready, start, () -> harness.planView.stopAccepting(stopped)));
                assertTrue(ready.await(1, TimeUnit.SECONDS));
                start.countDown();
                cancel.get(1, TimeUnit.SECONDS);
                deliver.get(1, TimeUnit.SECONDS);
                reject.get(1, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                racers.shutdownNow();
            }

            Object outcome = await(racing.handle((permit, failure) -> failure == null ? permit : failure));
            if (outcome instanceof FoliaBackpressure.Permit permit) {
                assertTrue(harness.terminal(racingPlan, TerminalStatus.NO_CHANGE));
                permit.close();
            } else if (outcome instanceof CancellationException) {
                assertTrue(harness.terminal(racingPlan, TerminalStatus.NO_CHANGE));
            } else {
                assertInstanceOf(IllegalStateException.class, outcome);
                awaitCondition(() -> harness.completion.outstandingTerminalCount() == 1);
                assertFalse(harness.terminal(racingPlan, TerminalStatus.NO_CHANGE));
            }
            awaitCondition(() -> harness.planView.pressure(region).readyBytes() == 0);
        }
    }

    @Test
    void deadlineExpiresWhileCommonPoolIsSaturated() throws Exception {
        ForkJoinPool commonPool = ForkJoinPool.commonPool();
        int workers = Math.max(1, commonPool.getParallelism());
        CountDownLatch entered = new CountDownLatch(workers);
        CountDownLatch release = new CountDownLatch(1);
        List<CompletableFuture<Void>> blockers = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            blockers.add(CompletableFuture.runAsync(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }, commonPool));
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
                RegionKey region = new RegionKey();
                FoliaBackpressure.Permit holder = harness.plan().backpressure().tryAcquire(
                        region,
                        harness.demand(1, 16, 1, false)
                ).orElseThrow();
                BoundPlan expiringPlan = harness.plan();
                FoliaBackpressure.Demand expiring = harness.demandWithDeadline(
                        1,
                        16,
                        1,
                        false,
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)
                );
                CompletionStage<FoliaBackpressure.Permit> stage = expiringPlan.backpressure().acquire(
                        region,
                        expiring,
                        new CompletableFuture<>()
                );

                long started = System.nanoTime();
                awaitCondition(() -> expiringPlan.backpressure().pressure(region).waiters() == 0);
                assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
                release.countDown();
                blockers.forEach(CompletableFuture::join);
                CompletionException failure = assertThrows(
                        CompletionException.class,
                        () -> stage.toCompletableFuture().join()
                );
                assertInstanceOf(TimeoutException.class, failure.getCause());
                assertEquals("backpressure admission deadline expired", failure.getCause().getMessage());
                holder.close();
            }
        } finally {
            release.countDown();
            blockers.forEach(CompletableFuture::join);
        }
    }

    @Test
    void settledBatchSurvivesSubmissionFailures() {
        AtomicInteger attemptSubmissions = new AtomicInteger();
        DefaultFoliaBackpressure.WaiterDeliveryProbe probe = new DefaultFoliaBackpressure.WaiterDeliveryProbe() {
            @Override
            public void beforeBatchSubmission() {
                throw new IllegalStateException("injected batch preparation failure");
            }

            @Override
            public void beforeAttemptSubmission(long attemptId) {
                if (attemptSubmissions.incrementAndGet() == 2) {
                    throw new IllegalStateException("injected attempt submission failure " + attemptId);
                }
            }
        };
        try (Harness harness = harness(
                limits(3, 3_072, 8, 8, 16, 16_384, 16),
                1,
                TEST_TUNING,
                probe
        )) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit holder = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(3, 384, 1, false)
            ).orElseThrow();
            List<CompletionStage<FoliaBackpressure.Permit>> waiters = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                waiters.add(harness.plan().backpressure().acquire(
                        region,
                        harness.demand(1, 128, 1, false),
                        new CompletableFuture<>()
                ));
            }

            holder.close();
            int granted = 0;
            int rejected = 0;
            for (CompletionStage<FoliaBackpressure.Permit> waiter : waiters) {
                Object outcome = await(waiter.handle((permit, failure) -> failure == null ? permit : failure));
                if (outcome instanceof FoliaBackpressure.Permit permit) {
                    granted++;
                    permit.close();
                } else {
                    assertInstanceOf(IllegalStateException.class, outcome);
                    rejected++;
                }
            }
            assertEquals(2, granted);
            assertEquals(1, rejected);
            awaitCondition(() -> harness.service.outstandingAdmissionProducerCount() == 0);
            awaitCondition(() -> harness.planView.pressure(region).readyBytes() == 0);
            awaitCondition(() -> harness.backpressure.trackedRegionCount() == 0);
        }
    }

    @Test
    void flushExpirySettlesEveryGrantedUndeliveredWaiter() throws Exception {
        CountDownLatch grantsSettled = new CountDownLatch(1);
        CountDownLatch releaseDelivery = new CountDownLatch(1);
        AtomicInteger barrierCalls = new AtomicInteger();
        DefaultFoliaBackpressure.WaiterDeliveryProbe barrier = () -> {
            if (barrierCalls.getAndIncrement() == 0) {
                grantsSettled.countDown();
                awaitLatch(releaseDelivery);
            }
        };
        Harness harness = harness(limits(3, 4_096, 6, 8, 12, 16_384, 12), 1, TEST_TUNING, barrier);
        try {
            RegionKey region = new RegionKey();
            List<FoliaBackpressure.Permit> holders = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                holders.add(harness.plan().backpressure().tryAcquire(
                        region,
                        harness.demand(1, 128, 1, false)
                ).orElseThrow());
            }
            List<CompletionStage<FoliaBackpressure.Permit>> waiters = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                waiters.add(harness.plan().backpressure().acquire(
                        region,
                        harness.demand(1, 128, 1, false),
                        new CompletableFuture<>()
                ));
            }

            holders.forEach(FoliaBackpressure.Permit::close);
            assertTrue(grantsSettled.await(1, TimeUnit.SECONDS));
            harness.completion.closeAdmission();
            CompletionStage<Void> flushed = harness.service.flush(Duration.ofMillis(60));
            Thread.sleep(100);
            releaseDelivery.countDown();

            for (CompletionStage<FoliaBackpressure.Permit> waiter : waiters) {
                assertThrows(CompletionException.class, () -> waiter.toCompletableFuture().join());
            }
            await(flushed);
            await(harness.completion.future());
            assertEquals(0, harness.planView.pressure(region).readyBytes());
            assertEquals(0, harness.backpressure.trackedRegionCount());
            assertEquals(OperationCompletionService.State.TERMINATED, harness.service.state());
            harness.markClosed();
        } finally {
            releaseDelivery.countDown();
            harness.close();
        }
    }

    @Test
    void stageMachineAndCloseAreConservative() {
        try (Harness harness = harness(limits(2, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit permit = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 128, 1, false)
            ).orElseThrow();

            permit.enter(FoliaBackpressure.Stage.SCHEDULED);
            permit.enter(FoliaBackpressure.Stage.COMMITTING);
            permit.enter(FoliaBackpressure.Stage.FINALIZING);
            assertEquals(0, harness.planView.pressure(region).readyBytes());
            assertEquals(1, harness.planView.pressure(region).finalizerChains());
            permit.close();
            permit.close();
            assertEquals(0, harness.planView.pressure(region).finalizerChains());

            FoliaBackpressure.Permit invalid = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 64, 1, false)
            ).orElseThrow();
            assertThrows(
                    IllegalStateException.class,
                    () -> invalid.enter(FoliaBackpressure.Stage.COMMITTING)
            );
            assertEquals(0, harness.planView.pressure(region).readyBytes());
        }
    }

    @Test
    void continuationCapacityIsSubtractive() {
        try (Harness harness = harness(limits(8, 8_192, 4, 4, 16, 16_384, 8), 1)) {
            RegionKey region = new RegionKey();
            List<FoliaBackpressure.Permit> regular = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                regular.add(harness.plan().backpressure().tryAcquire(
                        region,
                        harness.demand(1, 16, 1, false)
                ).orElseThrow());
            }
            assertTrue(harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 16, 1, false)
            ).isEmpty());

            FoliaBackpressure.Permit continuation = await(harness.plan().backpressure().acquireContinuation(
                    region,
                    harness.demand(1, 16, 1, true),
                    new CompletableFuture<>()
            ));
            continuation.close();
            regular.forEach(FoliaBackpressure.Permit::close);
        }
    }

    @Test
    void transferRefusesWithoutMovingAccounting() {
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            RegionKey first = new RegionKey();
            RegionKey second = new RegionKey();
            FoliaBackpressure.Permit moving = harness.plan().backpressure().tryAcquire(
                    first,
                    harness.demand(1, 128, 1, false)
            ).orElseThrow();
            FoliaBackpressure.Permit occupying = harness.plan().backpressure().tryAcquire(
                    second,
                    harness.demand(1, 128, 1, false)
            ).orElseThrow();

            assertFalse(harness.planView.transfer(moving, second));
            assertSame(first, moving.region());
            assertEquals(128, harness.planView.pressure(first).readyBytes());
            occupying.close();
            assertTrue(harness.planView.transfer(moving, second));
            assertSame(second, moving.region());
            assertEquals(0, harness.planView.pressure(first).readyBytes());
            assertEquals(128, harness.planView.pressure(second).readyBytes());
            moving.close();
        }
    }

    @Test
    void transferDistinguishesBusyFromSaturated() throws Exception {
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            RegionKey source = new RegionKey();
            RegionKey target = new RegionKey();
            FoliaBackpressure.Permit moving = harness.planView.tryAcquire(
                    source,
                    harness.demand(1, 128, 1, false)
            ).orElseThrow();
            FoliaBackpressure.Permit occupying = harness.plan().backpressure().tryAcquire(
                    target,
                    harness.demand(1, 128, 1, false)
            ).orElseThrow();
            ReentrantLock accountingLock = reflectedLock(harness.backpressure, "accountingLock");
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread lockHolder = Thread.ofPlatform().start(() -> {
                accountingLock.lock();
                try {
                    locked.countDown();
                    awaitLatch(release);
                } finally {
                    accountingLock.unlock();
                }
            });
            assertTrue(locked.await(1, TimeUnit.SECONDS));

            assertSame(
                    DefaultFoliaBackpressure.TransferResult.RETRY_BUSY,
                    harness.planView.transferResult(moving, target)
            );
            assertSame(source, moving.region());
            release.countDown();
            lockHolder.join(1_000);

            assertSame(
                    DefaultFoliaBackpressure.TransferResult.TARGET_SATURATED,
                    harness.planView.transferResult(moving, target)
            );
            assertSame(source, moving.region());
            occupying.close();
            assertSame(
                    DefaultFoliaBackpressure.TransferResult.TRANSFERRED,
                    harness.planView.transferResult(moving, target)
            );
            moving.close();
        }
    }

    @Test
    void permitTransitionsNeverWaitForAccountingLock() throws Exception {
        try (Harness harness = harness(limits(4, 4_096, 8, 8, 16, 16_384, 16), 1)) {
            RegionKey transferSource = new RegionKey();
            RegionKey transferTarget = new RegionKey();
            RegionKey finalizingRegion = new RegionKey();
            RegionKey closingRegion = new RegionKey();
            FoliaBackpressure.Permit transferring = harness.plan().backpressure().tryAcquire(
                    transferSource,
                    harness.demand(1, 64, 1, false)
            ).orElseThrow();
            FoliaBackpressure.Permit finalizing = harness.plan().backpressure().tryAcquire(
                    finalizingRegion,
                    harness.demand(1, 64, 1, false)
            ).orElseThrow();
            finalizing.enter(FoliaBackpressure.Stage.SCHEDULED);
            finalizing.enter(FoliaBackpressure.Stage.COMMITTING);
            FoliaBackpressure.Permit closing = harness.plan().backpressure().tryAcquire(
                    closingRegion,
                    harness.demand(1, 64, 1, false)
            ).orElseThrow();
            ReentrantLock accountingLock = reflectedLock(harness.backpressure, "accountingLock");
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread lockHolder = Thread.ofPlatform().start(() -> {
                accountingLock.lock();
                try {
                    locked.countDown();
                    awaitLatch(release);
                } finally {
                    accountingLock.unlock();
                }
            });
            assertTrue(locked.await(1, TimeUnit.SECONDS));

            ExecutorService tickThreads = Executors.newFixedThreadPool(3);
            try {
                Future<Boolean> transfer = tickThreads.submit(
                        () -> harness.planView.transfer(transferring, transferTarget)
                );
                Future<?> enterFinalizing = tickThreads.submit(
                        () -> finalizing.enter(FoliaBackpressure.Stage.FINALIZING)
                );
                Future<?> close = tickThreads.submit(closing::close);

                assertFalse(transfer.get(200, TimeUnit.MILLISECONDS));
                enterFinalizing.get(200, TimeUnit.MILLISECONDS);
                close.get(200, TimeUnit.MILLISECONDS);
                assertEquals(0, harness.planView.pressure(finalizingRegion).readyBytes());
                assertEquals(0, harness.planView.pressure(closingRegion).readyBytes());
            } finally {
                release.countDown();
                tickThreads.shutdownNow();
                lockHolder.join(1_000);
            }
            transferring.close();
            finalizing.close();
        }
    }

    @Test
    void concurrentCloseAndTransferReleaseExactlyOnce() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            try (Harness harness = harness(limits(1, 1_024, 4, 4, 1, 1_024, 4), 1)) {
                RegionKey source = new RegionKey();
                RegionKey target = new RegionKey();
                FoliaBackpressure.Permit racing = harness.plan().backpressure().tryAcquire(
                        source,
                        harness.demand(1, 64, 1, false)
                ).orElseThrow();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                ExecutorService racers = Executors.newFixedThreadPool(2);
                try {
                    Future<?> close = racers.submit(() -> race(ready, start, racing::close));
                    Future<Boolean> transfer = racers.submit(
                            () -> raceResult(ready, start, () -> harness.planView.transfer(racing, target))
                    );
                    assertTrue(ready.await(1, TimeUnit.SECONDS));
                    start.countDown();
                    close.get(1, TimeUnit.SECONDS);
                    transfer.get(1, TimeUnit.SECONDS);
                } finally {
                    start.countDown();
                    racers.shutdownNow();
                }

                assertEquals(0, harness.planView.pressure(source).readyBytes());
                assertEquals(0, harness.planView.pressure(target).readyBytes());
                FoliaBackpressure.Permit probe = harness.plan().backpressure().tryAcquire(
                        new RegionKey(),
                        harness.demand(1, 64, 1, false)
                ).orElseThrow();
                assertTrue(harness.plan().backpressure().tryAcquire(
                        new RegionKey(),
                        harness.demand(1, 64, 1, false)
                ).isEmpty());
                probe.close();
            }
        }
    }

    @Test
    void releasedPermitWakesWaiterThroughWaiterProducer() {
        try (Harness harness = harness(limits(1, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit releasedProducer = await(harness.plan().backpressure().acquire(
                    region,
                    harness.demand(1, 64, 1, false),
                    new CompletableFuture<>()
            ));
            awaitCondition(() -> harness.service.outstandingAdmissionProducerCount() == 0);
            CompletionStage<FoliaBackpressure.Permit> waiting = harness.plan().backpressure().acquire(
                    region,
                    harness.demand(1, 64, 1, false),
                    new CompletableFuture<>()
            );
            assertEquals(1, harness.service.outstandingAdmissionProducerCount());

            releasedProducer.close();

            FoliaBackpressure.Permit admitted = await(waiting);
            admitted.close();
        }
    }

    @Test
    void pruneHandoffIsBoundedAndEventuallyEvicts() throws Exception {
        DefaultFoliaBackpressure.Tuning tuning = new DefaultFoliaBackpressure.Tuning(
                2,
                3,
                3,
                3,
                TimeUnit.SECONDS.toNanos(5)
        );
        try (Harness harness = harness(limits(8, 8_192, 16, 16, 16, 16_384, 16), 1, tuning, () -> {
        })) {
            List<FoliaBackpressure.Permit> permits = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                permits.add(harness.plan().backpressure().tryAcquire(
                        new RegionKey(),
                        harness.demand(1, 64, 1, false)
                ).orElseThrow());
            }
            ReentrantLock accountingLock = reflectedLock(harness.backpressure, "accountingLock");
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread lockHolder = Thread.ofPlatform().start(() -> {
                accountingLock.lock();
                try {
                    locked.countDown();
                    awaitLatch(release);
                } finally {
                    accountingLock.unlock();
                }
            });
            assertTrue(locked.await(1, TimeUnit.SECONDS));

            permits.forEach(FoliaBackpressure.Permit::close);
            assertTrue(harness.backpressure.pendingPruneRequestCount() <= tuning.maxPendingPrunes());
            release.countDown();
            lockHolder.join(1_000);

            for (int i = 0; i < 8; i++) {
                FoliaBackpressure.Permit probe = harness.plan().backpressure().tryAcquire(
                        new RegionKey(),
                        harness.demand(1, 64, 1, false)
                ).orElseThrow();
                probe.close();
            }
            awaitCondition(() -> harness.backpressure.pendingPruneRequestCount() == 0);
            awaitCondition(() -> harness.backpressure.trackedRegionCount() == 0);
        }
    }

    @Test
    void telemetryDoesNotDropContendedSamples() throws Exception {
        try (Harness harness = harness(limits(2, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit permit = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 128, 1, false)
            ).orElseThrow();
            ReentrantLock accountingLock = reflectedLock(harness.backpressure, "accountingLock");
            CountDownLatch locked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread lockHolder = Thread.ofPlatform().start(() -> {
                accountingLock.lock();
                try {
                    locked.countDown();
                    awaitLatch(release);
                } finally {
                    accountingLock.unlock();
                }
            });
            assertTrue(locked.await(1, TimeUnit.SECONDS));
            try {
                harness.planView.recordScheduleDelay(region, 80);
                harness.planView.recordSlice(region, 4, 160);
            } finally {
                release.countDown();
                lockHolder.join(1_000);
            }

            assertEquals(80, harness.planView.pressure(region).scheduleDelayEwmaNanos());
            assertEquals(160, harness.planView.pressure(region).sliceRuntimeEwmaNanos());
            assertEquals(4, harness.backpressure.sliceChunksEwma(region));
            permit.close();
        }
    }

    @Test
    void deadlineValidationRejectsUnboundedHorizons() {
        try (Harness harness = harness(limits(2, 1_024, 4, 4, 8, 8_192, 8), 1)) {
            BoundPlan plan = harness.plan();
            assertThrows(IllegalArgumentException.class, () -> plan.backpressure().tryAcquire(
                    new RegionKey(),
                    harness.demandWithDeadline(1, 16, 1, false, Long.MAX_VALUE)
            ));
            assertTrue(plan.backpressure().tryAcquire(
                    new RegionKey(),
                    harness.demandWithDeadline(1, 16, 1, false, System.nanoTime() - 1)
            ).isEmpty());
        }
    }

    @Test
    void stopSettlesMultipleProducerBackedBatches() {
        DefaultFoliaBackpressure.Tuning tuning = new DefaultFoliaBackpressure.Tuning(
                2,
                32,
                32,
                3,
                TimeUnit.SECONDS.toNanos(5)
        );
        AtomicInteger rejections = new AtomicInteger();
        try (Harness harness = harness(
                limits(1, 1_024, 4, 8, 16, 16_384, 16),
                1,
                tuning,
                () -> {
                },
                (region, demand, outcome, reason) -> rejections.incrementAndGet()
        )) {
            RegionKey region = new RegionKey();
            FoliaBackpressure.Permit holder = harness.plan().backpressure().tryAcquire(
                    region,
                    harness.demand(1, 16, 1, false)
            ).orElseThrow();
            List<CompletionStage<FoliaBackpressure.Permit>> waiters = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                waiters.add(harness.plan().backpressure().acquire(
                        region,
                        harness.demand(1, 16, 1, false),
                        new CompletableFuture<>()
                ));
            }

            IllegalStateException stopped = new IllegalStateException("stopped");
            harness.planView.stopAccepting(stopped);
            for (CompletionStage<FoliaBackpressure.Permit> waiter : waiters) {
                CompletionException failure = assertThrows(
                        CompletionException.class,
                        () -> waiter.toCompletableFuture().join()
                );
                assertSame(stopped, failure.getCause());
            }
            awaitCondition(() -> rejections.get() == 5);
            holder.close();
        }
    }

    private static Harness harness(FoliaBackpressure.Limits limits, int continuationCarve) {
        return harness(limits, continuationCarve, TEST_TUNING, () -> {
        });
    }

    private static Harness harness(
            FoliaBackpressure.Limits limits,
            int continuationCarve,
            DefaultFoliaBackpressure.Tuning tuning,
            DefaultFoliaBackpressure.WaiterDeliveryProbe waiterDeliveryProbe
    ) {
        return harness(
                limits,
                continuationCarve,
                tuning,
                waiterDeliveryProbe,
                (region, demand, outcome, reason) -> {
                }
        );
    }

    private static Harness harness(
            FoliaBackpressure.Limits limits,
            int continuationCarve,
            DefaultFoliaBackpressure.Tuning tuning,
            DefaultFoliaBackpressure.WaiterDeliveryProbe waiterDeliveryProbe,
            DefaultFoliaBackpressure.AdmissionRejectionHandler rejectionHandler
    ) {
        return new Harness(
                limits,
                continuationCarve,
                tuning,
                waiterDeliveryProbe,
                rejectionHandler
        );
    }

    private static DefaultFoliaBackpressure.AdmissionRejectionHandler outcomeHandler(
            AtomicReference<DefaultFoliaBackpressure.AdmissionFailureOutcome> observedOutcome
    ) {
        return (region, demand, outcome, reason) -> observedOutcome.set(outcome);
    }

    private static FoliaBackpressure.Limits limits(
            int regionalChunks,
            long regionalBytes,
            int regionalFinalizers,
            int regionalWaiters,
            int globalChunks,
            long globalBytes,
            int globalFinalizers
    ) {
        return new FoliaBackpressure.Limits(
                regionalChunks,
                regionalBytes,
                regionalFinalizers,
                regionalWaiters,
                globalChunks,
                globalBytes,
                globalFinalizers
        );
    }

    private static ReentrantLock reflectedLock(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (ReentrantLock) field.get(target);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().orTimeout(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    private static void awaitCondition(Condition condition) {
        long deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        while (!condition.evaluate()) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("condition did not become true");
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void race(CountDownLatch ready, CountDownLatch start, Runnable action) {
        ready.countDown();
        awaitLatch(start);
        action.run();
    }

    private static <T> T raceResult(CountDownLatch ready, CountDownLatch start, ResultSupplier<T> action) {
        ready.countDown();
        awaitLatch(start);
        return action.get();
    }

    @SafeVarargs
    private static int winnerCount(AtomicReference<Boolean>... outcomes) {
        int winners = 0;
        for (AtomicReference<Boolean> outcome : outcomes) {
            if (Boolean.TRUE.equals(outcome.get())) {
                winners++;
            }
        }
        return winners;
    }

    @FunctionalInterface
    private interface Condition {

        boolean evaluate();

    }

    @FunctionalInterface
    private interface TestCase {

        void run(DefaultFoliaBackpressureTest test) throws Exception;

    }

    @FunctionalInterface
    private interface ResultSupplier<T> {

        T get();

    }

    private record BoundPlan(
            OperationCompletionService.PlanProducerToken token,
            DefaultFoliaBackpressure.BoundAdmission backpressure
    ) {
    }

    private static final class Harness implements AutoCloseable {

        private final OperationCompletionService service = new OperationCompletionService(
                "Task 13 corrective test completion"
        );
        private final UUID operationId = UUID.randomUUID();
        private final DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                () -> {
                },
                DefaultOperationCompletion.FinalizerBoundary.none(),
                Duration.ofSeconds(1),
                new HistoryPersistencePolicy(
                        1,
                        Duration.ofSeconds(1),
                        Duration.ZERO,
                        Duration.ofSeconds(1)
                ),
                DefaultOperationCompletion.PersistenceBoundary.notRequired()
        );
        private final DefaultFoliaBackpressure backpressure;
        private final List<OperationCompletionService.PlanProducerToken> tokens = new ArrayList<>();
        private final DefaultFoliaBackpressure.BoundAdmission planView;
        private long nextPlanSequence;
        private boolean closed;

        private Harness(
                FoliaBackpressure.Limits limits,
                int continuationCarve,
                DefaultFoliaBackpressure.Tuning tuning,
                DefaultFoliaBackpressure.WaiterDeliveryProbe waiterDeliveryProbe,
                DefaultFoliaBackpressure.AdmissionRejectionHandler rejectionHandler
        ) {
            this.backpressure = new DefaultFoliaBackpressure(
                    limits,
                    continuationCarve,
                    service,
                    rejectionHandler,
                    System::nanoTime,
                    tuning,
                    waiterDeliveryProbe
            );
            this.planView = plan().backpressure();
        }

        private BoundPlan plan() {
            long sequence = ++nextPlanSequence;
            long chunkKey = sequence;
            AtomicReference<OperationCompletionService.PlanProducerToken> tokenReference = new AtomicReference<>();
            OperationCompletionService.PlanProducerToken token = service.tryAcquirePlanProducer(
                    operationId,
                    chunkKey,
                    sequence,
                    deadlineNanos -> {
                        OperationCompletionService.PlanProducerToken expiring = tokenReference.get();
                        if (expiring != null) {
                            expiring.rejectAdmission(new TimeoutException(
                                    "test plan drain expired at " + deadlineNanos
                            ));
                        }
                    },
                    failure -> {
                        OperationCompletionService.PlanProducerToken failed = tokenReference.get();
                        if (failed != null) {
                            failed.rejectAdmission(failure);
                        }
                    }
            ).orElseThrow();
            tokenReference.set(token);
            completion.register(token);
            tokens.add(token);
            return new BoundPlan(token, backpressure.bind(token));
        }

        private FoliaBackpressure.Demand demand(
                int chunks,
                long bytes,
                int finalizerChains,
                boolean previouslyAccepted
        ) {
            return demandWithDeadline(
                    chunks,
                    bytes,
                    finalizerChains,
                    previouslyAccepted,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            );
        }

        private FoliaBackpressure.Demand demandWithDeadline(
                int chunks,
                long bytes,
                int finalizerChains,
                boolean previouslyAccepted,
                long deadlineNanos
        ) {
            return new FoliaBackpressure.Demand(
                    operationId,
                    chunks,
                    bytes,
                    finalizerChains,
                    FoliaBackpressure.Priority.NORMAL,
                    previouslyAccepted,
                    deadlineNanos
            );
        }

        private void markClosed() {
            closed = true;
        }

        private boolean terminal(BoundPlan plan, TerminalStatus status) {
            OperationCompletionService.PlanProducerToken token = plan.token();
            return token.terminal(new ChunkTerminalRecord(
                    operationId,
                    token.chunkKey(),
                    token.planSequence(),
                    status,
                    emptyReceipt(),
                    Optional.empty()
            ));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (OperationCompletionService.PlanProducerToken token : tokens) {
                token.terminal(new ChunkTerminalRecord(
                        operationId,
                        token.chunkKey(),
                        token.planSequence(),
                        TerminalStatus.NO_CHANGE,
                        emptyReceipt(),
                        Optional.empty()
                ));
            }
            completion.closeAdmission();
            await(completion.future());
            await(service.flush(Duration.ofSeconds(2)));
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

    }

}
