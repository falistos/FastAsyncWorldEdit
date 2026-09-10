package com.fastasyncworldedit.core.util.task;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

/**
 * Worker/control-plane allocator which gates same-chunk plans in registration order.
 * Registration and sequencing terminalization are not tick-thread entry points.
 */
public final class SameChunkPlanSequencer {

    private final OperationCompletionService completionService;
    private final Map<Long, ArrayDeque<Registration>> chunks = new HashMap<>();
    // A global monotonic source is also monotonic per chunk and prevents reuse after idle queues are evicted.
    private long nextPlanSequence;

    /**
     * @param completionService lifecycle-guaranteed service used to release predecessor gates
     */
    public SameChunkPlanSequencer(OperationCompletionService completionService) {
        this(completionService, 1);
    }

    SameChunkPlanSequencer(OperationCompletionService completionService, long initialPlanSequence) {
        this.completionService = Objects.requireNonNull(completionService, "completionService");
        if (initialPlanSequence <= 0) {
            throw new IllegalArgumentException("initialPlanSequence must be positive");
        }
        this.nextPlanSequence = initialPlanSequence;
    }

    /** Allocates the sequence before the caller registers the plan for admission or schedules it. */
    public Registration register(long chunkKey) {
        Registration registration;
        boolean activate;
        synchronized (this) {
            if (nextPlanSequence == Long.MAX_VALUE) {
                throw new IllegalStateException("Plan sequence space exhausted");
            }
            registration = new Registration(chunkKey, nextPlanSequence++);
            SequencerKey key = new SequencerKey(chunkKey, registration.planSequence);
            registration.producer = completionService.registerProducer(
                    key,
                    deadlineNanos -> drainExpired(registration, deadlineNanos),
                    failure -> transitionFailed(registration, failure)
            );
            ArrayDeque<Registration> queue = chunks.computeIfAbsent(chunkKey, ignored -> new ArrayDeque<>());
            activate = queue.isEmpty();
            queue.addLast(registration);
        }
        if (activate) {
            activate(registration);
        }
        return registration;
    }

    private void terminal(Registration registration) {
        Registration successor;
        synchronized (this) {
            if (registration.terminal) {
                return;
            }
            ArrayDeque<Registration> queue = chunks.get(registration.chunkKey);
            if (queue == null || queue.peekFirst() != registration) {
                throw new IllegalStateException("A same-chunk plan cannot terminate before its predecessor");
            }
            registration.terminal = true;
            queue.removeFirst();
            if (queue.isEmpty()) {
                chunks.remove(registration.chunkKey);
                successor = null;
            } else {
                successor = queue.peekFirst();
            }
        }
        registration.producer.tryComplete(() -> {
            if (successor != null) {
                activate(successor);
            }
        });
    }

    private void activate(Registration registration) {
        synchronized (this) {
            if (registration.readyPublicationQueued) {
                return;
            }
            registration.readyPublicationQueued = true;
        }
        registration.producer.execute(() -> completionService.publishOutcome(registration.ready, null));
    }

    private void drainExpired(Registration registration, long deadlineNanos) {
        boolean publishFailure;
        synchronized (this) {
            removeRegistration(registration);
            publishFailure = !registration.readyPublicationQueued;
            registration.readyPublicationQueued = true;
        }
        if (publishFailure) {
            completionService.publishFailure(
                    registration.ready,
                    new TimeoutException("Same-chunk sequencing drain deadline expired at " + deadlineNanos)
            );
        }
    }

    private void transitionFailed(Registration registration, Throwable failure) {
        boolean publishFailure;
        synchronized (this) {
            removeRegistration(registration);
            publishFailure = !registration.readyPublicationQueued;
            registration.readyPublicationQueued = true;
        }
        if (publishFailure) {
            completionService.publishFailure(registration.ready, failure);
        }
    }

    private void removeRegistration(Registration registration) {
        if (registration.terminal) {
            return;
        }
        registration.terminal = true;
        ArrayDeque<Registration> queue = chunks.get(registration.chunkKey);
        if (queue == null) {
            return;
        }
        boolean wasHead = queue.peekFirst() == registration;
        queue.remove(registration);
        if (queue.isEmpty()) {
            chunks.remove(registration.chunkKey);
        } else if (wasHead) {
            Registration successor = queue.peekFirst();
            completionService.submit(() -> activate(successor));
        }
    }

    public final class Registration {

        private final long chunkKey;
        private final long planSequence;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final CompletionStage<Void> readyView;
        private OperationCompletionService.Producer producer;
        private boolean readyPublicationQueued;
        private boolean terminal;

        private Registration(long chunkKey, long planSequence) {
            this.chunkKey = chunkKey;
            this.planSequence = planSequence;
            this.readyView = completionService.isolateStage(ready);
        }

        public long chunkKey() {
            return chunkKey;
        }

        /** Invariant across every callback, ownership rebind, retry, and finalizer for this plan. */
        public long planSequence() {
            return planSequence;
        }

        /** Completes only after every earlier registration for this chunk has terminated. */
        public CompletionStage<Void> ready() {
            return readyView;
        }

        /** Releases the next same-chunk registration. Repeated calls are ignored. */
        public void terminal() {
            SameChunkPlanSequencer.this.terminal(this);
        }

    }

    private record SequencerKey(long chunkKey, long planSequence) {
    }

}
