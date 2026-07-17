package com.fastasyncworldedit.bukkit.folia;

import com.fastasyncworldedit.core.util.task.EntityTask;
import com.fastasyncworldedit.core.util.task.GlobalTask;
import com.fastasyncworldedit.core.util.task.RegionCall;
import com.fastasyncworldedit.core.util.task.RegionTask;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * The ONE class that talks to Folia schedulers. Every region hop, ticket mint/retire,
 * and instrumentation label lives here (C1).
 */
public interface FoliaRegionDispatcher {

    /** Phase/instrumentation label for §8 timers (F10). Not an authorization. */
    enum TaskKind {
        SNAPSHOT_CAPTURE,
        COMMIT,
        FINALIZER,
        PACKET,
        LEGACY_GLOBAL,
        ASYNC
    }

    /**
     * Run on the region owning (world,cx,cz); the task receives a fresh RegionTicket,
     * retired in this dispatcher's finally block. Inline when the caller owns the target.
     */
    CompletionStage<Void> onRegion(World world, int cx, int cz, TaskKind kind, RegionTask task);

    <T> CompletionStage<T> onRegion(World world, int cx, int cz, TaskKind kind, RegionCall<T> call);

    /**
     * Run on the entity's owning context; the task receives a fresh EntityTicket.
     * Entity retirement completes the stage exceptionally. Inline when the caller owns it.
     */
    CompletionStage<Void> onEntity(Entity entity, TaskKind kind, EntityTask task);

    /** Global-region thread (config, cross-cutting lifecycle only). No ticket: owns no chunk. */
    CompletionStage<Void> onGlobal(TaskKind kind, GlobalTask task);

    /** Lifecycle (F10): stop new submission; already-running callbacks may finish. */
    void stopAccepting(Throwable reason);

    /**
     * Bounded drain: completes when in-flight callbacks settle or the deadline expires,
     * reporting unresolved tickets/tasks. Never awaited from a tick thread.
     */
    CompletionStage<DrainReport> drain(Duration deadline);

    record DrainReport(int unresolvedTasks, int liveTickets, List<String> unresolvedLabels) {
    }

}
