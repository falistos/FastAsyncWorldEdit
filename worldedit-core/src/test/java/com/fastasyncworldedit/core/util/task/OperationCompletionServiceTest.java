package com.fastasyncworldedit.core.util.task;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationCompletionServiceTest {

    @Test
    void flushFencesLateProducerAndDrainsExistingProducer() throws Exception {
        OperationCompletionService service = newService();
        OperationCompletionService.Producer producer = producer(service);
        AtomicBoolean transitionRan = new AtomicBoolean();

        var flushed = service.flush(Duration.ofSeconds(2));
        assertEquals(OperationCompletionService.State.FLUSHING, service.state());
        assertThrows(RejectedExecutionException.class, () -> service.registerProducer(
                new Object(),
                deadlineNanos -> {
                },
                failure -> {
                }
        ));
        assertFalse(flushed.toCompletableFuture().isDone());

        producer.complete(() -> transitionRan.set(true));
        await(flushed);

        assertTrue(transitionRan.get());
        assertEquals(OperationCompletionService.State.TERMINATED, service.state());
        assertEquals(0, service.registeredProducerCount());
        assertEquals(0, service.queuedTransitionCount());
    }

    @Test
    void producerDeregistrationRacingFlushWaitsForQueuedTransition() throws Exception {
        OperationCompletionService service = newService();
        OperationCompletionService.Producer producer = producer(service);
        AtomicBoolean transitionRan = new AtomicBoolean();
        CompletableFuture<Void> producerQueued = new CompletableFuture<>();
        AtomicReference<CompletionStage<Void>> flushed = new AtomicReference<>();
        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            runConcurrently(
                    () -> {
                        producer.complete(() -> transitionRan.set(true));
                        producerQueued.complete(null);
                    },
                    () -> {
                        producerQueued.join();
                        flushed.set(service.flush(Duration.ofSeconds(2)));
                    }
            );

            assertFalse(flushed.get().toCompletableFuture().isDone());
            assertEquals(1, service.registeredProducerCount());
        }
        await(flushed.get());

        assertTrue(transitionRan.get());
        assertEquals(OperationCompletionService.State.TERMINATED, service.state());
        assertEquals(0, service.registeredProducerCount());
        assertEquals(0, service.queuedTransitionCount());
    }

    @Test
    void throwingProducerTransitionSettlesFailureAndDeregisters() throws Exception {
        OperationCompletionService service = newService();
        IllegalStateException transitionFailure = new IllegalStateException("transition failed");
        AtomicReference<Throwable> settledFailure = new AtomicReference<>();
        OperationCompletionService.Producer producer = service.registerProducer(
                new Object(),
                deadlineNanos -> {
                },
                settledFailure::set
        );

        Throwable submittedFailure = await(producer.execute(() -> {
            throw transitionFailure;
        }).handle((ignored, failure) -> failure));

        assertSame(transitionFailure, submittedFailure);
        assertSame(transitionFailure, settledFailure.get());
        assertEquals(0, service.registeredProducerCount());
        await(service.flush(Duration.ofSeconds(2)));
        assertEquals(OperationCompletionService.State.TERMINATED, service.state());
    }

    @Test
    void transitionSubmittedAfterProducerCompletionIsLoud() throws Exception {
        OperationCompletionService service = newService();
        OperationCompletionService.Producer producer = producer(service);

        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            producer.complete(() -> {
            });

            assertThrows(IllegalStateException.class, () -> producer.execute(() -> {
            }));
        }
        await(service.flush(Duration.ofSeconds(2)));
    }

    @Test
    void livePlanTokenRemainsConsumableDuringFlushing() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                () -> {
                },
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                policy(1),
                DefaultOperationCompletion.PersistenceBoundary.notRequired()
        );
        OperationCompletionService.PlanProducerToken token = service.tryAcquirePlanProducer(
                operationId,
                2,
                2,
                deadlineNanos -> {
                },
                failure -> {
                    throw new AssertionError(failure);
                }
        )
                .orElseThrow();

        var flushed = service.flush(Duration.ofSeconds(2));
        assertFalse(flushed.toCompletableFuture().isDone());
        completion.register(token);
        assertTrue(token.terminal(terminal(
                operationId,
                2,
                2,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        )));
        completion.closeAdmission();

        assertEquals(OperationResult.Classification.SUCCEEDED, await(completion.future()).classification());
        await(flushed);
        assertEquals(OperationCompletionService.State.TERMINATED, service.state());
    }

    @Test
    void postTerminationSubmissionRunsInlineUnderSerialization() throws Exception {
        OperationCompletionService service = newService();
        await(service.flush(Duration.ofSeconds(2)));
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> executionThread = new AtomicReference<>();

        await(service.submit(() -> executionThread.set(Thread.currentThread())));

        assertSame(caller, executionThread.get());
    }

    @Test
    void postTerminationInlineCommandDoesNotHoldLifecycleLock() throws Exception {
        OperationCompletionService service = newService();
        await(service.flush(Duration.ofSeconds(2)));
        AtomicBoolean lifecycleReadable = new AtomicBoolean();

        await(service.submit(() -> {
            Thread reader = new Thread(() -> {
                service.state();
                lifecycleReadable.set(true);
            });
            reader.start();
            try {
                reader.join(1_000);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            assertFalse(reader.isAlive());
        }));

        assertTrue(lifecycleReadable.get());
    }

    @Test
    void postTerminationTickCallerFailsBeforeMutation() throws Exception {
        Thread tickCaller = Thread.currentThread();
        OperationCompletionService service = new OperationCompletionService(
                "FAWE Tick Completion Test",
                () -> Thread.currentThread() == tickCaller,
                null
        );
        AtomicBoolean ran = new AtomicBoolean();
        await(service.flush(Duration.ofSeconds(2)));
        CompletableFuture<Void> serializationHeld = new CompletableFuture<>();
        CompletableFuture<Void> releaseSerialization = new CompletableFuture<>();
        Thread blocker = Thread.ofPlatform().start(() -> service.submit(() -> {
            serializationHeld.complete(null);
            releaseSerialization.join();
        }));
        await(serializationHeld);
        try {
            assertThrows(RejectedExecutionException.class, () -> service.submit(() -> ran.set(true)));
        } finally {
            releaseSerialization.complete(null);
            blocker.join(2_000);
        }

        assertFalse(ran.get());
        assertFalse(blocker.isAlive());
    }

    @Test
    void notificationTaskStartsOutsideLifecycleLock() throws Exception {
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        OperationCompletionService service = new OperationCompletionService(
                "FAWE Publication Lock Test",
                () -> false,
                command -> {
                    starterEntered.countDown();
                    try {
                        assertTrue(releaseStarter.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                    Thread.ofVirtual().start(command);
                }
        );
        CompletionStage<Void> submitted = service.submit(() -> {
        });
        assertTrue(starterEntered.await(2, TimeUnit.SECONDS));
        CountDownLatch lifecycleRead = new CountDownLatch(1);
        Thread reader = Thread.ofPlatform().start(() -> {
            service.state();
            lifecycleRead.countDown();
        });
        try {
            assertTrue(lifecycleRead.await(2, TimeUnit.SECONDS));
        } finally {
            releaseStarter.countDown();
            reader.join(2_000);
        }
        await(submitted);
        await(service.flush(Duration.ofSeconds(2)));
    }

    @Test
    void completionExecutorUsesAnUnmarkedPlainThread() throws Exception {
        OperationCompletionService service = newService();
        AtomicReference<Thread> executionThread = new AtomicReference<>();

        await(service.submit(() -> executionThread.set(Thread.currentThread())));

        assertSame(Thread.class, executionThread.get().getClass());
        await(service.flush(Duration.ofSeconds(2)));
    }

    @Test
    void blockedConsumerDoesNotDelayNotificationsTransitionsOrFlush() throws Exception {
        OperationCompletionService service = newService();
        CompletableFuture<Void> blockedConsumerEntered = new CompletableFuture<>();
        CompletableFuture<Void> releaseBlockedConsumer = new CompletableFuture<>();
        AtomicBoolean blockedConsumerOnControl = new AtomicBoolean();
        AtomicBoolean secondNotificationOnIsolatedTask = new AtomicBoolean();
        AtomicBoolean transitionRan = new AtomicBoolean();
        DefaultOperationCompletion first = emptyCompletion(service, UUID.randomUUID());
        DefaultOperationCompletion second = emptyCompletion(service, UUID.randomUUID());

        first.future().thenRunAsync(() -> {
            blockedConsumerOnControl.set(service.isCompletionThread());
            blockedConsumerEntered.complete(null);
            releaseBlockedConsumer.join();
        }, Runnable::run);
        try {
            first.closeAdmission();
            await(blockedConsumerEntered);

            CompletionStage<Void> secondNotification = second.future().thenRunAsync(() ->
                    secondNotificationOnIsolatedTask.set(service.isNotificationThread()), service
            );
            second.closeAdmission();

            await(secondNotification);
            await(service.submit(() -> transitionRan.set(true)));
            await(service.flush(Duration.ofSeconds(2)));

            assertFalse(blockedConsumerOnControl.get());
            assertTrue(secondNotificationOnIsolatedTask.get());
            assertTrue(transitionRan.get());
            assertFalse(releaseBlockedConsumer.isDone());
            assertEquals(OperationCompletionService.State.TERMINATED, service.state());
        } finally {
            releaseBlockedConsumer.complete(null);
        }
    }

    @Test
    void planAndAdmissionAcquisitionRejectAfterFlushWithoutSideEffects() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion;
        OperationCompletionService.PlanProducerToken token;
        CompletionStage<Void> flushed;
        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            completion = emptyCompletion(service, operationId);
            token = service.tryAcquirePlanProducer(
                    operationId,
                    20,
                    200,
                    deadlineNanos -> {
                    },
                    failure -> {
                        throw new AssertionError(failure);
                    }
            ).orElseThrow();
            flushed = service.flush(Duration.ofSeconds(2));
            long producersBefore = service.registeredProducerCount();

            assertTrue(service.tryAcquirePlanProducer(
                    operationId,
                    21,
                    210,
                    deadlineNanos -> {
                    },
                    failure -> {
                    }
            ).isEmpty());
            assertTrue(service.<String>tryAcquireAdmissionProducer(1, token).isEmpty());
            assertEquals(producersBefore, service.registeredProducerCount());
            assertEquals(0, service.outstandingAdmissionProducerCount());

            completion.register(token);
            assertTrue(token.rejectAdmission(new RejectedExecutionException("shutdown")));
            completion.closeAdmission();
        }
        await(completion.future());
        await(flushed);

        assertTrue(service.tryAcquirePlanProducer(
                operationId,
                22,
                220,
                deadlineNanos -> {
                },
                failure -> {
                }
        ).isEmpty());
        assertTrue(service.<String>tryAcquireAdmissionProducer(2, token).isEmpty());
        assertEquals(0, service.registeredProducerCount());
        assertEquals(0, service.outstandingAdmissionProducerCount());
    }

    @Test
    void waiterRejectionTerminalizesPlanBeforeExceptionalPublication() throws Exception {
        AtomicReference<DefaultOperationCompletion> completionRef = new AtomicReference<>();
        AtomicBoolean inspectNextPublication = new AtomicBoolean();
        AtomicBoolean accountingSettled = new AtomicBoolean();
        AtomicBoolean terminalBeforePublication = new AtomicBoolean();
        CountDownLatch admissionPublication = new CountDownLatch(1);
        CompletableFuture<Void> finalizer = new CompletableFuture<>();
        OperationCompletionService service = new OperationCompletionService(
                "FAWE Admission Ordering Test",
                () -> false,
                command -> {
                    if (inspectNextPublication.compareAndSet(true, false)) {
                        terminalBeforePublication.set(
                                accountingSettled.get() && completionRef.get().outstandingTerminalCount() == 0
                        );
                        admissionPublication.countDown();
                    }
                    command.run();
                }
        );
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                () -> {
                },
                () -> finalizer,
                finalizerTimeout(),
                policy(1),
                DefaultOperationCompletion.PersistenceBoundary.notRequired()
        );
        completionRef.set(completion);
        await(service.submit(() -> {
        }));
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 23, 230);
        OperationCompletionService.AdmissionProducer<String> admission = service
                .<String>tryAcquireAdmissionProducer(1, token)
                .orElseThrow();
        IllegalStateException waiterFailure = new IllegalStateException("waiter rejected");
        assertTrue(admission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));
        CompletionStage<Throwable> delivered = admission.delivery().future().handle((ignored, failure) -> failure);

        accountingSettled.set(true);
        inspectNextPublication.set(true);
        assertTrue(admission.reject(waiterFailure));

        assertTrue(admissionPublication.await(2, TimeUnit.SECONDS));
        assertSame(waiterFailure, await(delivered));
        assertTrue(terminalBeforePublication.get());
        assertEquals(0, completion.outstandingTerminalCount());
        finalizer.complete(null);
        completion.closeAdmission();
        assertEquals(1, await(completion.future()).terminalRecords().size());
        flush(service);
    }

    @Test
    void planDrainAndWaiterRejectionCompeteForOneTerminalTransition() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 24, 240);
        OperationCompletionService.AdmissionProducer<String> admission = service
                .<String>tryAcquireAdmissionProducer(2, token)
                .orElseThrow();
        IllegalStateException waiterFailure = new IllegalStateException("waiter rejected");
        IllegalStateException planFailure = new IllegalStateException("plan drain won");
        AtomicBoolean planClaimed = new AtomicBoolean();
        AtomicBoolean waiterClaimed = new AtomicBoolean();
        assertTrue(admission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));
        CompletionStage<Throwable> delivered = admission.delivery().future().handle((ignored, failure) -> failure);

        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            runConcurrently(
                    () -> planClaimed.set(token.rejectAdmission(planFailure)),
                    () -> waiterClaimed.set(admission.reject(waiterFailure))
            );
            assertFalse(delivered.toCompletableFuture().isDone());
        }

        assertTrue(planClaimed.get());
        assertTrue(waiterClaimed.get());
        assertSame(waiterFailure, await(delivered));
        assertEquals(0, completion.outstandingTerminalCount());
        completion.closeAdmission();
        OperationResult result = await(completion.future());
        assertEquals(1, result.terminalRecords().size());
        assertSame(planFailure, result.terminalRecords().getFirst().failure().orElseThrow());
        flush(service);
    }

    @Test
    void abortAndFlushExpiryRaceHasOneUnconsumedTokenWinner() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        OperationCompletionService.PlanProducerToken token = acquirePlan(service, operationId, 33, 330);
        IllegalStateException explicitAbort = new IllegalStateException("caller abandoned registration");
        CompletionStage<Void> flushed = service.flush(Duration.ofSeconds(2));
        await(service.submit(() -> {
        }));
        ReentrantLock lock = lifecycleLock(service);
        Method expireFlush = OperationCompletionService.class.getDeclaredMethod("expireFlush");
        expireFlush.setAccessible(true);
        CountDownLatch started = new CountDownLatch(2);
        CompletableFuture<Boolean> abortWon = new CompletableFuture<>();
        CompletableFuture<Void> expiryFinished = new CompletableFuture<>();

        lock.lock();
        try {
            Thread.ofPlatform().start(() -> {
                started.countDown();
                try {
                    abortWon.complete(token.abort(explicitAbort));
                } catch (Throwable failure) {
                    abortWon.completeExceptionally(failure);
                }
            });
            Thread.ofPlatform().start(() -> {
                started.countDown();
                try {
                    expireFlush.invoke(service);
                    expiryFinished.complete(null);
                } catch (InvocationTargetException failure) {
                    expiryFinished.completeExceptionally(failure.getCause());
                } catch (Throwable failure) {
                    expiryFinished.completeExceptionally(failure);
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            long queueDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (lock.getQueueLength() != 2 && System.nanoTime() < queueDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(2, lock.getQueueLength());
        } finally {
            lock.unlock();
        }

        boolean explicitAbortWon = await(abortWon);
        await(expiryFinished);
        await(flushed);

        assertEquals(0, service.registeredProducerCount());
        assertEquals(1, service.releasedProducerCount());
        assertEquals(1, service.unconsumedPlanTokenDiagnosticCount());
        assertEquals(explicitAbortWon ? 0 : 1, service.abandonedPlanTokenDiagnosticCount());
        if (explicitAbortWon) {
            assertSame(explicitAbort, service.lastUnconsumedPlanTokenFailure().orElseThrow());
        } else {
            assertInstanceOf(TimeoutException.class, service.lastUnconsumedPlanTokenFailure().orElseThrow());
        }
        assertFalse(token.abort(new IllegalStateException("duplicate abort")));
    }

    @Test
    void abandonedPlanTokenIsDiagnosedAndCannotHangFlush() throws Exception {
        OperationCompletionService service = newService();
        OperationCompletionService.PlanProducerToken token = acquirePlan(service, UUID.randomUUID(), 34, 340);

        await(service.flush(Duration.ofMillis(40)));

        assertEquals(0, service.registeredProducerCount());
        assertEquals(1, service.unconsumedPlanTokenDiagnosticCount());
        assertEquals(1, service.abandonedPlanTokenDiagnosticCount());
        assertInstanceOf(TimeoutException.class, service.lastUnconsumedPlanTokenFailure().orElseThrow());
        assertFalse(token.abort(new IllegalStateException("too late")));
    }

    @Test
    void abandonedUnarmedProducerMakesArmLoseAndForceRejects() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 25, 250);
        OperationCompletionService.AdmissionProducer<Void> admission = service
                .<Void>tryAcquireAdmissionProducer(3, token)
                .orElseThrow();
        AtomicBoolean accountingStarted = new AtomicBoolean();
        CompletionStage<Throwable> delivered = admission.delivery().future().handle((ignored, failure) -> failure);
        completion.closeAdmission();

        CompletionStage<Void> flushed;
        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            flushed = service.flush(Duration.ofMillis(40));
            awaitDrainExpiry(service);
            assertFalse(admission.arm(
                    deadlineNanos -> accountingStarted.set(true),
                    failure -> accountingStarted.set(true)
            ));
            assertFalse(accountingStarted.get());
        }

        Throwable rejection = await(delivered);
        assertInstanceOf(TimeoutException.class, rejection);
        OperationResult result = await(completion.future());
        await(flushed);
        assertFalse(accountingStarted.get());
        assertEquals(TerminalStatus.NOT_ACCEPTED, result.terminalRecords().getFirst().status());
        assertSame(rejection, result.terminalRecords().getFirst().failure().orElseThrow());
        assertEquals(0, service.outstandingAdmissionProducerCount());
        assertEquals(0, service.registeredProducerCount());
        assertEquals(1, service.unarmedAdmissionProducerDiagnosticCount());
        assertSame(rejection, service.lastUnarmedAdmissionProducerFailure().orElseThrow());
    }

    @Test
    void rejectionBeforeArmTerminalizesPlanAndReleasesProducer() throws Exception {
        ConcurrentLinkedQueue<Runnable> notifications = new ConcurrentLinkedQueue<>();
        OperationCompletionService service = new OperationCompletionService(
                "FAWE Pre-Arm Rejection Test",
                () -> false,
                notifications::add
        );
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 35, 350);
        OperationCompletionService.AdmissionProducer<Void> admission = service
                .<Void>tryAcquireAdmissionProducer(14, token)
                .orElseThrow();
        IllegalStateException setupFailure = new IllegalStateException("waiter setup failed");
        CompletableFuture<Throwable> delivered = admission.delivery().future()
                .handle((ignored, failure) -> failure)
                .toCompletableFuture();
        CompletableFuture<OperationResult> completed = completion.future().toCompletableFuture();

        assertTrue(admission.reject(setupFailure));
        awaitPendingPublication(service);
        assertEquals(1, service.outstandingAdmissionProducerCount());
        CompletionStage<Void> flushed = service.flush(Duration.ofMillis(40));
        CompletableFuture<Void> flushCompleted = flushed.toCompletableFuture();
        awaitDrainExpiry(service);
        assertEquals(1, service.outstandingAdmissionProducerCount());
        assertEquals(0, service.unarmedAdmissionProducerDiagnosticCount());
        drainNotifications(notifications, delivered, completed, flushCompleted);
        assertSame(setupFailure, await(delivered));
        completion.closeAdmission();
        OperationResult result = await(completed);
        await(flushCompleted);

        assertEquals(TerminalStatus.NOT_ACCEPTED, result.terminalRecords().getFirst().status());
        assertSame(setupFailure, result.terminalRecords().getFirst().failure().orElseThrow());
        assertEquals(0, service.outstandingAdmissionProducerCount());
        assertEquals(0, service.unarmedAdmissionProducerDiagnosticCount());
    }

    @Test
    void deliverRejectAndCancelRaceOnOneAttemptCas() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 25, 250);
        OperationCompletionService.AdmissionProducer<String> admission = service
                .<String>tryAcquireAdmissionProducer(3, token)
                .orElseThrow();
        AtomicBoolean delivered = new AtomicBoolean();
        AtomicBoolean rejected = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        IllegalStateException rejection = new IllegalStateException("rejected");
        CancellationException cancellation = new CancellationException("cancelled");
        assertTrue(admission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));

        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            runConcurrently(
                    () -> delivered.set(admission.delivery().deliver("granted")),
                    () -> rejected.set(admission.reject(rejection)),
                    () -> cancelled.set(admission.cancel(cancellation))
            );
        }

        assertEquals(1, (delivered.get() ? 1 : 0) + (rejected.get() ? 1 : 0) + (cancelled.get() ? 1 : 0));
        Object outcome = await(admission.delivery().future().handle(
                (value, failure) -> failure == null ? value : failure
        ));
        if (delivered.get()) {
            assertEquals("granted", outcome);
        } else if (rejected.get()) {
            assertSame(rejection, outcome);
        } else {
            assertSame(cancellation, outcome);
        }
        assertFalse(admission.delivery().deliver("duplicate"));
        assertFalse(admission.reject(new IllegalStateException("duplicate")));
        assertFalse(admission.cancel(new CancellationException("duplicate")));
        if (!rejected.get()) {
            assertTrue(token.terminal(terminal(
                    operationId,
                    25,
                    250,
                    TerminalStatus.NO_CHANGE,
                    emptyReceipt(),
                    null
            )));
        }
        completion.closeAdmission();
        await(completion.future());
        flush(service);
    }

    @Test
    void cancellationLeavesPlanSequenceAvailableForAnotherAdmissionAttempt() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 26, 260);
        OperationCompletionService.AdmissionProducer<String> firstAdmission = service
                .<String>tryAcquireAdmissionProducer(8, token)
                .orElseThrow();
        CancellationException cancellation = new CancellationException("retry admission");
        assertTrue(firstAdmission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));

        assertTrue(firstAdmission.cancel(cancellation));
        assertSame(cancellation, await(firstAdmission.delivery().future().handle((ignored, failure) -> failure)));
        assertEquals(1, completion.outstandingTerminalCount());

        OperationCompletionService.AdmissionProducer<String> retryAdmission = service
                .<String>tryAcquireAdmissionProducer(9, token)
                .orElseThrow();
        assertTrue(retryAdmission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));
        assertTrue(retryAdmission.delivery().deliver("retry granted"));
        assertEquals("retry granted", await(retryAdmission.delivery().future()));
        assertTrue(token.terminal(terminal(
                operationId,
                26,
                260,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        )));
        completion.closeAdmission();
        await(completion.future());
        flush(service);
    }

    @Test
    void rejectionTerminalizesAssociatedPlanNotAccepted() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 27, 270);
        OperationCompletionService.AdmissionProducer<String> admission = service
                .<String>tryAcquireAdmissionProducer(10, token)
                .orElseThrow();
        IllegalStateException rejection = new IllegalStateException("not accepted");
        assertTrue(admission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));

        assertTrue(admission.reject(rejection));
        assertSame(rejection, await(admission.delivery().future().handle((ignored, failure) -> failure)));
        assertEquals(0, completion.outstandingTerminalCount());
        completion.closeAdmission();
        OperationResult result = await(completion.future());
        assertEquals(TerminalStatus.NOT_ACCEPTED, result.terminalRecords().getFirst().status());
        assertSame(rejection, result.terminalRecords().getFirst().failure().orElseThrow());
        flush(service);
    }

    @Test
    void cancellationAfterDeliveryCannotDisturbGrantedOutcome() throws Exception {
        AtomicBoolean pauseNextOutcome = new AtomicBoolean();
        CountDownLatch outcomeEntryPaused = new CountDownLatch(1);
        CountDownLatch releaseOutcomeEntry = new CountDownLatch(1);
        OperationCompletionService service = new OperationCompletionService(
                "FAWE Outcome Race Test",
                () -> false,
                null,
                () -> {
                    if (!pauseNextOutcome.compareAndSet(true, false)) {
                        return;
                    }
                    outcomeEntryPaused.countDown();
                    try {
                        assertTrue(releaseOutcomeEntry.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                    }
                }
        );
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 28, 280);
        OperationCompletionService.AdmissionProducer<String> admission = service
                .<String>tryAcquireAdmissionProducer(11, token)
                .orElseThrow();
        assertTrue(admission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));

        AtomicReference<Boolean> cancellationResult = new AtomicReference<>();
        AtomicReference<Throwable> cancellationFailure = new AtomicReference<>();
        pauseNextOutcome.set(true);
        Thread cancellation = Thread.ofPlatform().start(() -> {
            try {
                cancellationResult.set(admission.cancel(new CancellationException("too late")));
            } catch (Throwable failure) {
                cancellationFailure.set(failure);
            }
        });
        try {
            assertTrue(outcomeEntryPaused.await(2, TimeUnit.SECONDS));
            assertTrue(admission.delivery().deliver("granted"));
            assertEquals("granted", await(admission.delivery().future()));
            long releaseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (service.outstandingAdmissionProducerCount() != 0 && System.nanoTime() < releaseDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(0, service.outstandingAdmissionProducerCount());
        } finally {
            releaseOutcomeEntry.countDown();
            cancellation.join(2_000);
        }

        assertFalse(cancellation.isAlive());
        assertNull(cancellationFailure.get());
        assertEquals(Boolean.FALSE, cancellationResult.get());
        assertEquals(1, completion.outstandingTerminalCount());
        assertTrue(token.terminal(terminal(
                operationId,
                28,
                280,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        )));
        completion.closeAdmission();
        await(completion.future());
        flush(service);
    }

    @Test
    void collaboratorHotPathsDoNotAcquireLifecycleLock() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken firstToken = registerPlan(service, completion, 28, 280);
        OperationCompletionService.PlanProducerToken secondToken = registerPlan(service, completion, 29, 290);
        OperationCompletionService.PlanProducerToken timerToken = registerPlan(service, completion, 30, 300);
        OperationCompletionService.PlanProducerToken cancellationToken = registerPlan(
                service,
                completion,
                31,
                310
        );
        OperationCompletionService.AdmissionProducer<String> firstAdmission = service
                .<String>tryAcquireAdmissionProducer(5, firstToken)
                .orElseThrow();
        OperationCompletionService.AdmissionProducer<String> secondAdmission = service
                .<String>tryAcquireAdmissionProducer(6, secondToken)
                .orElseThrow();
        OperationCompletionService.AdmissionProducer<Void> timerAdmission = service
                .<Void>tryAcquireAdmissionProducer(7, timerToken)
                .orElseThrow();
        OperationCompletionService.AdmissionProducer<Void> cancellationAdmission = service
                .<Void>tryAcquireAdmissionProducer(12, cancellationToken)
                .orElseThrow();
        assertTrue(firstAdmission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));
        assertTrue(secondAdmission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));
        assertTrue(timerAdmission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));
        assertTrue(cancellationAdmission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));
        ReentrantLock lifecycleLock = lifecycleLock(service);
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch callerDone = new CountDownLatch(1);
        CountDownLatch timerLinearized = new CountDownLatch(1);
        AtomicReference<Throwable> callerFailure = new AtomicReference<>();
        IllegalStateException rejection = new IllegalStateException("waiter rejected");
        CancellationException cancellation = new CancellationException("waiter cancelled");
        TimeoutException timeout = new TimeoutException("timer expired");
        Thread holder = Thread.ofPlatform().start(() -> {
            lifecycleLock.lock();
            try {
                lockHeld.countDown();
                assertTrue(releaseLock.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            } finally {
                lifecycleLock.unlock();
            }
        });
        assertTrue(lockHeld.await(2, TimeUnit.SECONDS));
        try {
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    assertTrue(firstToken.terminal(terminal(
                            operationId,
                            28,
                            280,
                            TerminalStatus.NO_CHANGE,
                            emptyReceipt(),
                            null
                    )));
                    assertTrue(firstAdmission.submit(() -> {
                    }));
                    assertTrue(firstAdmission.reject(rejection));
                    assertTrue(secondAdmission.delivery().deliver("granted"));
                    assertTrue(cancellationAdmission.cancel(cancellation));
                    timerAdmission.schedule(
                            Duration.ZERO,
                            () -> {
                                timerLinearized.countDown();
                                return true;
                            },
                            () -> timerAdmission.reject(timeout)
                    );
                } catch (Throwable failure) {
                    callerFailure.set(failure);
                } finally {
                    callerDone.countDown();
                }
            });
            assertTrue(callerDone.await(2, TimeUnit.SECONDS));
            assertTrue(timerLinearized.await(2, TimeUnit.SECONDS));
            assertNull(callerFailure.get());
            caller.join(2_000);
            assertFalse(caller.isAlive());
        } finally {
            releaseLock.countDown();
            holder.join(2_000);
        }

        assertSame(rejection, await(firstAdmission.delivery().future().handle((ignored, failure) -> failure)));
        assertEquals("granted", await(secondAdmission.delivery().future()));
        assertSame(cancellation,
                await(cancellationAdmission.delivery().future().handle((ignored, failure) -> failure)));
        assertSame(timeout, await(timerAdmission.delivery().future().handle((ignored, failure) -> failure)));
        assertTrue(secondToken.terminal(terminal(
                operationId,
                29,
                290,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        )));
        assertTrue(cancellationToken.terminal(terminal(
                operationId,
                31,
                310,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        )));
        completion.closeAdmission();
        await(completion.future());
        flush(service);
    }

    @Test
    void admissionDeadlineLinearizesBeforeSubmittingSettlement() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 26, 260);
        OperationCompletionService.AdmissionProducer<Void> admission = service
                .<Void>tryAcquireAdmissionProducer(4, token)
                .orElseThrow();
        AtomicBoolean linearized = new AtomicBoolean();
        TimeoutException timeout = new TimeoutException("admission expired");
        assertTrue(admission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));

        admission.schedule(
                Duration.ZERO,
                () -> linearized.compareAndSet(false, true),
                () -> admission.reject(timeout)
        );

        assertSame(timeout, await(admission.delivery().future().handle((ignored, failure) -> failure)));
        assertTrue(linearized.get());
        completion.closeAdmission();
        await(completion.future());
        flush(service);
    }

    @Test
    void flushReclampsAlreadyScheduledAdmissionDeadline() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = emptyCompletion(service, operationId);
        OperationCompletionService.PlanProducerToken token = registerPlan(service, completion, 32, 320);
        OperationCompletionService.AdmissionProducer<Void> admission = service
                .<Void>tryAcquireAdmissionProducer(13, token)
                .orElseThrow();
        assertTrue(admission.arm(deadlineNanos -> {
        }, failure -> {
            throw new AssertionError(failure);
        }));
        admission.schedule(
                Duration.ofSeconds(30),
                () -> true,
                () -> admission.reject(new TimeoutException("admission expired"))
        );
        assertTrue(token.terminal(terminal(
                operationId,
                32,
                320,
                TerminalStatus.NO_CHANGE,
                emptyReceipt(),
                null
        )));
        completion.closeAdmission();
        await(completion.future());

        Duration drainRemaining = Duration.ofSeconds(2);
        CompletionStage<Void> flushed = service.flush(drainRemaining);
        await(service.submit(() -> {
        }));
        Field clampField = admission.getClass().getDeclaredField("drainDeadlineTask");
        clampField.setAccessible(true);
        ScheduledFuture<?> drainClamp = (ScheduledFuture<?>) clampField.get(admission);
        assertNotNull(drainClamp);
        assertTrue(drainClamp.getDelay(TimeUnit.NANOSECONDS) <= drainRemaining.toNanos());

        CancellationException cancellation = new CancellationException("test complete");
        assertTrue(admission.cancel(cancellation));
        assertSame(cancellation, await(admission.delivery().future().handle((ignored, failure) -> failure)));
        await(flushed);
    }

    @Test
    void flushClampsActivePersistenceSettlementToDrainDeadline() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        HistoryPersistencePolicy longPolicy = new HistoryPersistencePolicy(
                5,
                Duration.ofSeconds(5),
                Duration.ZERO,
                Duration.ofSeconds(5)
        );
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                () -> {
                },
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                longPolicy,
                (record, attempt) -> new CompletableFuture<>()
        );
        registerPlan(service, completion, 1, 1);
        completion.terminal(terminal(
                operationId,
                1,
                1,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        var flushed = service.flush(Duration.ofMillis(40));
        OperationResult result = await(completion.future());
        await(flushed);

        assertEquals(HistorySettlement.UNAVAILABLE, result.terminalRecords().getFirst().applied().historySettlement());
        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        assertEquals(OperationCompletionService.State.TERMINATED, service.state());
    }

    @Test
    void flushClampsNeverSettlingFinalizerAndPreservesWorldStatus() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                () -> {
                },
                () -> new CompletableFuture<>(),
                Duration.ofSeconds(5),
                policy(1),
                DefaultOperationCompletion.PersistenceBoundary.notRequired()
        );
        registerPlan(service, completion, 3, 3);
        completion.terminal(terminal(
                operationId,
                3,
                3,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        CompletionStage<Void> flushed = service.flush(Duration.ofMillis(40));
        OperationResult result = await(completion.future());
        await(flushed);

        assertEquals(OperationResult.Classification.PARTIAL, result.classification());
        assertEquals(TerminalStatus.COMMITTED, result.terminalRecords().getFirst().status());
        assertInstanceOf(TimeoutException.class, result.failures().getFirst());
        assertEquals(OperationCompletionService.State.TERMINATED, service.state());
    }

    @Test
    void earlySettlementCancelsAndRemovesSharedTimerTasks() throws Exception {
        OperationCompletionService service = newService();
        UUID operationId = UUID.randomUUID();
        HistoryPersistencePolicy longPolicy = new HistoryPersistencePolicy(
                2,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30)
        );
        DefaultOperationCompletion completion = new DefaultOperationCompletion(
                operationId,
                service,
                () -> {
                },
                DefaultOperationCompletion.FinalizerBoundary.none(),
                Duration.ofSeconds(30),
                longPolicy,
                (record, attempt) -> CompletableFuture.completedFuture(null)
        );
        registerPlan(service, completion, 4, 4);
        completion.terminal(terminal(
                operationId,
                4,
                4,
                TerminalStatus.COMMITTED,
                appliedReceipt(HistorySettlement.NOT_REQUIRED),
                null
        ));
        completion.closeAdmission();

        await(completion.future());
        await(service.submit(() -> {
        }));

        assertEquals(0, service.scheduledTimerTaskCount());
        await(service.flush(Duration.ofSeconds(2)));
    }

    @Test
    void historyPolicyRejectsUnboundedOrNonPositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> new HistoryPersistencePolicy(
                0,
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new HistoryPersistencePolicy(
                1,
                Duration.ZERO,
                Duration.ZERO,
                Duration.ofSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new HistoryPersistencePolicy(
                1,
                Duration.ofSeconds(1),
                Duration.ofMillis(-1),
                Duration.ofSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new HistoryPersistencePolicy(
                1,
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ZERO
        ));
    }

    private static OperationCompletionService.Producer producer(OperationCompletionService service) {
        return service.registerProducer(
                new Object(),
                deadlineNanos -> {
                },
                failure -> {
                    throw new AssertionError(failure);
                }
        );
    }

    private static ReentrantLock lifecycleLock(OperationCompletionService service) throws Exception {
        Field field = OperationCompletionService.class.getDeclaredField("lifecycleLock");
        field.setAccessible(true);
        return (ReentrantLock) field.get(service);
    }

    private static void awaitPendingPublication(OperationCompletionService service) throws TimeoutException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (service.pendingOutcomePublicationCount() == 0) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new TimeoutException("Admission publication was not reserved");
            }
            Thread.onSpinWait();
        }
    }

    private static void drainNotifications(
            ConcurrentLinkedQueue<Runnable> notifications,
            CompletableFuture<?>... outcomes
    ) throws TimeoutException {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!allDone(outcomes)) {
            Runnable notification = notifications.poll();
            if (notification != null) {
                notification.run();
            } else if (System.nanoTime() >= deadlineNanos) {
                throw new TimeoutException("Notification queue did not publish every outcome");
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private static boolean allDone(CompletableFuture<?>[] outcomes) {
        for (CompletableFuture<?> outcome : outcomes) {
            if (!outcome.isDone()) {
                return false;
            }
        }
        return true;
    }

    private static DefaultOperationCompletion emptyCompletion(OperationCompletionService service, UUID operationId) {
        return new DefaultOperationCompletion(
                operationId,
                service,
                () -> {
                },
                DefaultOperationCompletion.FinalizerBoundary.none(),
                finalizerTimeout(),
                policy(1),
                DefaultOperationCompletion.PersistenceBoundary.notRequired()
        );
    }

}
