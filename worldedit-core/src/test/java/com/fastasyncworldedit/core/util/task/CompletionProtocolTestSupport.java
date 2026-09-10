package com.fastasyncworldedit.core.util.task;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

final class CompletionProtocolTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(3);

    private CompletionProtocolTestSupport() {
    }

    static OperationCompletionService newService() {
        return new OperationCompletionService("FAWE Completion Test");
    }

    static HistoryPersistencePolicy policy(int maxAttempts) {
        return new HistoryPersistencePolicy(
                maxAttempts,
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(2)
        );
    }

    static Duration finalizerTimeout() {
        return Duration.ofSeconds(2);
    }

    static AppliedReceipt emptyReceipt() {
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

    static AppliedReceipt appliedReceipt(HistorySettlement settlement) {
        return new AppliedReceipt(
                1,
                List.of(),
                List.of(),
                List.of(),
                0,
                PacketPhaseResult.noPackets(),
                settlement
        );
    }

    static ChunkTerminalRecord terminal(
            UUID operationId,
            long chunkKey,
            long planSequence,
            TerminalStatus status,
            AppliedReceipt receipt,
            Throwable failure
    ) {
        return new ChunkTerminalRecord(
                operationId,
                chunkKey,
                planSequence,
                status,
                receipt,
                Optional.ofNullable(failure)
        );
    }

    static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    static void flush(OperationCompletionService service) throws Exception {
        await(service.flush(WAIT));
    }

    static void awaitDrainExpiry(OperationCompletionService service) throws TimeoutException {
        long deadlineNanos = System.nanoTime() + WAIT.toNanos();
        while (!service.drainDeadlineExpired()) {
            if (System.nanoTime() >= deadlineNanos) {
                throw new TimeoutException("Completion-service drain expiry was not published");
            }
            Thread.onSpinWait();
        }
    }

    static OperationCompletionService.PlanProducerToken registerPlan(
            OperationCompletionService service,
            DefaultOperationCompletion completion,
            long chunkKey,
            long planSequence
    ) {
        return registerPlan(service, completion, chunkKey, planSequence, deadlineNanos -> {
        });
    }

    static OperationCompletionService.PlanProducerToken registerPlan(
            OperationCompletionService service,
            DefaultOperationCompletion completion,
            long chunkKey,
            long planSequence,
            OperationCompletionService.FlushExpiryHook ownerExpiryHook
    ) {
        OperationCompletionService.PlanProducerToken token = acquirePlan(
                service,
                completion.operationId(),
                chunkKey,
                planSequence,
                ownerExpiryHook
        );
        completion.register(token);
        return token;
    }

    static OperationCompletionService.PlanProducerToken acquirePlan(
            OperationCompletionService service,
            UUID operationId,
            long chunkKey,
            long planSequence
    ) {
        return acquirePlan(service, operationId, chunkKey, planSequence, deadlineNanos -> {
        });
    }

    static OperationCompletionService.PlanProducerToken acquirePlan(
            OperationCompletionService service,
            UUID operationId,
            long chunkKey,
            long planSequence,
            OperationCompletionService.FlushExpiryHook ownerExpiryHook
    ) {
        return service.tryAcquirePlanProducer(
                operationId,
                chunkKey,
                planSequence,
                ownerExpiryHook,
                failure -> {
                    throw new AssertionError(failure);
                }
        ).orElseThrow();
    }

    static void runConcurrently(Runnable... tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.length);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.length);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int index = 0; index < tasks.length; index++) {
            Runnable task = tasks[index];
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    await(start);
                    task.run();
                } catch (Throwable thrown) {
                    failure.compareAndSet(null, thrown);
                } finally {
                    done.countDown();
                }
            }, "FAWE Completion Race " + index);
            thread.start();
        }
        await(ready);
        start.countDown();
        await(done);
        Throwable thrown = failure.get();
        if (thrown instanceof Exception exception) {
            throw exception;
        }
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw new AssertionError(thrown);
        }
    }

    static CompletionServiceBlock blockCompletionService(OperationCompletionService service) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        service.submit(() -> {
            entered.countDown();
            try {
                await(release);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            } catch (TimeoutException failure) {
                throw new AssertionError(failure);
            }
        });
        await(entered);
        return new CompletionServiceBlock(release);
    }

    private static void await(CountDownLatch latch) throws InterruptedException, TimeoutException {
        if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Concurrent test fixture timed out");
        }
    }

    record CompletionServiceBlock(CountDownLatch release) implements AutoCloseable {

        boolean isHolding() {
            return release.getCount() != 0;
        }

        @Override
        public void close() {
            release.countDown();
        }

    }

}
