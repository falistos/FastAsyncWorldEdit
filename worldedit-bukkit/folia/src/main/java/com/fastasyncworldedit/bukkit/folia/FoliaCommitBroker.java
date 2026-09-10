package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.AppliedReceipt;
import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.fastasyncworldedit.core.util.task.ChunkTerminalRecord;
import com.fastasyncworldedit.core.util.task.DefaultOperationCompletion;
import com.fastasyncworldedit.core.util.task.EntityTarget;
import com.fastasyncworldedit.core.util.task.EntityTask;
import com.fastasyncworldedit.core.util.task.GlobalTask;
import com.fastasyncworldedit.core.util.task.HistorySettlement;
import com.fastasyncworldedit.core.util.task.OperationCompletionService;
import com.fastasyncworldedit.core.util.task.OperationCompletionService.PlanProducerToken;
import com.fastasyncworldedit.core.util.task.PacketPhaseResult;
import com.fastasyncworldedit.core.util.task.RegionCall;
import com.fastasyncworldedit.core.util.task.RegionTask;
import com.fastasyncworldedit.core.util.task.RegionTicket;
import com.fastasyncworldedit.core.util.task.TerminalStatus;
import com.sk89q.worldedit.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Per-observed-region lane engine. Region identities group work and accounting; the owner check
 * immediately before each action remains the only execution authority.
 */
public final class FoliaCommitBroker {

    private static final AppliedReceipt EMPTY_RECEIPT = new AppliedReceipt(
            0,
            List.of(),
            List.of(),
            List.of(),
            0,
            PacketPhaseResult.noPackets(),
            HistorySettlement.NOT_REQUIRED
    );
    private static final int MAX_OPERATION_SCANS_PER_POLL = 128;
    private static final Object GLOBAL_LANE = new Object();

    private final Object planRegistrationLock = new Object();

    private final FoliaRegionDispatcher dispatcher;
    private final AtomicReference<FoliaBackpressure> backpressureView;
    private final DefaultFoliaBackpressure bindableBackpressure;
    private final OperationCompletionService completionService;
    private final RegionObserver regionObserver;
    private final CommitAction commitAction;
    private final SliceTuning sliceTuning;
    private final LongSupplier nanoTime;
    private final Runnable beforeScheduleRelease;
    private final NonBlockingHandoff handoff;
    private final Map<Object, Lane> lanes = new ConcurrentHashMap<>();
    private final Map<PlanIdentity, PlanState> plans = new ConcurrentHashMap<>();
    private final Map<ChunkIdentity, ConcurrentSkipListMap<Long, PlanState>> chunkPlans =
            new ConcurrentHashMap<>();
    private final Map<RegionKey, AtomicLong> regionalRebinds = new ConcurrentHashMap<>();
    private final AtomicInteger outstandingChunks = new AtomicInteger();
    private final AtomicLong packetMailboxBytes = new AtomicLong();
    private final AtomicLong rebindCount = new AtomicLong();
    private final AtomicLong planRegistrationSequence = new AtomicLong();

    /**
     * Compatibility constructor for diagnostics-only wiring. Plan registration and lane work require
     * the full constructor so live region identity and the completion fence cannot be guessed.
     */
    public FoliaCommitBroker(FoliaRegionDispatcher dispatcher, FoliaBackpressure backpressure) {
        this(
                dispatcher,
                backpressure,
                null,
                null,
                RegionObserver.unavailable(),
                CommitAction.unavailable(),
                SliceTuning.initial(),
                System::nanoTime,
                () -> {
                },
                ForkJoinPool.commonPool()::execute
        );
    }

    FoliaCommitBroker(
            FoliaRegionDispatcher dispatcher,
            DefaultFoliaBackpressure backpressure,
            OperationCompletionService completionService,
            RegionObserver regionObserver,
            CommitAction commitAction,
            SliceTuning sliceTuning,
            LongSupplier nanoTime
    ) {
        this(
                dispatcher,
                backpressure,
                completionService,
                regionObserver,
                commitAction,
                sliceTuning,
                nanoTime,
                () -> {
                },
                ForkJoinPool.commonPool()::execute
        );
    }

    FoliaCommitBroker(
            FoliaRegionDispatcher dispatcher,
            DefaultFoliaBackpressure backpressure,
            OperationCompletionService completionService,
            RegionObserver regionObserver,
            CommitAction commitAction,
            SliceTuning sliceTuning,
            LongSupplier nanoTime,
            Runnable beforeScheduleRelease
    ) {
        this(
                dispatcher,
                backpressure,
                completionService,
                regionObserver,
                commitAction,
                sliceTuning,
                nanoTime,
                beforeScheduleRelease,
                ForkJoinPool.commonPool()::execute
        );
    }

    FoliaCommitBroker(
            FoliaRegionDispatcher dispatcher,
            DefaultFoliaBackpressure backpressure,
            OperationCompletionService completionService,
            RegionObserver regionObserver,
            CommitAction commitAction,
            SliceTuning sliceTuning,
            LongSupplier nanoTime,
            Runnable beforeScheduleRelease,
            NonBlockingHandoff handoff
    ) {
        this(
                dispatcher,
                null,
                backpressure,
                completionService,
                regionObserver,
                commitAction,
                sliceTuning,
                nanoTime,
                beforeScheduleRelease,
                handoff
        );
    }

    private FoliaCommitBroker(
            FoliaRegionDispatcher dispatcher,
            FoliaBackpressure backpressure,
            DefaultFoliaBackpressure bindableBackpressure,
            OperationCompletionService completionService,
            RegionObserver regionObserver,
            CommitAction commitAction,
            SliceTuning sliceTuning,
            LongSupplier nanoTime,
            Runnable beforeScheduleRelease,
            NonBlockingHandoff handoff
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.backpressureView = new AtomicReference<>(backpressure);
        this.bindableBackpressure = bindableBackpressure;
        this.completionService = completionService;
        this.regionObserver = Objects.requireNonNull(regionObserver, "regionObserver");
        this.commitAction = Objects.requireNonNull(commitAction, "commitAction");
        this.sliceTuning = Objects.requireNonNull(sliceTuning, "sliceTuning").validated();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.beforeScheduleRelease = Objects.requireNonNull(beforeScheduleRelease, "beforeScheduleRelease");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
    }

    /**
     * Task-14 plan-producer preflight and registration seam. Empty means producer registration
     * closed before the plan was registered; no backpressure accounting has happened.
     */
    Optional<RegisteredPlan> registerPlan(
            DefaultOperationCompletion completion,
            long chunkKey,
            long planSequence,
            ChunkTarget target
    ) {
        requirePlanCollaborators();
        DefaultOperationCompletion checkedCompletion = Objects.requireNonNull(completion, "completion");
        ChunkTarget checkedTarget = Objects.requireNonNull(target, "target");
        UUID operationId = checkedCompletion.operationId();
        PlanIdentity identity = new PlanIdentity(operationId, chunkKey, planSequence);
        PlanState state = new PlanState(identity, checkedTarget);
        PlanProducerToken token = completionService.tryAcquirePlanProducer(
                operationId,
                chunkKey,
                planSequence,
                state::drainExpired,
                failure -> completionService.submit(() -> state.transitionFailed(failure))
        ).orElse(null);
        if (token == null) {
            return Optional.empty();
        }
        state.attachToken(token);
        synchronized (planRegistrationLock) {
            checkedCompletion.register(token);
            try {
                DefaultFoliaBackpressure.BoundAdmission boundAdmission = bindableBackpressure.bind(token);
                FoliaBackpressure admission = new PlanBackpressure(state, boundAdmission);
                state.attachAdmission(admission);
                backpressureView.compareAndSet(null, boundAdmission);
                state.publishRegistration();
                return Optional.of(new RegisteredPlan(state, admission));
            } catch (RuntimeException | Error failure) {
                token.rejectAdmission(failure);
                state.transitionFailed(failure);
                throw failure;
            }
        }
    }

    /**
     * Enqueues one admitted abstract commit unit. The action runs only after a fresh owner check,
     * successful permit transfer, and the READY -> SCHEDULED -> COMMITTING transitions.
     */
    void enqueue(
            RegisteredPlan registeredPlan,
            FoliaBackpressure.Permit permit,
            int costUnits,
            long packetBytes
    ) {
        RegisteredPlan checkedPlan = Objects.requireNonNull(registeredPlan, "registeredPlan");
        FoliaBackpressure.Permit checkedPermit = Objects.requireNonNull(permit, "permit");
        if (costUnits <= 0) {
            throw new IllegalArgumentException("costUnits must be positive");
        }
        if (packetBytes < 0) {
            throw new IllegalArgumentException("packetBytes must not be negative");
        }
        PlanState state = checkedPlan.state;
        state.accept(checkedPermit, costUnits, packetBytes);
        enqueueCommit(state, checkedPermit.region());
    }

    /** Re-enters a reserved-capacity continuation under the same plan identity and DRR policy. */
    void enqueueContinuation(
            RegisteredPlan registeredPlan,
            FoliaBackpressure.Permit continuationPermit,
            int costUnits,
            long packetBytes
    ) {
        RegisteredPlan checkedPlan = Objects.requireNonNull(registeredPlan, "registeredPlan");
        FoliaBackpressure.Permit checkedPermit = Objects.requireNonNull(continuationPermit, "continuationPermit");
        if (costUnits <= 0) {
            throw new IllegalArgumentException("costUnits must be positive");
        }
        if (packetBytes < 0) {
            throw new IllegalArgumentException("packetBytes must not be negative");
        }
        if (!checkedPermit.demand().previouslyAccepted()) {
            throw new IllegalArgumentException("Continuation permit must retain a previously accepted reservation");
        }
        PlanState plan = checkedPlan.state;
        ContinuationWork continuation = new ContinuationWork(plan, checkedPermit, costUnits);
        plan.attachContinuation(checkedPermit, packetBytes);
        enqueueCommitWork(continuation, checkedPermit.region());
    }

    /** Operation-level cancellation that abandons this plan, distinct from admission-attempt cancellation. */
    boolean cancelPlan(RegisteredPlan registeredPlan, Throwable cause) {
        Objects.requireNonNull(registeredPlan, "registeredPlan");
        return registeredPlan.state.cancelBeforeMutation(Objects.requireNonNull(cause, "cause"));
    }

    /**
     * Wave-2 updates this O(1) immutable drain snapshot after each applied-state transition. A
     * drain-expiry hook reads it once; it never builds a receipt or scans plan state.
     */
    void updateDrainTerminal(RegisteredPlan registeredPlan, ChunkTerminalRecord terminalRecord) {
        Objects.requireNonNull(registeredPlan, "registeredPlan").state.updateDrainTerminal(terminalRecord);
    }

    /**
     * Target-lane C6 seam for task 16. NORMAL always wins over queued WHEN_FREE work in the same
     * live region lane, and both share the lane's adaptive time slice with commit work.
     */
    <T> CompletionStage<T> scheduleSync(
            ChunkTarget target,
            SyncPriority priority,
            RegionCall<T> call
    ) {
        ChunkSyncRequest<T> request = new ChunkSyncRequest<>(
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(priority, "priority"),
                Objects.requireNonNull(call, "call")
        );
        discoverOrEnqueue(request);
        return request.result;
    }

    CompletionStage<Void> scheduleSyncTask(ChunkTarget target, SyncPriority priority, RegionTask task) {
        return scheduleSync(target, priority, ticket -> {
            Objects.requireNonNull(task, "task").run(ticket);
            return null;
        });
    }

    CompletionStage<Void> scheduleSync(EntityTarget target, SyncPriority priority, EntityTask task) {
        EntitySyncRequest request = new EntitySyncRequest(
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(priority, "priority"),
                Objects.requireNonNull(task, "task")
        );
        discoverOrEnqueue(request);
        return request.result;
    }

    CompletionStage<Void> scheduleSyncGlobal(SyncPriority priority, GlobalTask task) {
        GlobalSyncRequest request = new GlobalSyncRequest(
                Objects.requireNonNull(priority, "priority"),
                Objects.requireNonNull(task, "task")
        );
        enqueueSync(request, GLOBAL_LANE);
        return request.result;
    }

    /** Frozen G4 per-region producer. */
    DiagnosticSnapshot diagnostics(RegionKey region) {
        RegionKey checkedRegion = Objects.requireNonNull(region, "region");
        observeRegion(checkedRegion);
        FoliaBackpressure.Pressure pressure = pressure(checkedRegion);
        RegionalPlanSnapshot plans = regionalPlans(checkedRegion);
        return new DiagnosticSnapshot(
                Optional.of(checkedRegion),
                pressure.readyChunks(),
                pressure.readyBytes(),
                pressure.waiters(),
                pressure.finalizerChains(),
                plans.packetBytes(),
                pressure.scheduledDrains(),
                dispatcher.liveTickets(),
                dispatcher.outstandingFutures(),
                plans.outstandingChunks(),
                counter(regionalRebinds, checkedRegion),
                pressure.oldestReadyNanos()
        );
    }

    /** Frozen G4 global producer. */
    DiagnosticSnapshot diagnosticsGlobal() {
        long readyBytes = 0;
        long oldestReadyNanos = 0;
        int readyChunks = 0;
        int waiters = 0;
        int finalizerChains = 0;
        int scheduledDrains = 0;
        for (RegionKey region : observedRegions()) {
            FoliaBackpressure.Pressure pressure = pressure(region);
            readyChunks = saturatedIntAdd(readyChunks, pressure.readyChunks());
            readyBytes = saturatedLongAdd(readyBytes, pressure.readyBytes());
            waiters = saturatedIntAdd(waiters, pressure.waiters());
            finalizerChains = saturatedIntAdd(finalizerChains, pressure.finalizerChains());
            scheduledDrains = saturatedIntAdd(scheduledDrains, pressure.scheduledDrains());
            oldestReadyNanos = Math.max(oldestReadyNanos, pressure.oldestReadyNanos());
        }
        return new DiagnosticSnapshot(
                Optional.empty(),
                readyChunks,
                readyBytes,
                waiters,
                finalizerChains,
                packetMailboxBytes.get(),
                scheduledDrains,
                dispatcher.liveTickets(),
                dispatcher.outstandingFutures(),
                outstandingChunks.get(),
                rebindCount.get(),
                oldestReadyNanos
        );
    }

    /** Exact four-field G4 line. Extended telemetry is emitted separately by the caller. */
    String diagnosticLine(DiagnosticSnapshot snapshot) {
        DiagnosticSnapshot checked = Objects.requireNonNull(snapshot, "snapshot");
        String region = checked.region().map(RegionKey::toString).orElse("global");
        return "FAWE_QUEUE depth=" + checked.readyChunks()
                + " inflight=" + saturatedIntAdd(checked.scheduledDrains(), checked.finalizerChains())
                + " outstanding=" + checked.outstandingChunks()
                + " region=" + region;
    }

    /**
     * Task-17 construction seam for the task-13 rejection observer. The completion-service hop is
     * installed once here; the observer must perform bounded internal state work and no user code.
     */
    static DefaultFoliaBackpressure.AdmissionRejectionHandler rejectionHook(
            OperationCompletionService completionService,
            AdmissionRejectionObserver observer
    ) {
        OperationCompletionService checkedService = Objects.requireNonNull(completionService, "completionService");
        AdmissionRejectionObserver checkedObserver = Objects.requireNonNull(observer, "observer");
        return (region, demand, outcome, failure) -> {
            if (outcome != DefaultFoliaBackpressure.AdmissionFailureOutcome.REJECTED) {
                return;
            }
            checkedService.submit(() -> checkedObserver.rejected(region, demand, failure));
        };
    }

    private void discoverOrEnqueue(SyncRequest request) {
        Optional<RegionKey> hint = request.hint();
        if (hint.isPresent()) {
            enqueueSync(request, hint.orElseThrow());
            return;
        }
        request.discover().whenComplete((region, failure) -> {
            if (failure != null) {
                request.fail(unwrap(failure));
            } else {
                enqueueSync(request, region);
            }
        });
    }

    private void enqueueSync(SyncRequest request, Object laneKey) {
        if (request.result.isDone()) {
            return;
        }
        Lane lane;
        while (true) {
            lane = lane(laneKey);
            synchronized (lane) {
                if (lanes.get(laneKey) != lane) {
                    continue;
                }
                request.lane.set(lane);
                if (request.priority == SyncPriority.NORMAL) {
                    lane.normalSync.add(request);
                } else {
                    lane.whenFreeSync.add(request);
                }
                lane.depth.incrementAndGet();
                break;
            }
        }
        arm(lane, request);
    }

    private void enqueueCommit(PlanState plan, RegionKey region) {
        if (plan.terminal.get()) {
            plan.closePermit();
            return;
        }
        plan.moveRegion(region);
        enqueueCommitWork(plan, region);
    }

    private void enqueueCommitWork(CommitWork work, RegionKey region) {
        PlanState plan = work.plan();
        plan.moveRegion(region);
        Lane lane;
        while (true) {
            lane = lane(region);
            synchronized (lane) {
                if (lanes.get(region) != lane) {
                    continue;
                }
                plan.lane.set(lane);
                lane.addCommit(work);
                break;
            }
        }
        arm(lane, work);
    }

    private Lane lane(Object key) {
        return lanes.computeIfAbsent(key, ignored -> new Lane(key, new SliceController(sliceTuning)));
    }

    private void arm(Lane lane, DispatchAnchor preferredAnchor) {
        if (!lane.scheduled.compareAndSet(false, true)) {
            return;
        }
        DispatchAnchor anchor = preferredAnchor.isDispatchable() ? preferredAnchor : lane.anchor();
        if (anchor == null) {
            lane.scheduled.set(false);
            if (lane.hasWork()) {
                rearmIfNeeded(lane);
            }
            return;
        }
        long scheduledAt = nanoTime.getAsLong();
        lane.scheduledAtNanos.set(scheduledAt);
        CompletionStage<Void> dispatch;
        try {
            dispatch = anchor.dispatch(() -> drain(lane, anchor, scheduledAt));
        } catch (Throwable failure) {
            lane.scheduled.set(false);
            anchor.dispatchFailed(failure);
            rearmIfNeeded(lane);
            return;
        }
        dispatch.whenComplete((ignored, failure) -> {
            if (failure != null) {
                lane.scheduled.set(false);
                anchor.dispatchFailed(unwrap(failure));
                rearmIfNeeded(lane);
            }
        });
    }

    private void drain(Lane lane, DispatchAnchor anchor, long scheduledAt) {
        long started = nanoTime.getAsLong();
        Object actualKey;
        try {
            actualKey = anchor.currentLaneKey();
        } catch (Throwable failure) {
            lane.scheduled.set(false);
            anchor.dispatchFailed(failure);
            rearmIfNeeded(lane);
            return;
        }
        long scheduleDelay = Math.max(0, started - scheduledAt);
        if (actualKey instanceof RegionKey region) {
            recordScheduleDelay(region, scheduleDelay);
        }
        int processed = 0;
        try {
            while (lane.controller.mayBegin(processed, Math.max(0, nanoTime.getAsLong() - started))) {
                LaneWork work = lane.poll();
                if (work == null) {
                    break;
                }
                lane.depth.decrementAndGet();
                work.acceptDrain();
                if (!Objects.equals(lane.key, actualKey) || !work.ownedHere()) {
                    rebind(work, actualKey);
                } else {
                    work.execute();
                }
                processed++;
            }
        } finally {
            long runtime = Math.max(0, nanoTime.getAsLong() - started);
            boolean backlog = lane.hasWork();
            lane.controller.observe(backlog, scheduleDelay, runtime);
            if (actualKey instanceof RegionKey region && processed > 0) {
                recordSlice(region, processed, runtime);
            }
            beforeScheduleRelease.run();
            lane.scheduled.set(false);
            if (lane.hasWork()) {
                deferRearm(lane);
            } else {
                retireLane(lane);
            }
        }
    }

    private void deferRearm(Lane lane) {
        handoff.execute(() -> {
            try {
                rearmIfNeeded(lane);
            } catch (Throwable failure) {
                DispatchAnchor anchor = lane.anchor();
                if (anchor != null) {
                    anchor.dispatchFailed(failure);
                }
            }
        });
    }

    private void rebind(LaneWork work, Object actualKey) {
        if (work.ownedHere()) {
            if (work.transferTo(actualKey)) {
                enqueueRebound(work, actualKey);
            } else {
                deferRetry(work);
            }
            return;
        }
        work.discover().whenComplete((newKey, failure) -> {
            if (failure != null) {
                work.failBeforeMutation(unwrap(failure));
            } else if (work.transferTo(newKey)) {
                enqueueRebound(work, newKey);
            } else {
                deferRetry(work);
            }
        });
    }

    private void enqueueRebound(LaneWork work, Object actualKey) {
        handoff.execute(() -> {
            try {
                work.recordRebind(actualKey);
                if (work instanceof CommitWork commit) {
                    enqueueCommitWork(commit, (RegionKey) actualKey);
                } else {
                    enqueueSync((SyncRequest) work, actualKey);
                }
            } catch (Throwable failure) {
                work.failBeforeMutation(failure);
            }
        });
    }

    private void retryOrExpire(LaneWork work) {
        if (nanoTime.getAsLong() >= work.deadlineNanos()) {
            work.transferExpired();
        } else {
            work.discover().whenComplete((newKey, failure) -> {
                if (failure != null) {
                    work.failBeforeMutation(unwrap(failure));
                } else if (work.transferTo(newKey)) {
                    enqueueRebound(work, newKey);
                } else {
                    deferRetry(work);
                }
            });
        }
    }

    private void deferRetry(LaneWork work) {
        if (nanoTime.getAsLong() >= work.deadlineNanos()) {
            work.transferExpired();
            return;
        }
        handoff.execute(() -> {
            try {
                retryOrExpire(work);
            } catch (Throwable failure) {
                work.failBeforeMutation(failure);
            }
        });
    }

    private void rearmIfNeeded(Lane lane) {
        if (!lane.hasWork()) {
            retireLane(lane);
            return;
        }
        DispatchAnchor next = lane.anchor();
        if (next != null) {
            arm(lane, next);
        }
    }

    private void retireLane(Lane lane) {
        synchronized (lane) {
            if (!lane.scheduled.get() && !lane.hasWork()) {
                lanes.remove(lane.key, lane);
            }
        }
        if (lane.hasWork()) {
            arm(lane, Objects.requireNonNull(lane.anchor(), "nonempty lane anchor"));
        }
    }

    private boolean eligible(PlanState plan) {
        ConcurrentSkipListMap<Long, PlanState> sequence = chunkPlans.get(plan.chunkIdentity);
        Map.Entry<Long, PlanState> first = sequence == null ? null : sequence.firstEntry();
        return first != null && first.getValue() == plan;
    }

    private void removePlan(PlanState state) {
        plans.remove(state.identity, state);
        chunkPlans.computeIfPresent(state.chunkIdentity, (ignored, sequence) -> {
            sequence.remove(state.registrationOrder, state);
            return sequence.isEmpty() ? null : sequence;
        });
        state.packetBytes.set(0);
        packetMailboxBytes.addAndGet(-state.globalPacketBytes.getAndSet(0));
        outstandingChunks.decrementAndGet();
    }

    private void observeRegion(RegionKey region) {
        regionalRebinds.computeIfAbsent(region, ignored -> new AtomicLong());
    }

    private List<RegionKey> observedRegions() {
        List<RegionKey> regions = new ArrayList<>(regionalRebinds.keySet());
        for (PlanState plan : plans.values()) {
            RegionKey region = plan.region.get();
            if (region != null) {
                regions.add(region);
            }
        }
        for (Object key : lanes.keySet()) {
            if (key instanceof RegionKey region) {
                regions.add(region);
            }
        }
        return regions.stream().distinct().toList();
    }

    private RegionalPlanSnapshot regionalPlans(RegionKey region) {
        int outstanding = 0;
        long bytes = 0;
        for (PlanState plan : plans.values()) {
            if (region.equals(plan.region.get())) {
                outstanding = saturatedIntAdd(outstanding, 1);
                bytes = saturatedLongAdd(bytes, plan.packetBytes.get());
            }
        }
        return new RegionalPlanSnapshot(outstanding, bytes);
    }

    private void requirePlanCollaborators() {
        if (completionService == null || bindableBackpressure == null) {
            throw new IllegalStateException("Plan registration requires completion-service and bound backpressure wiring");
        }
    }

    private FoliaBackpressure.Pressure pressure(RegionKey region) {
        FoliaBackpressure view = backpressureView.get();
        return view == null
                ? new FoliaBackpressure.Pressure(0, 0, 0, 0, 0, 0, 0, 0)
                : view.pressure(region);
    }

    private void recordScheduleDelay(RegionKey region, long delayNanos) {
        FoliaBackpressure view = backpressureView.get();
        if (view != null) {
            view.recordScheduleDelay(region, delayNanos);
        }
    }

    private void recordSlice(RegionKey region, int chunks, long runtimeNanos) {
        FoliaBackpressure view = backpressureView.get();
        if (view != null) {
            view.recordSlice(region, chunks, runtimeNanos);
        }
    }

    private static int quantum(FoliaBackpressure.Priority priority) {
        return switch (priority) {
            case INTERACTIVE -> 4;
            case NORMAL -> 2;
            case BULK -> 1;
        };
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException completionFailure && completionFailure.getCause() != null) {
            return completionFailure.getCause();
        }
        return failure;
    }

    private static int saturatedIntAdd(int first, int second) {
        long result = (long) first + second;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static long saturatedLongAdd(long first, long second) {
        if (second > 0 && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private static long counter(Map<RegionKey, AtomicLong> counters, RegionKey region) {
        AtomicLong counter = counters.get(region);
        return counter == null ? 0 : counter.get();
    }

    private record RegionalPlanSnapshot(int outstandingChunks, long packetBytes) {
    }

    enum SyncPriority {
        NORMAL,
        WHEN_FREE
    }

    @FunctionalInterface
    interface CommitAction {

        /**
         * Runs one complete abstract owner commit unit. It returns either a continuation boundary
         * or the truthful phase-12 terminal record, and must not throw after its first mutation.
         */
        CommitOutcome commit(RegionTicket ticket, RegisteredPlan plan, boolean continuation);

        static CommitAction unavailable() {
            return (ticket, plan, continuation) -> {
                throw new IllegalStateException("Wave-2 commit action is not wired");
            };
        }

    }

    record CommitOutcome(Optional<ChunkTerminalRecord> terminal) {

        CommitOutcome {
            terminal = Objects.requireNonNull(terminal, "terminal");
        }

        static CommitOutcome continuePlan() {
            return new CommitOutcome(Optional.empty());
        }

        static CommitOutcome terminal(ChunkTerminalRecord record) {
            return new CommitOutcome(Optional.of(Objects.requireNonNull(record, "record")));
        }

    }

    interface RegionObserver {

        Optional<RegionKey> lastRegionHint(World world, int chunkX, int chunkZ);

        RegionKey currentRegion(World world);

        RegionKey currentRegion(EntityTarget target);

        boolean ownsChunk(World world, int chunkX, int chunkZ);

        boolean ownsEntity(EntityTarget target);

        static RegionObserver unavailable() {
            return new RegionObserver() {
                @Override
                public Optional<RegionKey> lastRegionHint(World world, int chunkX, int chunkZ) {
                    return Optional.empty();
                }

                @Override
                public RegionKey currentRegion(World world) {
                    throw new IllegalStateException("Live region observer is not wired");
                }

                @Override
                public RegionKey currentRegion(EntityTarget target) {
                    throw new IllegalStateException("Live entity region observer is not wired");
                }

                @Override
                public boolean ownsChunk(World world, int chunkX, int chunkZ) {
                    return false;
                }

                @Override
                public boolean ownsEntity(EntityTarget target) {
                    return false;
                }
            };
        }

    }

    /** Off-owner handoff whose {@link #execute(Runnable)} implementation must never wait. */
    @FunctionalInterface
    interface NonBlockingHandoff {

        void execute(Runnable command);

    }

    @FunctionalInterface
    interface AdmissionRejectionObserver {

        void rejected(RegionKey region, FoliaBackpressure.Demand demand, Throwable failure);

    }

    record SliceTuning(
            long initialTargetNanos,
            long hardStopNanos,
            int initialCap,
            int minimumCap,
            int maximumCap,
            int adjustmentWindow,
            int capGrowth,
            long targetGrowthNanos,
            long minimumTargetNanos,
            long maximumTargetNanos,
            long healthyScheduleDelayNanos,
            long overloadScheduleDelayNanos
    ) {

        static SliceTuning initial() {
            return new SliceTuning(
                    TimeUnit.MICROSECONDS.toNanos(750),
                    TimeUnit.MICROSECONDS.toNanos(1_500),
                    8,
                    1,
                    32,
                    32,
                    2,
                    TimeUnit.MICROSECONDS.toNanos(125),
                    TimeUnit.MICROSECONDS.toNanos(250),
                    TimeUnit.MICROSECONDS.toNanos(1_500),
                    TimeUnit.MILLISECONDS.toNanos(55),
                    TimeUnit.MILLISECONDS.toNanos(75)
            );
        }

        private SliceTuning validated() {
            if (initialTargetNanos <= 0 || hardStopNanos <= 0 || initialCap <= 0 || minimumCap <= 0
                    || maximumCap < initialCap || initialCap < minimumCap || adjustmentWindow <= 0
                    || capGrowth <= 0 || targetGrowthNanos <= 0 || minimumTargetNanos <= 0
                    || maximumTargetNanos < initialTargetNanos || healthyScheduleDelayNanos < 0
                    || overloadScheduleDelayNanos <= healthyScheduleDelayNanos) {
                throw new IllegalArgumentException("Invalid lane slice tuning");
            }
            return this;
        }

    }

    static final class SliceController {

        private final SliceTuning tuning;
        private final long[] runtimes;
        private int cap;
        private long targetNanos;
        private int samples;
        private int cursor;

        SliceController(SliceTuning tuning) {
            this.tuning = Objects.requireNonNull(tuning, "tuning").validated();
            this.runtimes = new long[tuning.adjustmentWindow()];
            this.cap = tuning.initialCap();
            this.targetNanos = tuning.initialTargetNanos();
        }

        boolean mayBegin(int completed, long elapsedNanos) {
            return completed < cap && (completed == 0 || elapsedNanos < targetNanos)
                    && elapsedNanos < tuning.hardStopNanos();
        }

        void observe(boolean backlog, long scheduleDelayNanos, long runtimeNanos) {
            runtimes[cursor] = runtimeNanos;
            cursor = (cursor + 1) % runtimes.length;
            samples++;
            if (scheduleDelayNanos > tuning.overloadScheduleDelayNanos()
                    || runtimeNanos > tuning.hardStopNanos()) {
                cap = Math.max(tuning.minimumCap(), cap / 2);
                targetNanos = Math.max(tuning.minimumTargetNanos(), targetNanos / 2);
                return;
            }
            if (samples % tuning.adjustmentWindow() != 0 || !backlog
                    || scheduleDelayNanos > tuning.healthyScheduleDelayNanos()) {
                return;
            }
            if (percentile95() < targetNanos) {
                cap = Math.min(tuning.maximumCap(), cap + tuning.capGrowth());
                targetNanos = Math.min(tuning.maximumTargetNanos(), targetNanos + tuning.targetGrowthNanos());
            }
        }

        int cap() {
            return cap;
        }

        long targetNanos() {
            return targetNanos;
        }

        private long percentile95() {
            int count = Math.min(samples, runtimes.length);
            long[] ordered = new long[count];
            System.arraycopy(runtimes, 0, ordered, 0, count);
            Arrays.sort(ordered);
            int index = Math.max(0, (int) Math.ceil(count * 0.95) - 1);
            return ordered[index];
        }

    }

    private final class PlanBackpressure implements DefaultFoliaBackpressure.BoundAdmission {

        private final PlanState plan;
        private final DefaultFoliaBackpressure.BoundAdmission delegate;

        private PlanBackpressure(PlanState plan, DefaultFoliaBackpressure.BoundAdmission delegate) {
            this.plan = plan;
            this.delegate = delegate;
        }

        @Override
        public Limits limits() {
            return delegate.limits();
        }

        @Override
        public CompletionStage<Permit> acquire(
                RegionKey region,
                Demand demand,
                CompletionStage<?> cancellationSignal
        ) {
            plan.observeAdmissionRegion(region);
            return observe(delegate.acquire(region, demand, cancellationSignal));
        }

        @Override
        public Optional<Permit> tryAcquire(RegionKey region, Demand demand) {
            plan.observeAdmissionRegion(region);
            Optional<Permit> granted = delegate.tryAcquire(region, demand);
            granted.ifPresent(plan::attachGrantedPermit);
            return granted;
        }

        @Override
        public CompletionStage<Permit> acquireContinuation(
                RegionKey region,
                Demand demand,
                CompletionStage<?> cancellationSignal
        ) {
            plan.observeAdmissionRegion(region);
            CompletionStage<Permit> observed = delegate.acquireContinuation(region, demand, cancellationSignal)
                    .thenApply(granted -> {
                        plan.trackContinuationPermit(granted);
                        return granted;
                    });
            observed.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    completionService.submit(() -> plan.admissionRejected(unwrap(failure)));
                }
            });
            return observed;
        }

        @Override
        public boolean transfer(Permit permit, RegionKey actualRegion) {
            return transferResult(permit, actualRegion) == DefaultFoliaBackpressure.TransferResult.TRANSFERRED;
        }

        @Override
        public DefaultFoliaBackpressure.TransferResult transferResult(Permit permit, RegionKey actualRegion) {
            DefaultFoliaBackpressure.TransferResult result = delegate.transferResult(permit, actualRegion);
            if (result == DefaultFoliaBackpressure.TransferResult.PERMIT_CLOSED) {
                plan.transferPermanentlyFailed.set(true);
            }
            return result;
        }

        @Override
        public Pressure pressure(RegionKey region) {
            return delegate.pressure(region);
        }

        @Override
        public void recordScheduleDelay(RegionKey region, long delayNanos) {
            delegate.recordScheduleDelay(region, delayNanos);
        }

        @Override
        public void recordSlice(RegionKey region, int chunks, long runtimeNanos) {
            delegate.recordSlice(region, chunks, runtimeNanos);
        }

        @Override
        public void stopAccepting(Throwable reason) {
            delegate.stopAccepting(reason);
        }

        private CompletionStage<Permit> observe(CompletionStage<Permit> stage) {
            CompletionStage<Permit> observed = stage.thenApply(granted -> {
                plan.attachGrantedPermit(granted);
                return granted;
            });
            observed.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    completionService.submit(() -> plan.admissionRejected(unwrap(failure)));
                }
            });
            return observed;
        }

    }

    final class RegisteredPlan {

        private final PlanState state;
        private final FoliaBackpressure admission;

        private RegisteredPlan(PlanState state, FoliaBackpressure admission) {
            this.state = state;
            this.admission = admission;
        }

        FoliaBackpressure admission() {
            return admission;
        }

        UUID operationId() {
            return state.identity.operationId();
        }

        long chunkKey() {
            return state.identity.chunkKey();
        }

        long planSequence() {
            return state.identity.planSequence();
        }

    }

    private interface DispatchAnchor {

        CompletionStage<Void> dispatch(Runnable drain);

        Object currentLaneKey();

        boolean isDispatchable();

        void dispatchFailed(Throwable failure);

    }

    private interface LaneWork extends DispatchAnchor {

        void acceptDrain();

        boolean ownedHere();

        CompletionStage<Object> discover();

        boolean transferTo(Object actualKey);

        void recordRebind(Object actualKey);

        void execute();

        void failBeforeMutation(Throwable failure);

        void transferExpired();

        long deadlineNanos();

    }

    private interface CommitWork extends LaneWork {

        PlanState plan();

        FoliaBackpressure.Permit permit();

        int costUnits();

        boolean continuation();

    }

    private final class PlanState implements CommitWork {

        private final PlanIdentity identity;
        private final ChunkIdentity chunkIdentity;
        private final ChunkTarget target;
        private final Object mutationBoundary = new Object();
        private final Object accountingBoundary = new Object();
        private final AtomicReference<PlanProducerToken> token = new AtomicReference<>();
        private final AtomicReference<FoliaBackpressure> admission = new AtomicReference<>();
        private final AtomicReference<ChunkTerminalRecord> drainTerminal;
        private final AtomicReference<FoliaBackpressure.Permit> permit = new AtomicReference<>();
        private final AtomicReference<FoliaBackpressure.Permit> continuationPermit = new AtomicReference<>();
        private final AtomicReference<RegionKey> region = new AtomicReference<>();
        private final AtomicReference<Lane> lane = new AtomicReference<>();
        private final AtomicBoolean registered = new AtomicBoolean();
        private final AtomicBoolean counted = new AtomicBoolean();
        private final AtomicBoolean accepted = new AtomicBoolean();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean transferPermanentlyFailed = new AtomicBoolean();
        private final AtomicLong packetBytes = new AtomicLong();
        private final AtomicLong globalPacketBytes = new AtomicLong();
        private volatile int costUnits;
        private volatile long registrationOrder;
        private boolean mutationStarted;

        private PlanState(PlanIdentity identity, ChunkTarget target) {
            this.identity = identity;
            this.target = target;
            this.chunkIdentity = new ChunkIdentity(target.world(), identity.chunkKey());
            this.drainTerminal = new AtomicReference<>(terminalRecord(
                    TerminalStatus.CANCELLED_BEFORE_MUTATION,
                    new CancellationException("Plan cancelled by completion-service drain expiry")
            ));
        }

        private void attachToken(PlanProducerToken planToken) {
            if (!token.compareAndSet(null, Objects.requireNonNull(planToken, "planToken"))) {
                throw new IllegalStateException("Plan token already attached: " + identity);
            }
        }

        private void attachAdmission(FoliaBackpressure boundAdmission) {
            if (!admission.compareAndSet(null, Objects.requireNonNull(boundAdmission, "boundAdmission"))) {
                throw new IllegalStateException("Plan admission view already attached: " + identity);
            }
        }

        private void publishRegistration() {
            long order = planRegistrationSequence.incrementAndGet();
            if (order <= 0) {
                throw new IllegalStateException("Broker plan-registration identity space exhausted");
            }
            registrationOrder = order;
            registered.set(true);
            PlanState previous = plans.putIfAbsent(identity, this);
            if (previous != null) {
                throw new IllegalStateException("Plan already registered in broker: " + identity);
            }
            chunkPlans.computeIfAbsent(chunkIdentity, ignored -> new ConcurrentSkipListMap<>())
                    .put(order, this);
            outstandingChunks.incrementAndGet();
            counted.set(true);
            if (terminal.get()) {
                cleanup();
            }
        }

        private void accept(FoliaBackpressure.Permit acceptedPermit, int acceptedCost, long acceptedPacketBytes) {
            if (!registered.get()) {
                throw new IllegalStateException("Plan is not registered: " + identity);
            }
            if (!identity.operationId().equals(acceptedPermit.demand().operationId())) {
                throw new IllegalArgumentException("Permit operation does not match plan: " + identity);
            }
            FoliaBackpressure.Permit attached = permit.get();
            if (attached != acceptedPermit && !permit.compareAndSet(null, acceptedPermit)) {
                throw new IllegalStateException("Plan received more than one initial permit: " + identity);
            }
            if (!accepted.compareAndSet(false, true)) {
                throw new IllegalStateException("Plan already accepted: " + identity);
            }
            costUnits = acceptedCost;
            if (!addMailboxBytes(acceptedPacketBytes)) {
                closePermit();
            }
        }

        private void moveRegion(RegionKey newRegion) {
            observeRegion(newRegion);
            region.set(newRegion);
        }

        private void observeAdmissionRegion(RegionKey observedRegion) {
            if (region.get() == null) {
                moveRegion(observedRegion);
            } else {
                observeRegion(observedRegion);
            }
        }

        private void attachGrantedPermit(FoliaBackpressure.Permit grantedPermit) {
            FoliaBackpressure.Permit current = permit.get();
            if (current != null && current != grantedPermit) {
                grantedPermit.close();
                throw new IllegalStateException("Plan received multiple initial admission permits: " + identity);
            }
            permit.compareAndSet(null, grantedPermit);
            if (terminal.get()) {
                finishPermit(grantedPermit);
            }
        }

        private void attachContinuation(FoliaBackpressure.Permit continuationPermit, long continuationPacketBytes) {
            if (terminal.get()) {
                continuationPermit.close();
                throw new IllegalStateException("Cannot enqueue a continuation for terminal plan " + identity);
            }
            if (this.continuationPermit.get() != continuationPermit) {
                trackContinuationPermit(continuationPermit);
            }
            if (!addMailboxBytes(continuationPacketBytes)) {
                finishPermit(continuationPermit);
                throw new IllegalStateException("Plan became terminal while attaching continuation " + identity);
            }
        }

        private void trackContinuationPermit(FoliaBackpressure.Permit trackedPermit) {
            FoliaBackpressure.Permit current = continuationPermit.get();
            if (current != trackedPermit && !continuationPermit.compareAndSet(null, trackedPermit)) {
                trackedPermit.close();
                throw new IllegalStateException("Plan already has an unsettled continuation permit: " + identity);
            }
            if (terminal.get()) {
                finishPermit(trackedPermit);
            }
        }

        private boolean addMailboxBytes(long additionalBytes) {
            synchronized (accountingBoundary) {
                if (terminal.get()) {
                    return false;
                }
                packetBytes.addAndGet(additionalBytes);
                globalPacketBytes.addAndGet(additionalBytes);
                packetMailboxBytes.addAndGet(additionalBytes);
                return true;
            }
        }

        private void updateDrainTerminal(ChunkTerminalRecord record) {
            validateTerminal(record);
            drainTerminal.set(record);
        }

        private void drainExpired(long deadlineNanos) {
            ChunkTerminalRecord snapshot = drainTerminal.get();
            if (claimTerminal(snapshot)) {
                closePermit();
            }
        }

        private void transitionFailed(Throwable failure) {
            if (terminal.compareAndSet(false, true)) {
                closePermit();
                cleanup();
            }
        }

        private boolean cancelBeforeMutation(Throwable cause) {
            synchronized (mutationBoundary) {
                return !mutationStarted
                        && claimTerminal(terminalRecord(TerminalStatus.CANCELLED_BEFORE_MUTATION, cause));
            }
        }

        private boolean beginMutation() {
            synchronized (mutationBoundary) {
                if (terminal.get()) {
                    return false;
                }
                mutationStarted = true;
                return true;
            }
        }

        private boolean claimTerminal(ChunkTerminalRecord record) {
            validateTerminal(record);
            if (terminal.get()) {
                return false;
            }
            PlanProducerToken currentToken = token.get();
            if (currentToken == null || !currentToken.terminal(record)) {
                return false;
            }
            if (terminal.compareAndSet(false, true)) {
                closePermit();
                cleanup();
            }
            return true;
        }

        private void cleanup() {
            if (counted.compareAndSet(true, false)) {
                synchronized (accountingBoundary) {
                    removePlan(this);
                }
            }
        }

        private void closePermit() {
            FoliaBackpressure.Permit initial = permit.getAndSet(null);
            if (initial != null) {
                initial.close();
            }
            FoliaBackpressure.Permit continuation = continuationPermit.getAndSet(null);
            if (continuation != null && continuation != initial) {
                continuation.close();
            }
        }

        private void finishPermit(FoliaBackpressure.Permit finishedPermit) {
            boolean claimed = permit.compareAndSet(finishedPermit, null);
            claimed |= continuationPermit.compareAndSet(finishedPermit, null);
            if (claimed) {
                finishedPermit.close();
            }
        }

        private void admissionRejected(Throwable failure) {
            if (failure instanceof CancellationException) {
                return;
            }
            PlanProducerToken currentToken = token.get();
            if (currentToken != null) {
                currentToken.rejectAdmission(failure);
            }
            if (terminal.compareAndSet(false, true)) {
                closePermit();
                cleanup();
            }
        }

        private ChunkTerminalRecord terminalRecord(TerminalStatus status, Throwable failure) {
            return new ChunkTerminalRecord(
                    identity.operationId(),
                    identity.chunkKey(),
                    identity.planSequence(),
                    status,
                    EMPTY_RECEIPT,
                    Optional.of(failure)
            );
        }

        private void validateTerminal(ChunkTerminalRecord record) {
            ChunkTerminalRecord checked = Objects.requireNonNull(record, "record");
            if (!identity.operationId().equals(checked.operationId())
                    || identity.chunkKey() != checked.chunkKey()
                    || identity.planSequence() != checked.planSequence()) {
                throw new IllegalArgumentException("Terminal record does not match plan " + identity);
            }
        }

        @Override
        public CompletionStage<Void> dispatch(Runnable drain) {
            return dispatcher.onRegion(
                    target.world(),
                    target.chunkX(),
                    target.chunkZ(),
                    FoliaRegionDispatcher.TaskKind.COMMIT,
                    (RegionTask) ticket -> drain.run()
            );
        }

        @Override
        public Object currentLaneKey() {
            return regionObserver.currentRegion(target.world());
        }

        @Override
        public boolean isDispatchable() {
            return !terminal.get();
        }

        @Override
        public void dispatchFailed(Throwable failure) {
            failBeforeMutation(failure);
        }

        @Override
        public boolean ownedHere() {
            return regionObserver.ownsChunk(target.world(), target.chunkX(), target.chunkZ());
        }

        @Override
        public void acceptDrain() {
            FoliaBackpressure.Permit currentPermit = permit.get();
            if (currentPermit != null && scheduled.compareAndSet(false, true)) {
                currentPermit.enter(FoliaBackpressure.Stage.SCHEDULED);
            }
        }

        @Override
        public CompletionStage<Object> discover() {
            CompletableFuture<Object> result = new CompletableFuture<>();
            dispatcher.onRegion(
                    target.world(),
                    target.chunkX(),
                    target.chunkZ(),
                    FoliaRegionDispatcher.TaskKind.COMMIT,
                    (RegionTask) ticket -> result.complete(regionObserver.currentRegion(target.world()))
            ).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(unwrap(failure));
                }
            });
            return result;
        }

        @Override
        public boolean transferTo(Object actualKey) {
            if (!(actualKey instanceof RegionKey actualRegion)) {
                return false;
            }
            FoliaBackpressure.Permit currentPermit = permit.get();
            return currentPermit != null && (currentPermit.region().equals(actualRegion)
                    || admission.get().transfer(currentPermit, actualRegion));
        }

        @Override
        public void recordRebind(Object actualKey) {
            RegionKey actualRegion = (RegionKey) actualKey;
            rebindCount.incrementAndGet();
            regionalRebinds.computeIfAbsent(actualRegion, ignored -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void execute() {
            if (terminal.get()) {
                closePermit();
                return;
            }
            FoliaBackpressure.Permit currentPermit = permit.get();
            if (currentPermit == null) {
                failBeforeMutation(new IllegalStateException("Plan has no attached permit: " + identity));
                return;
            }
            AtomicBoolean inlineWindow = new AtomicBoolean(true);
            AtomicBoolean invokedInline = new AtomicBoolean();
            try {
                if (nanoTime.getAsLong() >= deadlineNanos()) {
                    transferExpired();
                    return;
                }
                currentPermit.enter(FoliaBackpressure.Stage.COMMITTING);
                dispatcher.onRegion(
                        target.world(),
                        target.chunkX(),
                        target.chunkZ(),
                        FoliaRegionDispatcher.TaskKind.COMMIT,
                        ticket -> {
                            if (!inlineWindow.get()) {
                                throw new IllegalStateException("Owned-region commit dispatch was deferred");
                            }
                            if (!invokedInline.compareAndSet(false, true)) {
                                throw new IllegalStateException("Owned-region commit dispatch ran more than once");
                            }
                            if (terminal.get()) {
                                finishPermit(currentPermit);
                                return null;
                            }
                            if (!ownedHere() || nanoTime.getAsLong() >= deadlineNanos()) {
                                transferExpired();
                                return null;
                            }
                            if (!beginMutation()) {
                                finishPermit(currentPermit);
                                return null;
                            }
                            CommitOutcome outcome = commitAction.commit(ticket, new RegisteredPlan(
                                    this,
                                    admission.get()
                            ), false);
                            outcome.terminal().ifPresent(this::validateTerminal);
                            currentPermit.enter(FoliaBackpressure.Stage.FINALIZING);
                            outcome.terminal().ifPresent(this::claimTerminal);
                            finishPermit(currentPermit);
                            return null;
                        }
                ).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        failBeforeMutation(unwrap(failure));
                    }
                });
                inlineWindow.set(false);
                if (!invokedInline.get()) {
                    failBeforeMutation(new IllegalStateException("Owned-region commit dispatch was not inline"));
                }
            } catch (Throwable failure) {
                inlineWindow.set(false);
                failBeforeMutation(failure);
            }
        }

        @Override
        public void failBeforeMutation(Throwable failure) {
            claimTerminal(terminalRecord(TerminalStatus.FAILED_BEFORE_MUTATION, failure));
        }

        @Override
        public void transferExpired() {
            claimTerminal(terminalRecord(
                    TerminalStatus.CANCELLED_BEFORE_MUTATION,
                    new TimeoutException("Region permit transfer did not settle before the plan deadline")
            ));
        }

        @Override
        public long deadlineNanos() {
            FoliaBackpressure.Permit currentPermit = permit.get();
            return currentPermit == null || transferPermanentlyFailed.get()
                    ? Long.MIN_VALUE
                    : currentPermit.demand().deadlineNanos();
        }

        @Override
        public PlanState plan() {
            return this;
        }

        @Override
        public FoliaBackpressure.Permit permit() {
            return permit.get();
        }

        @Override
        public int costUnits() {
            return costUnits;
        }

        @Override
        public boolean continuation() {
            return false;
        }

    }

    private final class ContinuationWork implements CommitWork {

        private final PlanState plan;
        private final FoliaBackpressure.Permit permit;
        private final int costUnits;
        private final AtomicBoolean scheduled = new AtomicBoolean();

        private ContinuationWork(PlanState plan, FoliaBackpressure.Permit permit, int costUnits) {
            this.plan = plan;
            this.permit = permit;
            this.costUnits = costUnits;
        }

        @Override
        public CompletionStage<Void> dispatch(Runnable drain) {
            ChunkTarget target = plan.target;
            return dispatcher.onRegion(
                    target.world(),
                    target.chunkX(),
                    target.chunkZ(),
                    FoliaRegionDispatcher.TaskKind.FINALIZER,
                    (RegionTask) ticket -> drain.run()
            );
        }

        @Override
        public Object currentLaneKey() {
            return regionObserver.currentRegion(plan.target.world());
        }

        @Override
        public boolean isDispatchable() {
            return !plan.terminal.get();
        }

        @Override
        public void dispatchFailed(Throwable failure) {
            failBeforeMutation(failure);
        }

        @Override
        public void acceptDrain() {
            if (scheduled.compareAndSet(false, true)) {
                permit.enter(FoliaBackpressure.Stage.SCHEDULED);
            }
        }

        @Override
        public boolean ownedHere() {
            ChunkTarget target = plan.target;
            return regionObserver.ownsChunk(target.world(), target.chunkX(), target.chunkZ());
        }

        @Override
        public CompletionStage<Object> discover() {
            ChunkTarget target = plan.target;
            CompletableFuture<Object> result = new CompletableFuture<>();
            dispatcher.onRegion(
                    target.world(),
                    target.chunkX(),
                    target.chunkZ(),
                    FoliaRegionDispatcher.TaskKind.FINALIZER,
                    (RegionTask) ticket -> result.complete(regionObserver.currentRegion(target.world()))
            ).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(unwrap(failure));
                }
            });
            return result;
        }

        @Override
        public boolean transferTo(Object actualKey) {
            if (!(actualKey instanceof RegionKey actualRegion)) {
                return false;
            }
            FoliaBackpressure admission = plan.admission.get();
            return permit.region().equals(actualRegion) || admission.transfer(permit, actualRegion);
        }

        @Override
        public void recordRebind(Object actualKey) {
            plan.recordRebind(actualKey);
        }

        @Override
        public void execute() {
            if (plan.terminal.get()) {
                plan.finishPermit(permit);
                return;
            }
            ChunkTarget target = plan.target;
            AtomicBoolean inlineWindow = new AtomicBoolean(true);
            AtomicBoolean invokedInline = new AtomicBoolean();
            try {
                if (nanoTime.getAsLong() >= deadlineNanos()) {
                    transferExpired();
                    return;
                }
                permit.enter(FoliaBackpressure.Stage.COMMITTING);
                dispatcher.onRegion(
                        target.world(),
                        target.chunkX(),
                        target.chunkZ(),
                        FoliaRegionDispatcher.TaskKind.FINALIZER,
                        ticket -> {
                            if (!inlineWindow.get()) {
                                throw new IllegalStateException("Owned-region continuation dispatch was deferred");
                            }
                            if (!invokedInline.compareAndSet(false, true)) {
                                throw new IllegalStateException("Owned-region continuation dispatch ran more than once");
                            }
                            if (plan.terminal.get()) {
                                plan.finishPermit(permit);
                                return null;
                            }
                            if (!ownedHere() || nanoTime.getAsLong() >= deadlineNanos()) {
                                transferExpired();
                                return null;
                            }
                            if (!plan.beginMutation()) {
                                plan.finishPermit(permit);
                                return null;
                            }
                            CommitOutcome outcome = commitAction.commit(
                                    ticket,
                                    new RegisteredPlan(plan, plan.admission.get()),
                                    true
                            );
                            outcome.terminal().ifPresent(plan::validateTerminal);
                            permit.enter(FoliaBackpressure.Stage.FINALIZING);
                            outcome.terminal().ifPresent(plan::claimTerminal);
                            plan.finishPermit(permit);
                            return null;
                        }
                ).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        failBeforeMutation(unwrap(failure));
                    }
                });
                inlineWindow.set(false);
                if (!invokedInline.get()) {
                    failBeforeMutation(new IllegalStateException("Owned-region continuation dispatch was not inline"));
                }
            } catch (Throwable failure) {
                inlineWindow.set(false);
                failBeforeMutation(failure);
            }
        }

        @Override
        public void failBeforeMutation(Throwable failure) {
            plan.failBeforeMutation(failure);
            plan.finishPermit(permit);
        }

        @Override
        public void transferExpired() {
            plan.transferExpired();
            plan.finishPermit(permit);
        }

        @Override
        public long deadlineNanos() {
            return plan.transferPermanentlyFailed.get() ? Long.MIN_VALUE : permit.demand().deadlineNanos();
        }

        @Override
        public PlanState plan() {
            return plan;
        }

        @Override
        public FoliaBackpressure.Permit permit() {
            return permit;
        }

        @Override
        public int costUnits() {
            return costUnits;
        }

        @Override
        public boolean continuation() {
            return true;
        }

    }

    private abstract class SyncRequest implements LaneWork {

        private final SyncPriority priority;
        private final CompletableFuture<Void> execution = new CompletableFuture<>();
        private final CompletableFuture<?> result;
        private final AtomicReference<Lane> lane = new AtomicReference<>();

        private SyncRequest(SyncPriority priority, CompletableFuture<?> result) {
            this.priority = priority;
            this.result = result;
        }

        abstract Optional<RegionKey> hint();

        @Override
        public void acceptDrain() {
        }

        @Override
        public boolean isDispatchable() {
            return !result.isDone();
        }

        @Override
        public void dispatchFailed(Throwable failure) {
            fail(failure);
        }

        @Override
        public boolean transferTo(Object actualKey) {
            return actualKey == GLOBAL_LANE || actualKey instanceof RegionKey;
        }

        @Override
        public void recordRebind(Object actualKey) {
            if (actualKey instanceof RegionKey region) {
                rebindCount.incrementAndGet();
                regionalRebinds.computeIfAbsent(region, ignored -> new AtomicLong()).incrementAndGet();
            }
        }

        @Override
        public void failBeforeMutation(Throwable failure) {
            fail(failure);
        }

        @Override
        public void transferExpired() {
            fail(new TimeoutException("Target-lane discovery did not settle"));
        }

        @Override
        public long deadlineNanos() {
            return Long.MAX_VALUE;
        }

        void fail(Throwable failure) {
            execution.completeExceptionally(failure);
            completeFailure(failure);
        }

        abstract void completeFailure(Throwable failure);

    }

    private final class ChunkSyncRequest<T> extends SyncRequest {

        private final ChunkTarget target;
        private final RegionCall<T> call;
        private final CompletableFuture<T> result;

        private ChunkSyncRequest(ChunkTarget target, SyncPriority priority, RegionCall<T> call) {
            this(new CompletableFuture<>(), target, priority, call);
        }

        private ChunkSyncRequest(
                CompletableFuture<T> result,
                ChunkTarget target,
                SyncPriority priority,
                RegionCall<T> call
        ) {
            super(priority, result);
            this.result = result;
            this.target = target;
            this.call = call;
        }

        @Override
        Optional<RegionKey> hint() {
            return regionObserver.lastRegionHint(target.world(), target.chunkX(), target.chunkZ());
        }

        @Override
        public CompletionStage<Void> dispatch(Runnable drain) {
            return dispatcher.onRegion(
                    target.world(),
                    target.chunkX(),
                    target.chunkZ(),
                    FoliaRegionDispatcher.TaskKind.FINALIZER,
                    (RegionTask) ticket -> drain.run()
            );
        }

        @Override
        public Object currentLaneKey() {
            return regionObserver.currentRegion(target.world());
        }

        @Override
        public boolean ownedHere() {
            return regionObserver.ownsChunk(target.world(), target.chunkX(), target.chunkZ());
        }

        @Override
        public CompletionStage<Object> discover() {
            CompletableFuture<Object> discovery = new CompletableFuture<>();
            dispatcher.onRegion(
                    target.world(),
                    target.chunkX(),
                    target.chunkZ(),
                    FoliaRegionDispatcher.TaskKind.FINALIZER,
                    (RegionTask) ticket -> discovery.complete(regionObserver.currentRegion(target.world()))
            ).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    discovery.completeExceptionally(unwrap(failure));
                }
            });
            return discovery;
        }

        @Override
        public void execute() {
            AtomicBoolean inlineWindow = new AtomicBoolean(true);
            AtomicBoolean invokedInline = new AtomicBoolean();
            try {
                dispatcher.onRegion(
                        target.world(),
                        target.chunkX(),
                        target.chunkZ(),
                        FoliaRegionDispatcher.TaskKind.FINALIZER,
                        ticket -> {
                            if (!inlineWindow.get()) {
                                throw new IllegalStateException("Owned-region sync dispatch was deferred");
                            }
                            if (!invokedInline.compareAndSet(false, true)) {
                                throw new IllegalStateException("Owned-region sync dispatch ran more than once");
                            }
                            if (!ownedHere()) {
                                throw new IllegalStateException("Chunk ownership changed inside sync dispatch");
                            }
                            return call.call(ticket);
                        }
                ).whenComplete((value, failure) -> {
                    if (failure == null) {
                        result.complete(value);
                    } else {
                        result.completeExceptionally(unwrap(failure));
                    }
                });
                inlineWindow.set(false);
                if (!invokedInline.get()) {
                    fail(new IllegalStateException("Owned-region sync dispatch was not inline"));
                }
            } catch (Throwable failure) {
                inlineWindow.set(false);
                fail(failure);
            }
        }

        @Override
        void completeFailure(Throwable failure) {
            result.completeExceptionally(failure);
        }

    }

    private final class EntitySyncRequest extends SyncRequest {

        private final EntityTarget target;
        private final EntityTask task;
        private final CompletableFuture<Void> result;

        private EntitySyncRequest(EntityTarget target, SyncPriority priority, EntityTask task) {
            this(new CompletableFuture<>(), target, priority, task);
        }

        private EntitySyncRequest(
                CompletableFuture<Void> result,
                EntityTarget target,
                SyncPriority priority,
                EntityTask task
        ) {
            super(priority, result);
            this.result = result;
            this.target = target;
            this.task = task;
        }

        @Override
        Optional<RegionKey> hint() {
            return Optional.empty();
        }

        @Override
        public CompletionStage<Void> dispatch(Runnable drain) {
            return dispatcher.onEntity(target.entity(), FoliaRegionDispatcher.TaskKind.FINALIZER, ticket -> drain.run());
        }

        @Override
        public Object currentLaneKey() {
            return regionObserver.currentRegion(target);
        }

        @Override
        public boolean ownedHere() {
            return regionObserver.ownsEntity(target);
        }

        @Override
        public CompletionStage<Object> discover() {
            CompletableFuture<Object> discovery = new CompletableFuture<>();
            dispatcher.onEntity(
                    target.entity(),
                    FoliaRegionDispatcher.TaskKind.FINALIZER,
                    ticket -> discovery.complete(regionObserver.currentRegion(target))
            ).whenComplete((ignored, failure) -> {
                if (failure != null) {
                    discovery.completeExceptionally(unwrap(failure));
                }
            });
            return discovery;
        }

        @Override
        public void execute() {
            AtomicBoolean inlineWindow = new AtomicBoolean(true);
            AtomicBoolean invokedInline = new AtomicBoolean();
            try {
                dispatcher.onEntity(target.entity(), FoliaRegionDispatcher.TaskKind.FINALIZER, ticket -> {
                    if (!inlineWindow.get()) {
                        throw new IllegalStateException("Owned-entity sync dispatch was deferred");
                    }
                    if (!invokedInline.compareAndSet(false, true)) {
                        throw new IllegalStateException("Owned-entity sync dispatch ran more than once");
                    }
                    if (!ownedHere()) {
                        throw new IllegalStateException("Entity ownership changed inside sync dispatch");
                    }
                    task.run(ticket);
                }).whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        result.complete(null);
                    } else {
                        result.completeExceptionally(unwrap(failure));
                    }
                });
                inlineWindow.set(false);
                if (!invokedInline.get()) {
                    fail(new IllegalStateException("Owned-entity sync dispatch was not inline"));
                }
            } catch (Throwable failure) {
                inlineWindow.set(false);
                fail(failure);
            }
        }

        @Override
        void completeFailure(Throwable failure) {
            result.completeExceptionally(failure);
        }

    }

    private final class GlobalSyncRequest extends SyncRequest {

        private final GlobalTask task;
        private final CompletableFuture<Void> result;

        private GlobalSyncRequest(SyncPriority priority, GlobalTask task) {
            this(new CompletableFuture<>(), priority, task);
        }

        private GlobalSyncRequest(CompletableFuture<Void> result, SyncPriority priority, GlobalTask task) {
            super(priority, result);
            this.result = result;
            this.task = task;
        }

        @Override
        Optional<RegionKey> hint() {
            return Optional.empty();
        }

        @Override
        public CompletionStage<Void> dispatch(Runnable drain) {
            return dispatcher.onGlobal(FoliaRegionDispatcher.TaskKind.LEGACY_GLOBAL, drain::run);
        }

        @Override
        public Object currentLaneKey() {
            return GLOBAL_LANE;
        }

        @Override
        public boolean ownedHere() {
            return true;
        }

        @Override
        public CompletionStage<Object> discover() {
            return CompletableFuture.completedFuture(GLOBAL_LANE);
        }

        @Override
        public void execute() {
            try {
                task.run();
                result.complete(null);
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        @Override
        void completeFailure(Throwable failure) {
            result.completeExceptionally(failure);
        }

    }

    private final class Lane {

        private final Object key;
        private final SliceController controller;
        private final ConcurrentLinkedQueue<SyncRequest> normalSync = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<SyncRequest> whenFreeSync = new ConcurrentLinkedQueue<>();
        private final Map<UUID, OperationQueue> operationIndex = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<OperationQueue> operations = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicLong scheduledAtNanos = new AtomicLong();
        private final AtomicInteger depth = new AtomicInteger();
        private OperationQueue currentOperation;
        private int consecutive;
        private int consecutiveNormalSync;

        private Lane(Object key, SliceController controller) {
            this.key = key;
            this.controller = controller;
        }

        private void addCommit(CommitWork work) {
            PlanState plan = work.plan();
            OperationQueue operation = operationIndex.computeIfAbsent(
                    plan.identity.operationId(),
                    ignored -> new OperationQueue(plan.identity.operationId(), work.permit().demand().priority())
            );
            operation.items.add(work);
            depth.incrementAndGet();
            if (operation.inRotation.compareAndSet(false, true)) {
                operations.add(operation);
            }
        }

        private LaneWork poll() {
            if (consecutiveNormalSync < 4) {
                SyncRequest normal = normalSync.poll();
                if (normal != null) {
                    consecutiveNormalSync++;
                    return normal;
                }
            }
            CommitWork commit = pollCommit();
            if (commit != null) {
                consecutiveNormalSync = 0;
                return commit;
            }
            SyncRequest normal = normalSync.poll();
            if (normal != null) {
                consecutiveNormalSync = 1;
                return normal;
            }
            consecutiveNormalSync = 0;
            return whenFreeSync.poll();
        }

        private CommitWork pollCommit() {
            for (int scan = 0; scan < MAX_OPERATION_SCANS_PER_POLL; scan++) {
                OperationQueue operation = currentOperation;
                if (operation == null || consecutive >= 4) {
                    rotateCurrent();
                    operation = operations.poll();
                    if (operation == null) {
                        return null;
                    }
                    currentOperation = operation;
                    consecutive = 0;
                    operation.deficit += quantum(operation.priority);
                }
                CommitWork candidate = operation.items.peek();
                if (candidate == null) {
                    retireCurrent();
                    continue;
                }
                if (candidate.plan().terminal.get()) {
                    operation.items.poll();
                    depth.decrementAndGet();
                    continue;
                }
                if (candidate.costUnits() > operation.deficit || !eligible(candidate.plan())) {
                    rotateCurrent();
                    continue;
                }
                if (operation.items.poll() != candidate) {
                    continue;
                }
                operation.deficit -= candidate.costUnits();
                consecutive++;
                if (operation.items.isEmpty()) {
                    retireCurrent();
                }
                return candidate;
            }
            rotateCurrent();
            return null;
        }

        private void rotateCurrent() {
            OperationQueue operation = currentOperation;
            if (operation == null) {
                return;
            }
            currentOperation = null;
            consecutive = 0;
            if (operation.items.isEmpty()) {
                releaseRotation(operation);
            } else {
                operations.add(operation);
            }
        }

        private void retireCurrent() {
            OperationQueue operation = currentOperation;
            currentOperation = null;
            consecutive = 0;
            if (operation != null) {
                releaseRotation(operation);
            }
        }

        private void releaseRotation(OperationQueue operation) {
            operation.inRotation.set(false);
            if (operation.items.isEmpty()) {
                operationIndex.remove(operation.operationId, operation);
            } else if (operation.inRotation.compareAndSet(false, true)) {
                operations.add(operation);
            }
        }

        private DispatchAnchor anchor() {
            SyncRequest normal = normalSync.peek();
            if (normal != null) {
                return normal;
            }
            OperationQueue operation = currentOperation == null ? operations.peek() : currentOperation;
            if (operation != null) {
                CommitWork work = operation.items.peek();
                if (work != null) {
                    return work;
                }
            }
            return whenFreeSync.peek();
        }

        private boolean hasWork() {
            return depth.get() > 0;
        }

    }

    private static final class OperationQueue {

        private final UUID operationId;
        private final FoliaBackpressure.Priority priority;
        private final ConcurrentLinkedQueue<CommitWork> items = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean inRotation = new AtomicBoolean();
        private int deficit;

        private OperationQueue(UUID operationId, FoliaBackpressure.Priority priority) {
            this.operationId = operationId;
            this.priority = priority;
        }

    }

    private record PlanIdentity(UUID operationId, long chunkKey, long planSequence) {

        private PlanIdentity {
            Objects.requireNonNull(operationId, "operationId");
            if (planSequence <= 0) {
                throw new IllegalArgumentException("planSequence must be positive");
            }
        }

    }

    private record ChunkIdentity(World world, long chunkKey) {

        private ChunkIdentity {
            Objects.requireNonNull(world, "world");
        }

    }

}

/** Per-region or global (region == null) diagnostic snapshot. Frozen G4 producer. */
record DiagnosticSnapshot(
        Optional<RegionKey> region,
        int readyChunks,
        long readyBytes,
        int waiters,
        int finalizerChains,
        long packetMailboxBytes,
        int scheduledDrains,
        int liveTickets,
        int outstandingFutures,
        int outstandingChunks,
        long rebindCount,
        long oldestReadyNanos
) {

    DiagnosticSnapshot {
        region = Objects.requireNonNull(region, "region");
    }

}
