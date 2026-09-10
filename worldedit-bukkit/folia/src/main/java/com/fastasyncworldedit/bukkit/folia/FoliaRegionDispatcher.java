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

    /** Tickets currently live inside dispatcher callbacks. */
    int liveTickets();

    /** Accepted dispatcher stages whose completion has not settled. */
    int outstandingFutures();

    record DrainReport(int unresolvedTasks, int liveTickets, List<String> unresolvedLabels) {
    }

}
