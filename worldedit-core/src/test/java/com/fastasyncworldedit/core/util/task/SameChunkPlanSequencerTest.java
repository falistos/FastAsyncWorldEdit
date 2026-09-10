package com.fastasyncworldedit.core.util.task;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.await;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.blockCompletionService;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.flush;
import static com.fastasyncworldedit.core.util.task.CompletionProtocolTestSupport.newService;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SameChunkPlanSequencerTest {

    @Test
    void releasesSameChunkPlansInRegistrationOrderWhileDisjointChunksProceed() throws Exception {
        OperationCompletionService service = newService();
        SameChunkPlanSequencer sequencer = new SameChunkPlanSequencer(service);
        SameChunkPlanSequencer.Registration first = sequencer.register(1);
        SameChunkPlanSequencer.Registration second = sequencer.register(1);
        SameChunkPlanSequencer.Registration disjoint = sequencer.register(2);
        CompletableFuture<Void> firstReady = first.ready().toCompletableFuture();
        CompletableFuture<Void> secondReady = second.ready().toCompletableFuture();
        CompletableFuture<Void> disjointReady = disjoint.ready().toCompletableFuture();

        assertTrue(first.planSequence() < second.planSequence());
        assertTrue(second.planSequence() < disjoint.planSequence());
        assertNotEquals(first.planSequence(), disjoint.planSequence());
        assertNotEquals(second.planSequence(), disjoint.planSequence());
        assertThrows(IllegalStateException.class, second::terminal);

        await(firstReady);
        await(disjointReady);
        assertFalse(secondReady.isDone());

        first.terminal();
        await(secondReady);
        assertTrue(secondReady.isDone());

        first.terminal();
        second.terminal();
        disjoint.terminal();
        flush(service);
    }

    @Test
    void rejectsAllocationBeforePlanSequenceWouldOverflow() throws Exception {
        OperationCompletionService service = newService();
        SameChunkPlanSequencer sequencer = new SameChunkPlanSequencer(service, Long.MAX_VALUE - 1);

        SameChunkPlanSequencer.Registration last = sequencer.register(1);

        assertEquals(Long.MAX_VALUE - 1, last.planSequence());
        assertThrows(IllegalStateException.class, () -> sequencer.register(2));
        await(last.ready());
        last.terminal();
        flush(service);
    }

    @Test
    void headCanTerminalBeforeReadyWithoutWedgingChunkLane() throws Exception {
        OperationCompletionService service = newService();
        SameChunkPlanSequencer sequencer = new SameChunkPlanSequencer(service);
        SameChunkPlanSequencer.Registration first;
        SameChunkPlanSequencer.Registration successor;

        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            first = sequencer.register(3);
            first.terminal();
            successor = sequencer.register(3);
        }

        await(first.ready());
        await(successor.ready());
        successor.terminal();
        flush(service);
    }

    @Test
    void blockedReadyConsumerCannotHoldCompletionSerialization() throws Exception {
        OperationCompletionService service = newService();
        SameChunkPlanSequencer sequencer = new SameChunkPlanSequencer(service);
        CompletableFuture<Void> consumerEntered = new CompletableFuture<>();
        CompletableFuture<Void> releaseConsumer = new CompletableFuture<>();
        SameChunkPlanSequencer.Registration registration;
        CompletionStage<Void> consumer;

        try (var block = blockCompletionService(service)) {
            assertTrue(block.isHolding());
            registration = sequencer.register(4);
            consumer = registration.ready().thenRun(() -> {
                consumerEntered.complete(null);
                releaseConsumer.join();
            });
        }
        await(consumerEntered);
        try {
            await(service.submit(() -> {
            }));
            assertFalse(releaseConsumer.isDone());
        } finally {
            releaseConsumer.complete(null);
        }
        await(consumer);
        registration.terminal();
        flush(service);
    }

    @Test
    void readyContinuationAttachedAfterTerminationIsStillIsolated() throws Exception {
        OperationCompletionService service = newService();
        SameChunkPlanSequencer sequencer = new SameChunkPlanSequencer(service);
        SameChunkPlanSequencer.Registration registration = sequencer.register(5);
        await(registration.ready());
        registration.terminal();
        flush(service);
        Thread attachingThread = Thread.currentThread();
        AtomicBoolean onControl = new AtomicBoolean();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        await(registration.ready().thenRun(() -> {
            onControl.set(service.isCompletionThread());
            callbackThread.set(Thread.currentThread());
        }));

        assertFalse(onControl.get());
        assertNotEquals(attachingThread, callbackThread.get());
    }

    @Test
    void flushExpiryReleasesUnterminatedChunkLane() throws Exception {
        OperationCompletionService service = newService();
        SameChunkPlanSequencer sequencer = new SameChunkPlanSequencer(service);
        SameChunkPlanSequencer.Registration first = sequencer.register(6);
        SameChunkPlanSequencer.Registration second = sequencer.register(6);
        CompletionStage<Throwable> secondFailure = second.ready().handle((ignored, failure) -> failure);
        await(first.ready());

        await(service.flush(Duration.ofMillis(40)));

        assertTrue(await(secondFailure) != null);
        assertEquals(OperationCompletionService.State.TERMINATED, service.state());
    }

}
