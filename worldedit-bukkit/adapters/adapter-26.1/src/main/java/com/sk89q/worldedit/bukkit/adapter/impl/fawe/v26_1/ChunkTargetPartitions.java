package com.sk89q.worldedit.bukkit.adapter.impl.fawe.v26_1;

import com.fastasyncworldedit.core.math.IntPair;
import com.fastasyncworldedit.core.queue.implementation.QueueHandlerRouting;
import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.sk89q.worldedit.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Package-private construction seam that makes one-dispatch-per-target structural. */
final class ChunkTargetPartitions {

    private ChunkTargetPartitions() {
    }

    static <T> Map<IntPair, List<T>> partition(
            Collection<T> values,
            Function<T, IntPair> targetOf,
            Set<IntPair> additionalTargets
    ) {
        Map<IntPair, List<T>> partitions = new HashMap<>();
        for (T value : values) {
            IntPair target = targetOf.apply(value);
            partitions.computeIfAbsent(target, ignored -> new ArrayList<>()).add(value);
        }
        for (IntPair target : additionalTargets) {
            partitions.computeIfAbsent(target, ignored -> new ArrayList<>());
        }
        partitions.replaceAll((target, targetValues) -> List.copyOf(targetValues));
        return Map.copyOf(partitions);
    }

    static <T> List<CompletionStage<Void>> dispatchEach(
            World world,
            Map<IntPair, List<T>> partitions,
            PartitionTask<T> task
    ) {
        List<CompletionStage<Void>> dispatches = new ArrayList<>(partitions.size());
        for (Map.Entry<IntPair, List<T>> partition : partitions.entrySet()) {
            IntPair target = partition.getKey();
            List<T> values = partition.getValue();
            dispatches.add(QueueHandlerRouting.syncOn(
                    new ChunkTarget(world, target.x(), target.z()),
                    ticket -> {
                        ticket.assertOwns(target.x(), target.z());
                        task.run(target, values);
                    }
            ));
        }
        return dispatches;
    }

    @FunctionalInterface
    interface PartitionTask<T> {

        void run(IntPair target, List<T> values);

    }

}
