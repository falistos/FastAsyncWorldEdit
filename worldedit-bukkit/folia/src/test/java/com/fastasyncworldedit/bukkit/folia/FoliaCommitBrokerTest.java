package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.AppliedReceipt;
import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.fastasyncworldedit.core.util.task.ChunkTerminalRecord;
import com.fastasyncworldedit.core.util.task.DefaultOperationCompletion;
import com.fastasyncworldedit.core.util.task.EntityTarget;
import com.fastasyncworldedit.core.util.task.EntityTask;
import com.fastasyncworldedit.core.util.task.GlobalTask;
import com.fastasyncworldedit.core.util.task.HistoryPersistencePolicy;
import com.fastasyncworldedit.core.util.task.HistorySettlement;
import com.fastasyncworldedit.core.util.task.OperationCompletionService;
import com.fastasyncworldedit.core.util.task.OperationResult;
import com.fastasyncworldedit.core.util.task.PacketPhaseResult;
import com.fastasyncworldedit.core.util.task.RegionCall;
import com.fastasyncworldedit.core.util.task.RegionTask;
import com.fastasyncworldedit.core.util.task.TerminalStatus;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("auxiliaryclass")
class FoliaCommitBrokerTest {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(3);

    public static void main(String[] args) throws Exception {
        TestCase[] tests = {
                FoliaCommitBrokerTest::weightedDrrAndSameChunkSequenceAreDeterministic,
                FoliaCommitBrokerTest::sliceControllerUsesLaneDelayAndRuntimeToGrowAndShrink,
                FoliaCommitBrokerTest::transferRefusalAtDeadlineTerminalizesWithoutMutation,
                FoliaCommitBrokerTest::mergedRegionMigratesStaleMailboxBeforeAnyMutation,
                FoliaCommitBrokerTest::diagnosticLineUsesTheFrozenG4Mapping,
                FoliaCommitBrokerTest::diagnosticsExposeBrokerOwnedPlanCounters,
                FoliaCommitBrokerTest::normalSyncStrictlyPrecedesQueuedWhenFreeWork,
                FoliaCommitBrokerTest::continuationReentersTheSameFairMailbox,
                FoliaCommitBrokerTest::commitRuntimeStopsTheCurrentLaneSlice,
                FoliaCommitBrokerTest::deferredOwnedDispatchCannotMutateAfterTerminalization,
                FoliaCommitBrokerTest::operationCancellationAbandonsPlanBeforeMutation,
                FoliaCommitBrokerTest::enqueueDuringLaneScheduleReleaseCannotLoseItsWakeup,
                FoliaCommitBrokerTest::drainExpiryUsesPrebuiltTerminalAndReleasesProducer,
                FoliaCommitBrokerTest::rejectionHookHandsOffExactlyOnce
        };
        for (TestCase test : tests) {
            test.run(new FoliaCommitBrokerTest());
        }
        System.out.println("FoliaCommitBrokerTest: " + tests.length + " tests passed");
    }

    @Test
    void weightedDrrAndSameChunkSequenceAreDeterministic() {
        try (Harness harness = new Harness(64, () -> 0L)) {
            Operation interactive = harness.operation(FoliaBackpressure.Priority.INTERACTIVE);
            Operation normal = harness.operation(FoliaBackpressure.Priority.NORMAL);
            Operation bulk = harness.operation(FoliaBackpressure.Priority.BULK);
            List<FoliaCommitBroker.RegisteredPlan> interactivePlans = new ArrayList<>();
            List<FoliaCommitBroker.RegisteredPlan> normalPlans = new ArrayList<>();
            List<FoliaCommitBroker.RegisteredPlan> bulkPlans = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                interactivePlans.add(interactive.register(1_000 + index, 1, index, 0));
                normalPlans.add(normal.register(2_000 + index, 1, 100 + index, 0));
                bulkPlans.add(bulk.register(3_000 + index, 1, 200 + index, 0));
            }

            interactivePlans.forEach(interactive::admit);
            normalPlans.forEach(normal::admit);
            bulkPlans.forEach(bulk::admit);
            harness.dispatcher.runAll();

            assertEquals(
                    List.of("I", "I", "I", "I", "N", "N", "B"),
                    harness.commitOrder.subList(0, 7)
            );
            assertTrue(maximumRun(harness.commitOrder.subList(0, 14)) <= 4);

            Operation first = harness.operation(FoliaBackpressure.Priority.INTERACTIVE);
            Operation second = harness.operation(FoliaBackpressure.Priority.BULK);
            FoliaCommitBroker.RegisteredPlan firstPlan = first.register(9_000, 2, 400, 400);
            FoliaCommitBroker.RegisteredPlan secondPlan = second.register(9_000, 1, 400, 400);
            second.admit(secondPlan);
            first.admit(firstPlan);
            harness.dispatcher.runAll();

            int firstIndex = harness.commitKeys.indexOf(firstPlan.operationId() + ":2");
            int secondIndex = harness.commitKeys.indexOf(secondPlan.operationId() + ":1");
            assertTrue(firstIndex >= 0 && firstIndex < secondIndex);
        }
    }

    @Test
    void sliceControllerUsesLaneDelayAndRuntimeToGrowAndShrink() {
        FoliaCommitBroker.SliceTuning tuning = FoliaCommitBroker.SliceTuning.initial();
        FoliaCommitBroker.SliceController controller = new FoliaCommitBroker.SliceController(tuning);
        for (int sample = 0; sample < tuning.adjustmentWindow(); sample++) {
            controller.observe(true, TimeUnit.MILLISECONDS.toNanos(10), TimeUnit.MICROSECONDS.toNanos(100));
        }

        assertEquals(10, controller.cap());
        assertEquals(TimeUnit.MICROSECONDS.toNanos(875), controller.targetNanos());

        controller.observe(
                true,
                TimeUnit.MILLISECONDS.toNanos(76),
                TimeUnit.MICROSECONDS.toNanos(100)
        );
        assertEquals(5, controller.cap());
        assertEquals(437_500, controller.targetNanos());

        controller.observe(true, 0, TimeUnit.MICROSECONDS.toNanos(1_501));
        assertEquals(2, controller.cap());
        assertEquals(TimeUnit.MICROSECONDS.toNanos(250), controller.targetNanos());
    }

    @Test
    void transferRefusalAtDeadlineTerminalizesWithoutMutation() {
        AtomicLong clock = new AtomicLong(100);
        try (Harness harness = new Harness(1, clock::get)) {
            Operation holderOperation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            FoliaCommitBroker.RegisteredPlan holder = holderOperation.register(10, 1, 10, 10);
            FoliaBackpressure.Permit held = holderOperation.acquire(holder, harness.targetRegion, 1_000);

            Operation movingOperation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            FoliaCommitBroker.RegisteredPlan moving = movingOperation.register(20, 1, 20, 20);
            FoliaBackpressure.Permit movingPermit = movingOperation.acquire(moving, harness.sourceRegion, 200);
            harness.ownership.put(harness.coordinate(20, 20), harness.targetRegion);
            harness.broker.enqueue(moving, movingPermit, 1, 0);
            harness.clock.set(200);
            harness.dispatcher.runAll();

            movingOperation.closeAdmission();
            OperationResult result = await(movingOperation.completion.future());
            assertEquals(TerminalStatus.CANCELLED_BEFORE_MUTATION, result.terminalRecords().getFirst().status());
            assertEquals(0, harness.mutations.get());
            assertEquals(0, harness.broker.diagnostics(harness.sourceRegion).outstandingChunks());

            held.close();
            harness.broker.cancelPlan(holder, new IllegalStateException("test holder released"));
        }
    }

    @Test
    void mergedRegionMigratesStaleMailboxBeforeAnyMutation() {
        try (Harness harness = new Harness(16, () -> 0L)) {
            Operation operation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            for (int index = 0; index < 10; index++) {
                FoliaCommitBroker.RegisteredPlan plan = operation.register(100 + index, 1, 100 + index, 0);
                operation.admit(plan);
            }
            for (int index = 0; index < 10; index++) {
                harness.ownership.put(harness.coordinate(100 + index, 0), harness.targetRegion);
            }

            assertTrue(harness.dispatcher.runNext());
            assertEquals(0, harness.mutations.get());
            harness.dispatcher.runAll();

            assertEquals(10, harness.mutations.get());
            assertEquals(10, harness.broker.diagnostics(harness.targetRegion).rebindCount());
        }
    }

    @Test
    void diagnosticLineUsesTheFrozenG4Mapping() {
        RegionKey region = new RegionKey(UUID.fromString("00000000-0000-0000-0000-000000000014"), 7);
        StubDispatcher dispatcher = new StubDispatcher();
        dispatcher.liveTickets = 5;
        dispatcher.outstandingFutures = 6;
        StubBackpressure backpressure = new StubBackpressure(new FoliaBackpressure.Pressure(
                11,
                12,
                13,
                14,
                15,
                16,
                17,
                18
        ));
        FoliaCommitBroker broker = new FoliaCommitBroker(dispatcher, backpressure);

        DiagnosticSnapshot snapshot = broker.diagnostics(region);
        assertEquals(11, snapshot.readyChunks());
        assertEquals(12, snapshot.readyBytes());
        assertEquals(13, snapshot.finalizerChains());
        assertEquals(14, snapshot.waiters());
        assertEquals(15, snapshot.scheduledDrains());
        assertEquals(5, snapshot.liveTickets());
        assertEquals(6, snapshot.outstandingFutures());
        assertEquals(0, snapshot.packetMailboxBytes());
        assertEquals(0, snapshot.outstandingChunks());
        assertEquals(0, snapshot.rebindCount());
        assertEquals(16, snapshot.oldestReadyNanos());
        assertEquals(
                "FAWE_QUEUE depth=11 inflight=28 outstanding=0 region=" + region,
                broker.diagnosticLine(snapshot)
        );
        broker.diagnostics(new RegionKey(region.worldId(), 8));
        assertEquals(
                "FAWE_QUEUE depth=22 inflight=56 outstanding=0 region=global",
                broker.diagnosticLine(broker.diagnosticsGlobal())
        );
    }

    @Test
    void diagnosticsExposeBrokerOwnedPlanCounters() {
        try (Harness harness = new Harness(8, () -> 0L)) {
            Operation operation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            FoliaCommitBroker.RegisteredPlan plan = operation.register(50, 1, 50, 0);
            FoliaBackpressure.Permit permit = operation.acquire(plan, harness.sourceRegion, 10_000);
            harness.broker.enqueue(plan, permit, 1, 321);

            DiagnosticSnapshot regional = harness.broker.diagnostics(harness.sourceRegion);
            assertEquals(321, regional.packetMailboxBytes());
            assertEquals(1, regional.outstandingChunks());
            assertEquals(321, harness.broker.diagnosticsGlobal().packetMailboxBytes());
            assertEquals(1, harness.broker.diagnosticsGlobal().outstandingChunks());

            assertTrue(harness.broker.cancelPlan(plan, new CancellationException("diagnostic cleanup")));
            assertEquals(0, harness.broker.diagnosticsGlobal().packetMailboxBytes());
            assertEquals(0, harness.broker.diagnosticsGlobal().outstandingChunks());
        }
    }

    @Test
    void normalSyncStrictlyPrecedesQueuedWhenFreeWork() {
        try (Harness harness = new Harness(8, () -> 0L)) {
            List<String> order = new ArrayList<>();
            CompletionStage<Void> whenFree = harness.broker.scheduleSyncGlobal(
                    FoliaCommitBroker.SyncPriority.WHEN_FREE,
                    () -> order.add("when-free")
            );
            CompletionStage<Void> normal = harness.broker.scheduleSyncGlobal(
                    FoliaCommitBroker.SyncPriority.NORMAL,
                    () -> order.add("normal")
            );

            harness.dispatcher.runAll();

            await(normal);
            await(whenFree);
            assertEquals(List.of("normal", "when-free"), order);
        }
    }

    @Test
    void continuationReentersTheSameFairMailbox() {
        try (Harness harness = new Harness(8, () -> 0L)) {
            Operation operation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            FoliaCommitBroker.RegisteredPlan plan = operation.register(60, 1, 60, 60);
            harness.continueInitial.set(true);
            operation.admit(plan);
            harness.dispatcher.runAll();

            assertEquals(1, harness.broker.diagnosticsGlobal().outstandingChunks());
            FoliaBackpressure.Permit continuation = await(plan.admission().acquireContinuation(
                    harness.sourceRegion,
                    operation.demand(true, 10_000),
                    new CompletableFuture<>()
            ));
            Operation interactive = harness.operation(FoliaBackpressure.Priority.INTERACTIVE);
            FoliaCommitBroker.RegisteredPlan competing = interactive.register(61, 1, 61, 61);
            interactive.admit(competing);
            harness.broker.enqueueContinuation(plan, continuation, 1, 0);
            harness.dispatcher.runAll();
            operation.closeAdmission();
            interactive.closeAdmission();

            OperationResult result = await(operation.completion.future());
            assertEquals(TerminalStatus.NO_CHANGE, result.terminalRecords().getFirst().status());
            assertEquals(List.of("N", "I", "N"), harness.commitOrder);
            assertEquals(3, harness.mutations.get());
        }
    }

    @Test
    void commitRuntimeStopsTheCurrentLaneSlice() {
        try (Harness harness = new Harness(16, () -> 0L)) {
            harness.commitNanos.set(TimeUnit.MICROSECONDS.toNanos(200));
            Operation operation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            for (int index = 0; index < 10; index++) {
                FoliaCommitBroker.RegisteredPlan plan = operation.register(70 + index, 1, 70 + index, 0);
                FoliaBackpressure.Permit permit = operation.acquire(
                        plan,
                        harness.sourceRegion,
                        TimeUnit.SECONDS.toNanos(1)
                );
                harness.broker.enqueue(plan, permit, 1, 0);
            }

            assertTrue(harness.dispatcher.runNext());
            assertEquals(4, harness.mutations.get());
            harness.dispatcher.runAll();
        }
    }

    @Test
    void deferredOwnedDispatchCannotMutateAfterTerminalization() {
        try (Harness harness = new Harness(8, () -> 0L)) {
            harness.dispatcher.inlineOwnedCallbacks = false;
            Operation operation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            FoliaCommitBroker.RegisteredPlan plan = operation.register(75, 1, 75, 0);
            operation.admit(plan);
            harness.dispatcher.runAll();
            operation.closeAdmission();

            OperationResult result = await(operation.completion.future());
            assertEquals(TerminalStatus.FAILED_BEFORE_MUTATION, result.terminalRecords().getFirst().status());
            assertEquals(0, harness.mutations.get());
        }
    }

    @Test
    void operationCancellationAbandonsPlanBeforeMutation() {
        try (Harness harness = new Harness(8, () -> 0L)) {
            Operation operation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            FoliaCommitBroker.RegisteredPlan plan = operation.register(76, 1, 76, 0);
            FoliaBackpressure.Permit permit = operation.acquire(plan, harness.sourceRegion, 10_000);
            assertTrue(harness.broker.cancelPlan(plan, new CancellationException("operation cancelled")));
            harness.broker.enqueue(plan, permit, 1, 123);
            harness.dispatcher.runAll();
            operation.closeAdmission();

            OperationResult result = await(operation.completion.future());
            assertEquals(TerminalStatus.CANCELLED_BEFORE_MUTATION, result.terminalRecords().getFirst().status());
            assertEquals(0, harness.mutations.get());
            assertEquals(0, harness.broker.diagnosticsGlobal().packetMailboxBytes());
        }
    }

    @Test
    void enqueueDuringLaneScheduleReleaseCannotLoseItsWakeup() throws Exception {
        CountDownLatch scheduleReleaseEntered = new CountDownLatch(1);
        CountDownLatch allowScheduleRelease = new CountDownLatch(1);
        AtomicBoolean blockOnce = new AtomicBoolean(true);
        Runnable probe = () -> {
            if (blockOnce.compareAndSet(true, false)) {
                scheduleReleaseEntered.countDown();
                awaitLatch(allowScheduleRelease);
            }
        };
        try (Harness harness = new Harness(8, () -> 0L, probe)) {
            Operation operation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            FoliaCommitBroker.RegisteredPlan first = operation.register(80, 1, 80, 0);
            operation.admit(first);
            Thread drainer = Thread.ofPlatform().start(harness.dispatcher::runNext);
            assertTrue(scheduleReleaseEntered.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));

            FoliaCommitBroker.RegisteredPlan raced = operation.register(81, 1, 81, 0);
            operation.admit(raced);
            allowScheduleRelease.countDown();
            drainer.join(TEST_TIMEOUT.toMillis());
            assertTrue(!drainer.isAlive());
            harness.dispatcher.runAll();

            assertEquals(2, harness.mutations.get());
        }
    }

    @Test
    void drainExpiryUsesPrebuiltTerminalAndReleasesProducer() {
        try (Harness harness = new Harness(8, () -> 0L)) {
            Operation operation = harness.operation(FoliaBackpressure.Priority.NORMAL);
            operation.register(50, 1, 50, 50);
            operation.closeAdmission();

            await(harness.service.flush(Duration.ofMillis(50)));
            OperationResult result = await(operation.completion.future());

            assertEquals(TerminalStatus.CANCELLED_BEFORE_MUTATION, result.terminalRecords().getFirst().status());
            assertEquals(0, harness.service.registeredProducerCount());
            assertEquals(0, harness.mutations.get());
        }
    }

    @Test
    void rejectionHookHandsOffExactlyOnce() throws Exception {
        OperationCompletionService service = new OperationCompletionService("Task 14 rejection handoff");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        DefaultFoliaBackpressure.AdmissionRejectionHandler hook = FoliaCommitBroker.rejectionHook(
                service,
                (region, demand, failure) -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    awaitLatch(release);
                }
        );
        RegionKey region = new RegionKey();
        FoliaBackpressure.Demand demand = new FoliaBackpressure.Demand(
                UUID.randomUUID(),
                1,
                0,
                0,
                FoliaBackpressure.Priority.NORMAL,
                false,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        );

        long started = System.nanoTime();
        hook.failed(
                region,
                demand,
                DefaultFoliaBackpressure.AdmissionFailureOutcome.CANCELLED,
                new CancellationException("cancelled")
        );
        hook.failed(
                region,
                demand,
                DefaultFoliaBackpressure.AdmissionFailureOutcome.REJECTED,
                new IllegalStateException("rejected")
        );
        long elapsed = System.nanoTime() - started;

        assertTrue(elapsed < TimeUnit.MILLISECONDS.toNanos(100));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(1, calls.get());
        release.countDown();
        await(service.flush(Duration.ofSeconds(1)));
    }

    private static int maximumRun(List<String> values) {
        int maximum = 0;
        int current = 0;
        String previous = null;
        for (String value : values) {
            if (value.equals(previous)) {
                current++;
            } else {
                previous = value;
                current = 1;
            }
            maximum = Math.max(maximum, current);
        }
        return maximum;
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().orTimeout(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
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

    private static World world() {
        return (World) Proxy.newProxyInstance(
                World.class.getClassLoader(),
                new Class<?>[]{World.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString", "getName", "getNameUnsafe" -> "task-14-world";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }

    @FunctionalInterface
    private interface TestCase {

        void run(FoliaCommitBrokerTest test) throws Exception;

    }

    private static final class Harness implements AutoCloseable {

        private final World world = world();
        private final RegionKey sourceRegion = new RegionKey(UUID.randomUUID(), 1);
        private final RegionKey targetRegion = new RegionKey(sourceRegion.worldId(), 2);
        private final AtomicLong clock;
        private final StubDispatcher dispatcher = new StubDispatcher();
        private final Map<String, RegionKey> ownership = new ConcurrentHashMap<>();
        private final OperationCompletionService service = new OperationCompletionService("Task 14 broker test");
        private final DefaultFoliaBackpressure backpressure;
        private final FoliaCommitBroker broker;
        private final List<Operation> operations = new ArrayList<>();
        private final List<String> commitOrder = new ArrayList<>();
        private final List<String> commitKeys = new ArrayList<>();
        private final AtomicInteger mutations = new AtomicInteger();
        private final AtomicBoolean continueInitial = new AtomicBoolean();
        private final AtomicLong commitNanos = new AtomicLong();

        private Harness(int regionalChunks, LongSupplier initialTime) {
            this(regionalChunks, initialTime, () -> {
            });
        }

        private Harness(int regionalChunks, LongSupplier initialTime, Runnable beforeScheduleRelease) {
            this.clock = new AtomicLong(initialTime.getAsLong());
            this.dispatcher.owner = (chunkX, chunkZ) -> ownership.get(coordinate(chunkX, chunkZ));
            this.backpressure = new DefaultFoliaBackpressure(
                    new FoliaBackpressure.Limits(
                            regionalChunks,
                            1 << 20,
                            64,
                            64,
                            4_096,
                            1L << 30,
                            4_096
                    ),
                    8,
                    service,
                    (region, demand, outcome, failure) -> {
                    },
                    clock::get
            );
            FoliaCommitBroker.RegionObserver observer = new FoliaCommitBroker.RegionObserver() {
                @Override
                public Optional<RegionKey> lastRegionHint(World targetWorld, int chunkX, int chunkZ) {
                    return Optional.ofNullable(ownership.get(coordinate(chunkX, chunkZ)));
                }

                @Override
                public RegionKey currentRegion(World targetWorld) {
                    return dispatcher.currentRegion;
                }

                @Override
                public RegionKey currentRegion(EntityTarget target) {
                    return dispatcher.currentRegion;
                }

                @Override
                public boolean ownsChunk(World targetWorld, int chunkX, int chunkZ) {
                    return dispatcher.currentRegion != null
                            && dispatcher.currentRegion.equals(ownership.get(coordinate(chunkX, chunkZ)));
                }

                @Override
                public boolean ownsEntity(EntityTarget target) {
                    return false;
                }
            };
            this.broker = new FoliaCommitBroker(
                    dispatcher,
                    backpressure,
                    service,
                    observer,
                    (ticket, plan, continuation) -> {
                        clock.addAndGet(commitNanos.get());
                        mutations.incrementAndGet();
                        String label = operations.stream()
                                .filter(operation -> operation.id.equals(plan.operationId()))
                                .findFirst()
                                .map(operation -> operation.priority.name().substring(0, 1))
                                .orElse("?");
                        commitOrder.add(label);
                        commitKeys.add(plan.operationId() + ":" + plan.planSequence());
                        if (!continuation && continueInitial.compareAndSet(true, false)) {
                            return FoliaCommitBroker.CommitOutcome.continuePlan();
                        }
                        return FoliaCommitBroker.CommitOutcome.terminal(new ChunkTerminalRecord(
                                plan.operationId(),
                                plan.chunkKey(),
                                plan.planSequence(),
                                TerminalStatus.NO_CHANGE,
                                emptyReceipt(),
                                Optional.empty()
                        ));
                    },
                    FoliaCommitBroker.SliceTuning.initial(),
                    clock::get,
                    beforeScheduleRelease,
                    dispatcher::handoff
            );
        }

        private Operation operation(FoliaBackpressure.Priority priority) {
            Operation operation = new Operation(this, priority);
            operations.add(operation);
            return operation;
        }

        private String coordinate(int chunkX, int chunkZ) {
            return chunkX + ":" + chunkZ;
        }

        @Override
        public void close() {
            for (Operation operation : operations) {
                operation.close();
            }
            await(service.flush(Duration.ofSeconds(2)));
        }

    }

    private static final class Operation {

        private final Harness harness;
        private final UUID id = UUID.randomUUID();
        private final FoliaBackpressure.Priority priority;
        private final DefaultOperationCompletion completion;
        private final List<FoliaCommitBroker.RegisteredPlan> plans = new ArrayList<>();
        private boolean admissionClosed;

        private Operation(Harness harness, FoliaBackpressure.Priority priority) {
            this.harness = harness;
            this.priority = priority;
            this.completion = new DefaultOperationCompletion(
                    id,
                    harness.service,
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
        }

        private FoliaCommitBroker.RegisteredPlan register(
                long chunkKey,
                long planSequence,
                int chunkX,
                int chunkZ
        ) {
            harness.ownership.putIfAbsent(harness.coordinate(chunkX, chunkZ), harness.sourceRegion);
            FoliaCommitBroker.RegisteredPlan plan = harness.broker.registerPlan(
                    completion,
                    chunkKey,
                    planSequence,
                    new ChunkTarget(harness.world, chunkX, chunkZ)
            ).orElseThrow();
            plans.add(plan);
            return plan;
        }

        private void admit(FoliaCommitBroker.RegisteredPlan plan) {
            FoliaBackpressure.Permit permit = acquire(plan, harness.sourceRegion, 10_000);
            harness.broker.enqueue(plan, permit, 1, 0);
        }

        private FoliaBackpressure.Permit acquire(
                FoliaCommitBroker.RegisteredPlan plan,
                RegionKey region,
                long deadlineNanos
        ) {
            return plan.admission().tryAcquire(
                    region,
                    demand(false, deadlineNanos)
            ).orElseThrow();
        }

        private FoliaBackpressure.Demand demand(boolean previouslyAccepted, long deadlineNanos) {
            return new FoliaBackpressure.Demand(
                    id,
                    1,
                    0,
                    previouslyAccepted ? 1 : 0,
                    priority,
                    previouslyAccepted,
                    deadlineNanos
            );
        }

        private void closeAdmission() {
            if (!admissionClosed) {
                admissionClosed = true;
                completion.closeAdmission();
            }
        }

        private void close() {
            for (FoliaCommitBroker.RegisteredPlan plan : plans) {
                harness.broker.cancelPlan(plan, new IllegalStateException("test cleanup"));
            }
            closeAdmission();
            await(completion.future());
        }

    }

    private static class StubDispatcher implements FoliaRegionDispatcher {

        private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Runnable> handoffs = new ConcurrentLinkedQueue<>();
        private BiFunction<Integer, Integer, RegionKey> owner = (chunkX, chunkZ) -> null;
        private volatile RegionKey currentRegion;
        private final ThreadLocal<Boolean> insideCallback = ThreadLocal.withInitial(() -> false);
        private boolean inlineOwnedCallbacks = true;
        private int liveTickets;
        private int outstandingFutures;

        @Override
        public CompletionStage<Void> onRegion(
                World world,
                int chunkX,
                int chunkZ,
                TaskKind kind,
                RegionTask task
        ) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            RegionKey region = owner.apply(chunkX, chunkZ);
            Runnable callback = () -> runVoid(result, region, () -> task.run(null));
            if (inlineOwnedCallbacks && insideCallback.get() && Objects.equals(currentRegion, region)) {
                callback.run();
            } else {
                callbacks.add(callback);
            }
            return result;
        }

        @Override
        public <T> CompletionStage<T> onRegion(
                World world,
                int chunkX,
                int chunkZ,
                TaskKind kind,
                RegionCall<T> call
        ) {
            CompletableFuture<T> result = new CompletableFuture<>();
            RegionKey region = owner.apply(chunkX, chunkZ);
            Runnable callback = () -> runValue(result, region, () -> result.complete(call.call(null)));
            if (inlineOwnedCallbacks && insideCallback.get() && Objects.equals(currentRegion, region)) {
                callback.run();
            } else {
                callbacks.add(callback);
            }
            return result;
        }

        @Override
        public CompletionStage<Void> onEntity(Entity entity, TaskKind kind, EntityTask task) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            Runnable callback = () -> runVoid(result, currentRegion, () -> task.run(null));
            if (inlineOwnedCallbacks && insideCallback.get()) {
                callback.run();
            } else {
                callbacks.add(callback);
            }
            return result;
        }

        @Override
        public CompletionStage<Void> onGlobal(TaskKind kind, GlobalTask task) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            Runnable callback = () -> runVoid(result, null, task::run);
            if (inlineOwnedCallbacks && insideCallback.get() && currentRegion == null) {
                callback.run();
            } else {
                callbacks.add(callback);
            }
            return result;
        }

        @Override
        public void stopAccepting(Throwable reason) {
        }

        @Override
        public CompletionStage<DrainReport> drain(Duration deadline) {
            return CompletableFuture.completedFuture(new DrainReport(0, 0, List.of()));
        }

        @Override
        public int liveTickets() {
            return liveTickets;
        }

        @Override
        public int outstandingFutures() {
            return outstandingFutures;
        }

        private void runAll() {
            int emptyPolls = 0;
            while (emptyPolls < 5) {
                Runnable handoff = handoffs.poll();
                if (runNext() || handoff != null) {
                    if (handoff != null) {
                        handoff.run();
                    }
                    emptyPolls = 0;
                } else {
                    emptyPolls++;
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
            }
        }

        private boolean runNext() {
            Runnable callback = callbacks.poll();
            if (callback == null) {
                return false;
            }
            callback.run();
            return true;
        }

        private void handoff(Runnable command) {
            handoffs.add(command);
        }

        private void runVoid(CompletableFuture<Void> result, RegionKey region, Runnable task) {
            RegionKey previousRegion = currentRegion;
            boolean previousInside = insideCallback.get();
            currentRegion = region;
            insideCallback.set(true);
            try {
                task.run();
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                currentRegion = previousRegion;
                insideCallback.set(previousInside);
            }
        }

        private <T> void runValue(CompletableFuture<T> result, RegionKey region, Runnable task) {
            RegionKey previousRegion = currentRegion;
            boolean previousInside = insideCallback.get();
            currentRegion = region;
            insideCallback.set(true);
            try {
                task.run();
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                currentRegion = previousRegion;
                insideCallback.set(previousInside);
            }
        }

    }

    private static final class StubBackpressure implements FoliaBackpressure {

        private final Pressure pressure;

        private StubBackpressure(Pressure pressure) {
            this.pressure = pressure;
        }

        @Override
        public Limits limits() {
            return new Limits(1, 1, 1, 1, 1, 1, 1);
        }

        @Override
        public CompletionStage<Permit> acquire(
                RegionKey region,
                Demand demand,
                CompletionStage<?> cancellationSignal
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Permit> tryAcquire(RegionKey region, Demand demand) {
            return Optional.empty();
        }

        @Override
        public CompletionStage<Permit> acquireContinuation(
                RegionKey region,
                Demand demand,
                CompletionStage<?> cancellationSignal
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean transfer(Permit permit, RegionKey actualRegion) {
            return false;
        }

        @Override
        public Pressure pressure(RegionKey region) {
            return pressure;
        }

        @Override
        public void recordScheduleDelay(RegionKey region, long delayNanos) {
        }

        @Override
        public void recordSlice(RegionKey region, int chunks, long runtimeNanos) {
        }

        @Override
        public void stopAccepting(Throwable reason) {
        }

    }

}
