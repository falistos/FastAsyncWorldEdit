package com.fastasyncworldedit.core.util.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.acquirePlan;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.appliedReceipt;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.await;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.awaitDrainExpiry;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.blockCompletionService;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.emptyReceipt;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.finalizerTimeout;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.flush;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.newService;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.policy;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.registerPlan;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.runConcurrently;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.terminal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultOperationCompletionTest {

    private static final Runnable NOOP_GUARD = () -> {
    };

    @Test
    void suppressesDuplicateTerminalWithoutRepeatingPersistence() throws Exception {
        OperationCompletionService service = newService();
        AtomicInteger persistenceCalls = new AtomicInteger();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId, (record, attempt) -> {
            persistenceCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        ChunkTerminalRecord committed = terminal(
                operationId,
                1,
                10,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        );

        registerPlan(service, completion, 1, 10);
        completion.terminal(committed);
        completion.terminal(committed);
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.SUCCEEDED, result.classification());
        assertEquals(HistorySettlement.DURABLE, result.terminalRecords().getFirst().applied().historySettlement());
        assertEquals(1, persistenceCalls.get());
        assertEquals(1, completion.duplicateTerminalCount());
        assertEquals(0, completion.outstandingTerminalCount());
        assertEquals(DefaultOperationCompletion.State.SUCCEEDED, completion.state());
        flush(service);

        completion.terminal(committed);
        assertEquals(2, completion.duplicateTerminalCount());
        assertEquals(1, persistenceCalls.get());
    }

    @Test
    void concurrentDuplicateTerminalIsCountedExactlyOnce() throws Exception {
        OperationCompletionService service = newService();
        AtomicInteger persistenceCalls = new AtomicInteger();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId, (record, attempt) -> {
            persistenceCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        ChunkTerminalRecord committed = terminal(
                operationId,
                12,
                120,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        );
        registerPlan(service, completion, 12, 120);

        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            runConcurrently(() -> completion.terminal(committed), () -> completion.terminal(committed));
        }
        completion.closeAdmission();

        assertEquals(OperationResult.Classification.SUCCEEDED, await(completion.future()).classification());
        assertEquals(1, completion.duplicateTerminalCount());
        assertEquals(1, persistenceCalls.get());
        flush(service);
    }

    @Test
    void concurrentContradictoryTerminalHasDistinctDiagnostic() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);
        ChunkTerminalRecord committed = terminal(
                operationId,
                121,
                1_210,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        );
        ChunkTerminalRecord noChange = terminal(
                operationId,
                121,
                1_210,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        );
        registerPlan(service, completion, 121, 1_210);

        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            runConcurrently(() -> completion.terminal(committed), () -> completion.terminal(noChange));
        }
        completion.closeAdmission();
        await(completion.future());

        assertEquals(1, completion.duplicateTerminalCount());
        assertEquals(1, completion.contradictoryTerminalCount());
        flush(service);
    }

    @Test
    void terminalRacingAdmissionCloseStillCompletes() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);
        ChunkTerminalRecord noChange = terminal(
                operationId,
                13,
                130,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        );
        registerPlan(service, completion, 13, 130);
        await(service.submit(() -> {
        }));

        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            long queuedBefore = service.queuedTransitionCount();
            runConcurrently(() -> completion.terminal(noChange), completion::closeAdmission);
            assertTrue(service.queuedTransitionCount() >= queuedBefore + 2);
            assertFalse(completion.future().toCompletableFuture().isDone());
        }

        assertEquals(OperationResult.Classification.SUCCEEDED, await(completion.future()).classification());
        assertEquals(0, completion.outstandingTerminalCount());
        flush(service);
    }

    @Test
    void allRejectedOperationFailsAndPreservesRejectionCause() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        IllegalStateException rejection = new IllegalStateException("saturated");
        DefaultOperationCompletion completion = completion(service, operationId);

        registerPlan(service, completion, 2, 20);
        completion.terminal(terminal(
                operationId,
                2,
                20,
                TerminalStatus.NOT_ACCEPTED,
                emptyReceipt(),
                rejection
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.FAILED, result.classification());
        assertEquals(List.of(rejection), result.failures());
        assertSame(rejection, result.terminalRecords().getFirst().failure().orElseThrow());
        flush(service);
    }

    @Test
    void committedAndFailedChunksResolvePartialInRegistrationOrder() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);
        RuntimeException failure = new RuntimeException("unloaded");
        ChunkTerminalRecord committed = terminal(
                operationId,
                3,
                30,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        );
        ChunkTerminalRecord failed = terminal(
                operationId,
                4,
                40,
                TerminalStatus.FAILED_BEFORE_MUTATION,
                emptyReceipt(),
                failure
        );

        registerPlan(service, completion, 3, 30);
        registerPlan(service, completion, 4, 40);
        completion.terminal(failed);
        completion.terminal(committed);
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        assertEquals(List.of(committed.chunkKey(), failed.chunkKey()), result.terminalRecords().stream()
                .map(ChunkTerminalRecord::chunkKey)
                .toList());
        flush(service);
    }

    @Test
    void waitsForFinalizersAndPersistenceAndCompletesOnIsolatedNotification() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        CompletableFuture<Void> finalizers = new CompletableFuture<>();
        CompletableFuture<Void> persisted = new CompletableFuture<>();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                () -> finalizers,
                finalizerTimeout(),
                policy(2),
                (record, attempt) -> persisted
        );
        AtomicBoolean callbackOnControl = new AtomicBoolean();
        AtomicBoolean callbackOnNotification = new AtomicBoolean();
        CompletionStage<OperationResult> callback = completion.future().thenApply(operationResult -> {
            callbackOnControl.set(service.isCompletionThread());
            callbackOnNotification.set(service.isNotificationThread());
            return operationResult;
        });

        registerPlan(service, completion, 5, 50);
        completion.terminal(terminal(
                operationId,
                5,
                50,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();
        assertFalse(callback.toCompletableFuture().isDone());

        persisted.complete(null);
        assertFalse(callback.toCompletableFuture().isDone());
        finalizers.complete(null);

        await(callback);
        assertFalse(callbackOnControl.get());
        assertTrue(callbackOnNotification.get());
        Thread publicationThread = service.lastOutcomePublicationThread();
        assertTrue(publicationThread.isVirtual());
        assertFalse(publicationThread == service.completionThread());
        assertFalse(publicationThread == Thread.currentThread());
        flush(service);
    }

    @Test
    void callbackAttachedAfterResultCompletionRunsAsIsolatedNotification() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);
        completion.closeAdmission();
        await(completion.future());
        flush(service);

        CompletableFuture<OperationResult> completableView = completion.future().toCompletableFuture();
        await(completableView);
        Thread attachingThread = Thread.currentThread();
        AtomicBoolean callbackOnControl = new AtomicBoolean();
        AtomicBoolean callbackOnNotification = new AtomicBoolean();
        AtomicBoolean callbackOnAttachingThread = new AtomicBoolean();
        await(completableView.thenRunAsync(() -> {
            callbackOnControl.set(service.isCompletionThread());
            callbackOnNotification.set(service.isNotificationThread());
            callbackOnAttachingThread.set(Thread.currentThread() == attachingThread);
        }, service));

        assertFalse(callbackOnControl.get());
        assertTrue(callbackOnNotification.get());
        assertFalse(callbackOnAttachingThread.get());
    }

    @Test
    void postMutationFinalizerFailureResolvesPartial() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        RuntimeException finalizerFailure = new RuntimeException("relight failed");
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                () -> CompletableFuture.failedFuture(finalizerFailure),
                finalizerTimeout(),
                policy(2),
                (record, attempt) -> CompletableFuture.completedFuture(null)
        );

        registerPlan(service, completion, 6, 60);
        completion.terminal(terminal(
                operationId,
                6,
                60,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        assertEquals(List.of(finalizerFailure), result.failures());
        flush(service);
    }

    @Test
    void persistenceExhaustionFinalizesSingleReceiptUnavailable() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                policy(2),
                (record, attempt) -> {
                    attempts.incrementAndGet();
                    return CompletableFuture.failedFuture(new IllegalStateException("fsync failed"));
                }
        );

        registerPlan(service, completion, 61, 610);
        completion.terminal(terminal(
                operationId,
                61,
                610,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        assertEquals(HistorySettlement.UNAVAILABLE, result.terminalRecords().getFirst().applied().historySettlement());
        assertEquals(2, attempts.get());
        assertFalse(result.historyUsable());
        assertEquals(List.of(61L), result.unavailableHistoryChunks());
        assertTrue(result.actorMessage().contains("undo/history could not be persisted"));
        flush(service);
    }

    @Test
    void allUnavailableSettlementsMakeWholeOperationHistoryUnusable() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                policy(1),
                (record, attempt) -> CompletableFuture.failedFuture(new IllegalStateException("offline"))
        );

        registerPlan(service, completion, 62, 620);
        registerPlan(service, completion, 63, 630);
        completion.terminal(terminal(
                operationId,
                62,
                620,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.terminal(terminal(
                operationId,
                63,
                630,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        assertEquals(List.of(62L, 63L), result.unavailableHistoryChunks());
        assertFalse(result.historyUsable());
        flush(service);
    }

    @Test
    void settlementDeadlineExhaustionProducesUnavailable() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        HistoryPersistencePolicy deadlinePolicy = new HistoryPersistencePolicy(
                5,
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofMillis(30)
        );
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                deadlinePolicy,
                (record, attempt) -> new CompletableFuture<>()
        );

        registerPlan(service, completion, 64, 640);
        completion.terminal(terminal(
                operationId,
                64,
                640,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(HistorySettlement.UNAVAILABLE, result.terminalRecords().getFirst().applied().historySettlement());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        flush(service);
    }

    @Test
    void attemptTimeoutConsumesAttemptAndRetries() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        HistoryPersistencePolicy timeoutPolicy = new HistoryPersistencePolicy(
                2,
                Duration.ofMillis(20),
                Duration.ZERO,
                Duration.ofSeconds(1)
        );
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                timeoutPolicy,
                (record, attempt) -> attempts.incrementAndGet() == 1
                        ? new CompletableFuture<>()
                        : CompletableFuture.completedFuture(null)
        );

        registerPlan(service, completion, 65, 650);
        completion.terminal(terminal(
                operationId,
                65,
                650,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(2, attempts.get());
        assertEquals(HistorySettlement.DURABLE, result.terminalRecords().getFirst().applied().historySettlement());
        flush(service);
    }

    @Test
    void cancelledPersistenceConsumesAttemptAndRetries() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                policy(2),
                (record, attempt) -> attempts.incrementAndGet() == 1
                        ? cancelled
                        : CompletableFuture.completedFuture(null)
        );

        registerPlan(service, completion, 66, 660);
        completion.terminal(terminal(
                operationId,
                66,
                660,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(2, attempts.get());
        assertEquals(HistorySettlement.DURABLE, result.terminalRecords().getFirst().applied().historySettlement());
        flush(service);
    }

    @Test
    void exceptionalCoordinatorTransitionSettlesTerminalAndDeregistersProducer() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        IllegalStateException transitionFailure = new IllegalStateException("callback registration failed");
        CompletableFuture<Void> brokenStage = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
                throw transitionFailure;
            }
        };
        DefaultOperationCompletion completion = completion(service, operationId, (record, attempt) -> brokenStage);

        registerPlan(service, completion, 67, 670);
        completion.terminal(terminal(
                operationId,
                67,
                670,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        assertEquals(TerminalStatus.COMMITTED, result.terminalRecords().getFirst().status());
        assertEquals(HistorySettlement.UNAVAILABLE, result.terminalRecords().getFirst().applied().historySettlement());
        assertInstanceOf(DefaultOperationCompletion.HistoryUnavailableException.class, result.failures().getFirst());
        assertEquals(0, completion.outstandingTerminalCount());
        assertEquals(0, service.scheduledTimerTaskCount());
        flush(service);
    }

    @Test
    void noChangeSucceedsWithoutPersistence() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        DefaultOperationCompletion completion = completion(service, operationId, (record, attempt) -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });

        registerPlan(service, completion, 7, 70);
        completion.terminal(terminal(operationId, 7, 70, TerminalStatus.NO_CHANGE, emptyReceipt(), null));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.SUCCEEDED, result.classification());
        assertEquals(HistorySettlement.NOT_REQUIRED, result.terminalRecords().getFirst().applied().historySettlement());
        assertEquals(0, attempts.get());
        flush(service);
    }

    @Test
    void partiallyCommittedTerminalRemainsWorldPartialAfterDurableSettlement() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);

        registerPlan(service, completion, 8, 80);
        completion.terminal(terminal(
                operationId,
                8,
                80,
                TerminalStatus.PARTIALLY_COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                new IllegalStateException("entity phase failed")
        ));
        completion.closeAdmission();

        OperationResult result = await(completion.future());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        assertEquals(TerminalStatus.PARTIALLY_COMMITTED, result.terminalRecords().getFirst().status());
        assertEquals(HistorySettlement.DURABLE, result.terminalRecords().getFirst().applied().historySettlement());
        flush(service);
    }

    @Test
    void incompletePacketEnqueuePreventsSuccess() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);
        AppliedReceipt receipt = new AppliedReceipt(
                1,
                List.of(),
                List.of(),
                List.of(),
                0,
                new PacketPhaseResult(2, 1),
                HistorySettlement.NOT_REQUIRED
        );

        registerPlan(service, completion, 9, 90);
        completion.terminal(terminal(operationId, 9, 90, TerminalStatus.COMMITTED, receipt, null));
        completion.closeAdmission();

        assertEquals(OperationResult.Classification.PARTIAL, await(completion.future()).classification());
        flush(service);
    }

    @Test
    void appliedReceiptDefensivelyCopiesInnerLists() {
        List<String> tileIds = new ArrayList<>(List.of("tile-a"));
        List<EntityAction> entities = new ArrayList<>(List.of(
                new EntityAction(UUID.randomUUID(), EntityAction.Type.ADDED)
        ));
        List<String> poiIds = new ArrayList<>(List.of("poi-a"));
        AppliedReceipt receipt = new AppliedReceipt(
                1,
                tileIds,
                entities,
                poiIds,
                0,
                PacketPhaseResult.noPackets(),
                HistorySettlement.DURABLE
        );

        tileIds.add("tile-b");
        entities.clear();
        poiIds.add("poi-b");

        assertEquals(List.of("tile-a"), receipt.appliedTileIds());
        assertEquals(1, receipt.appliedEntities().size());
        assertEquals(List.of("poi-a"), receipt.appliedPoiNeighborIds());
        assertThrows(UnsupportedOperationException.class, () -> receipt.appliedTileIds().add("tile-c"));
    }

    @Test
    void terminalRecordRejectsNullAppliedReceiptAtConstruction() {
        assertThrows(NullPointerException.class, () -> terminal(
                UUID.randomUUID(),
                1,
                1,
                TerminalStatus.NO_CHANGE,
                null,
                null
        ));
    }

    @Test
    void drainExpiryTerminalizesMissingPlanAsCancelledBeforeMutation() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);

        registerPlan(service, completion, 10, 100);
        completion.closeAdmission();

        CompletionStage<Void> flushed = service.flush(Duration.ofMillis(40));
        OperationResult result = await(completion.future());
        await(flushed);

        assertEquals(OperationResult.Classification.FAILED, result.classification());
        assertEquals(TerminalStatus.CANCELLED_BEFORE_MUTATION, result.terminalRecords().getFirst().status());
        assertInstanceOf(TimeoutException.class,
                result.terminalRecords().getFirst().failure().orElseThrow());
    }

    @Test
    void drainOwnerFreezesCommittedReceiptWhenTerminalNotificationLosesExpiryRace() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);
        ChunkTerminalRecord committed = terminal(
                operationId,
                18,
                180,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.UNAVAILABLE),
                null
        );
        registerPlan(
                service,
                completion,
                18,
                180,
                deadlineNanos -> completion.terminal(committed)
        );

        CompletionStage<Void> flushed;
        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            completion.closeAdmission();
            flushed = service.flush(Duration.ofMillis(40));
            awaitDrainExpiry(service);

            assertThrows(IllegalStateException.class, () -> completion.terminal(committed));
            assertFalse(completion.future().toCompletableFuture().isDone());
        }

        OperationResult result = await(completion.future());
        await(flushed);
        assertEquals(TerminalStatus.COMMITTED, result.terminalRecords().getFirst().status());
        assertEquals(HistorySettlement.UNAVAILABLE,
                result.terminalRecords().getFirst().applied().historySettlement());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
    }

    @Test
    void admissionProducerFencesTerminationAndClosedGuardSuppressesRedispatch() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        AtomicInteger closeDispatches = new AtomicInteger();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                policy(1),
                DefaultOperationCompletion.PersistenceBoundary.notRequired(),
                closeDispatches::incrementAndGet
        );
        registerPlan(service, completion, 15, 150);
        completion.terminal(terminal(
                operationId,
                15,
                150,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        ));
        long producerDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (service.registeredProducerCount() != 1 && System.nanoTime() < producerDeadline) {
            Thread.onSpinWait();
        }
        assertEquals(1, service.registeredProducerCount());

        CompletionStage<Void> flushed = service.flush(Duration.ofSeconds(2));
        assertEquals(OperationCompletionService.State.FLUSHING, service.state());
        assertFalse(flushed.toCompletableFuture().isDone());

        completion.closeAdmission();
        assertEquals(1, closeDispatches.get());
        await(completion.future());
        await(flushed);

        completion.closeAdmission();
        assertEquals(1, closeDispatches.get());
        assertEquals(OperationCompletionService.State.TERMINATED, service.state());
    }

    @Test
    void blockingFinalizerPrefixCannotDelayDrainExpiry() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                () -> {
                    entered.complete(null);
                    release.join();
                    return CompletableFuture.completedFuture(null);
                },
                Duration.ofSeconds(5),
                policy(1),
                DefaultOperationCompletion.PersistenceBoundary.notRequired()
        );
        await(entered);
        registerPlan(service, completion, 16, 160);
        completion.terminal(terminal(
                operationId,
                16,
                160,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();
        try {
            CompletionStage<Void> flushed = service.flush(Duration.ofMillis(40));
            OperationResult result = await(completion.future());
            await(flushed);

            assertEquals(OperationResult.Classification.PARTIAL, result.classification());
            assertInstanceOf(TimeoutException.class, result.failures().getFirst());
            assertFalse(release.isDone());
        } finally {
            release.complete(null);
        }
    }

    @Test
    void armedPersistenceTimeoutCanExpireWhileBoundaryPrefixBlocks() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        HistoryPersistencePolicy longPolicy = new HistoryPersistencePolicy(
                1,
                Duration.ofSeconds(5),
                Duration.ZERO,
                Duration.ofSeconds(5)
        );
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                longPolicy,
                (record, attempt) -> {
                    entered.complete(null);
                    release.join();
                    return CompletableFuture.completedFuture(null);
                }
        );
        registerPlan(service, completion, 17, 170);
        completion.terminal(terminal(
                operationId,
                17,
                170,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        await(entered);
        completion.closeAdmission();
        try {
            CompletionStage<Void> flushed = service.flush(Duration.ofMillis(40));
            OperationResult result = await(completion.future());
            await(flushed);

            assertEquals(OperationResult.Classification.PARTIAL, result.classification());
            assertEquals(HistorySettlement.UNAVAILABLE,
                    result.terminalRecords().getFirst().applied().historySettlement());
            assertFalse(release.isDone());
        } finally {
            release.complete(null);
        }
    }

    @Test
    void rejectsDuplicateRegistrationLateRegistrationAndMissingRejectionCause() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);

        registerPlan(service, completion, 11, 110);
        assertThrows(IllegalStateException.class, () -> completion.register(11, 110));
        assertThrows(IllegalArgumentException.class, () -> completion.terminal(terminal(
                operationId,
                11,
                110,
                TerminalStatus.NOT_ACCEPTED,
                emptyReceipt(),
                null
        )));
        completion.terminal(terminal(
                operationId,
                11,
                110,
                TerminalStatus.NOT_ACCEPTED,
                emptyReceipt(),
                new IllegalStateException("rejected")
        ));
        completion.closeAdmission();
        assertThrows(IllegalStateException.class, () -> completion.register(12, 120));
        await(completion.future());
        flush(service);
    }

    @Test
    void unleasedPlanRegistrationFailsBeforeCoordinatorAccounting() throws Exception {
        OperationCompletionService service = newService();
        DefaultOperationCompletion completion = completion(service, UUID.randomUUID());

        assertThrows(IllegalStateException.class, () -> completion.register(19, 190));
        assertEquals(0, completion.outstandingTerminalCount());

        completion.closeAdmission();
        // pending Amendment 3: classify an empty operation whose plan registration lacked a capability.
        await(completion.future());
        flush(service);
    }

    @Test
    void transactionalRegistrationAbortsAllFourRejectedTokensAndFlushes() throws Exception {
        UUID operationId = UUID.randomUUID();

        OperationCompletionService tokenService = newService();
        OperationCompletionService coordinatorService = newService();
        DefaultOperationCompletion wrongService = completion(coordinatorService, operationId);
        OperationCompletionService.PlanProducerToken wrongServiceToken = acquirePlan(
                tokenService,
                operationId,
                27,
                270
        );
        IllegalArgumentException wrongServiceFailure = assertThrows(
                IllegalArgumentException.class,
                () -> wrongService.register(wrongServiceToken)
        );
        await(tokenService.submit(() -> {
        }));
        assertEquals(0, tokenService.registeredProducerCount());
        assertEquals(1, tokenService.unconsumedPlanTokenDiagnosticCount());
        assertSame(wrongServiceFailure, tokenService.lastUnconsumedPlanTokenFailure().orElseThrow());
        wrongService.closeAdmission();
        await(wrongService.future());
        flush(tokenService);
        flush(coordinatorService);

        OperationCompletionService wrongOperationService = newService();
        DefaultOperationCompletion wrongOperation = completion(wrongOperationService, UUID.randomUUID());
        awaitProducerCount(wrongOperationService, 1);
        OperationCompletionService.PlanProducerToken wrongOperationToken = acquirePlan(
                wrongOperationService,
                operationId,
                28,
                280
        );
        IllegalArgumentException wrongOperationFailure = assertThrows(
                IllegalArgumentException.class,
                () -> wrongOperation.register(wrongOperationToken)
        );
        awaitProducerCount(wrongOperationService, 1);
        assertSame(wrongOperationFailure, wrongOperationService.lastUnconsumedPlanTokenFailure().orElseThrow());
        wrongOperation.closeAdmission();
        await(wrongOperation.future());
        flush(wrongOperationService);

        OperationCompletionService closedService = newService();
        DefaultOperationCompletion closed = completion(closedService, operationId);
        OperationCompletionService.PlanProducerToken closedToken = acquirePlan(closedService, operationId, 29, 290);
        closed.closeAdmission();
        IllegalStateException closedFailure = assertThrows(
                IllegalStateException.class,
                () -> closed.register(closedToken)
        );
        awaitProducerCount(closedService, 0);
        assertSame(closedFailure, closedService.lastUnconsumedPlanTokenFailure().orElseThrow());
        await(closed.future());
        flush(closedService);

        OperationCompletionService duplicateService = newService();
        DefaultOperationCompletion duplicate = completion(duplicateService, operationId);
        OperationCompletionService.PlanProducerToken first = registerPlan(duplicateService, duplicate, 30, 300);
        assertTrue(first.terminal(terminal(
                operationId,
                30,
                300,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        )));
        awaitProducerCount(duplicateService, 1);
        OperationCompletionService.PlanProducerToken second = acquirePlan(duplicateService, operationId, 30, 300);
        IllegalStateException duplicateFailure = assertThrows(
                IllegalStateException.class,
                () -> duplicate.register(second)
        );
        awaitProducerCount(duplicateService, 1);
        assertSame(duplicateFailure, duplicateService.lastUnconsumedPlanTokenFailure().orElseThrow());
        duplicate.closeAdmission();
        await(duplicate.future());
        flush(duplicateService);
    }

    @Test
    void consumedPlanTokenRejectsAbortAndCapabilityReuse() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = completion(service, operationId);
        DefaultOperationCompletion sibling = completion(service, operationId);
        OperationCompletionService.PlanProducerToken token = acquirePlan(service, operationId, 31, 310);

        completion.register(token);
        assertThrows(IllegalStateException.class, () -> token.abort(new IllegalStateException("too late")));
        assertThrows(IllegalStateException.class, () -> sibling.register(token));
        assertEquals(0, sibling.outstandingTerminalCount());
        assertEquals(1, completion.outstandingTerminalCount());
        assertTrue(token.terminal(terminal(
                operationId,
                31,
                310,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        )));

        completion.closeAdmission();
        sibling.closeAdmission();
        await(completion.future());
        await(sibling.future());
        flush(service);
    }

    @Test
    void coordinatorContextGuardRejectsWrongContextEntry() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        Thread coordinatorThread = Thread.currentThread();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                () -> {
                    if (Thread.currentThread() != coordinatorThread) {
                        throw new IllegalStateException("wrong coordinator context");
                    }
                },
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                policy(1),
                DefaultOperationCompletion.PersistenceBoundary.notRequired()
        );
        CompletableFuture<Throwable> observed = new CompletableFuture<>();
        Thread wrongContext = new Thread(() -> {
            try {
                completion.register(14, 140);
                observed.complete(null);
            } catch (Throwable failure) {
                observed.complete(failure);
            }
        }, "FAWE Wrong Completion Context");

        wrongContext.start();

        assertInstanceOf(IllegalStateException.class, await(observed));
        completion.closeAdmission();
        // pending Amendment 3: classify an empty operation whose only attempted registration was rejected.
        await(completion.future());
        flush(service);
    }

    private static DefaultOperationCompletion completion(
            OperationCompletionService service,
            UUID operationId
    ) {
        return completion(
                service,
                operationId,
                (record, attempt) -> CompletableFuture.completedFuture(null)
        );
    }

    private static DefaultOperationCompletion completion(
            OperationCompletionService service,
            UUID operationId,
            DefaultOperationCompletion.PersistenceBoundary persistenceBoundary
    ) {
        return new DefaultOperationCompletion(
                operationId,
                service,
                NOOP_GUARD,
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                policy(2),
                persistenceBoundary
        );
    }

    private static void awaitProducerCount(
            OperationCompletionService service,
            long expected
    ) throws TimeoutException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (service.registeredProducerCount() != expected) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new TimeoutException("Producer count did not reach " + expected);
            }
            Thread.onSpinWait();
        }
    }

}
