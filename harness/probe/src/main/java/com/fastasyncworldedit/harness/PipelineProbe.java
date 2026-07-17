package com.fastasyncworldedit.harness;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundChunkBatchFinishedPacket;
import net.minecraft.network.protocol.game.ClientboundChunkBatchStartPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** The NMS-facing detached-prepare to owner-region-commit experiment. */
final class PipelineProbe {

    private static final int SECTION_Y = 5;
    private static final int SECTION_MIN_BLOCK_Y = SECTION_Y << 4;
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    private static final Target SAFETY_TARGET = new Target(0, 0, 0);
    private static final List<Target> TOURNAMENT_TARGETS = tournamentTargets();
    private static final List<Target> PERF_ANCHORS = List.of(
            new Target(0, 32, 32),
            new Target(1, 160, 32),
            new Target(2, 32, 160),
            new Target(3, 160, 160)
    );

    private final FoliaHarnessProbe plugin;
    private final ExecutorService workers;
    private volatile QueueSnapshot lastQueue = QueueSnapshot.empty("none");

    PipelineProbe(FoliaHarnessProbe plugin, ExecutorService workers) {
        this.plugin = plugin;
        this.workers = workers;
    }

    void runSafety(World world) {
        AtomicInteger regionTicks = new AtomicInteger();
        AtomicLong lastTickNanos = new AtomicLong();
        AtomicLong maxTickIntervalNanos = new AtomicLong();
        ScheduledTask heartbeat = Bukkit.getRegionScheduler().runAtFixedRate(
                plugin,
                world,
                SAFETY_TARGET.chunkX,
                SAFETY_TARGET.chunkZ,
                task -> {
                    long now = System.nanoTime();
                    long previous = lastTickNanos.getAndSet(now);
                    if (previous != 0) {
                        maxTickIntervalNanos.accumulateAndGet(now - previous, Math::max);
                    }
                    regionTicks.incrementAndGet();
                },
                1,
                1
        );

        long started = System.nanoTime();
        prepare(world, SAFETY_TARGET, Blocks.EMERALD_BLOCK.defaultBlockState(), regionTicks, true)
                .whenComplete((prepared, throwable) -> {
                    if (throwable != null) {
                        heartbeat.cancel();
                        plugin.fail("safety-prepare", unwrap(throwable));
                        return;
                    }
                    Bukkit.getRegionScheduler().execute(
                            plugin,
                            world,
                            SAFETY_TARGET.chunkX,
                            SAFETY_TARGET.chunkZ,
                            () -> {
                                try {
                                    CommitResult result = commitAndVerify(world, prepared);
                                    heartbeat.cancel();
                                    long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
                                    plugin.getLogger().info("FAWE_HARNESS_PIPELINE_SAFETY_OK"
                                            + " prepare_thread=" + prepared.prepareThread
                                            + " commit_thread=" + FoliaHarnessProbe.threadName()
                                            + " owner=" + result.owner
                                            + " visible_blocks=" + result.matches
                                            + " expected=" + BLOCKS_PER_SECTION
                                            + " ticks_during_prepare=" + prepared.ticksDuringPrepare
                                            + " max_tick_interval_us="
                                            + TimeUnit.NANOSECONDS.toMicros(maxTickIntervalNanos.get())
                                            + " elapsed_us=" + elapsedMicros
                                            + " live_refs_on_worker=false"
                                            + " save_marker=emerald_section_0_5_0");
                                } catch (Throwable commitFailure) {
                                    heartbeat.cancel();
                                    plugin.fail("safety-commit", commitFailure);
                                }
                            }
                    );
                });
    }

    void verifyReadback(World world) {
        Bukkit.getRegionScheduler().execute(plugin, world, SAFETY_TARGET.chunkX, SAFETY_TARGET.chunkZ, () -> {
            try {
                ServerLevel level = ((CraftWorld) world).getHandle();
                world.getChunkAt(SAFETY_TARGET.chunkX, SAFETY_TARGET.chunkZ);
                LevelChunk chunk = requireLoadedChunk(level, SAFETY_TARGET);
                int matches = countSection(chunk, SAFETY_TARGET, Blocks.EMERALD_BLOCK.defaultBlockState());
                if (matches != BLOCKS_PER_SECTION) {
                    throw new IllegalStateException("readback mismatch: " + matches + "/" + BLOCKS_PER_SECTION);
                }
                plugin.getLogger().info("FAWE_HARNESS_PIPELINE_READBACK_OK owner="
                        + Bukkit.isOwnedByCurrentRegion(world, SAFETY_TARGET.chunkX, SAFETY_TARGET.chunkZ)
                        + " blocks=" + matches
                        + " expected=" + BLOCKS_PER_SECTION
                        + " material=EMERALD_BLOCK"
                        + " thread=" + FoliaHarnessProbe.threadName());
            } catch (Throwable throwable) {
                plugin.fail("readback", throwable);
            }
        });
    }

    void runTournament(World world, Strategy strategy, int requestedChunks) {
        runTournament(world, strategy, requestedChunks, null);
    }

    void runPerf(World world, String op, Strategy strategy, int requestedChunks) {
        if (!op.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid performance operation id: " + op);
        }
        runTournament(world, strategy, requestedChunks, op);
    }

    void logQueue(String requestedOp) {
        QueueSnapshot snapshot = lastQueue;
        plugin.getLogger().info("FAWE_PROBE_QUEUE"
                + " op=" + requestedOp
                + " source_op=" + snapshot.op
                + " depth=" + snapshot.depth
                + " inflight=" + snapshot.inflight
                + " outstanding=" + snapshot.outstanding
                + " depth_highwater=" + snapshot.depthHighWater
                + " inflight_highwater=" + snapshot.inflightHighWater
                + " source=probe");
    }

    void sampleMark(World world, String label) {
        if (!label.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("invalid performance mark: " + label);
        }
        for (Target anchor : PERF_ANCHORS) {
            Bukkit.getRegionScheduler().execute(plugin, world, anchor.chunkX, anchor.chunkZ, () -> {
                try {
                    long now = System.nanoTime();
                    var currentRegion = TickRegionScheduler.getCurrentRegion();
                    var report = currentRegion.getData().getRegionSchedulingHandle().getTickReport5s(now);
                    long tickAverageNanos = -1;
                    long tickMaxNanos = -1;
                    if (report != null && report.timePerTickData() != null) {
                        var all = report.timePerTickData().segmentAll();
                        tickAverageNanos = Math.round(all.average());
                        tickMaxNanos = Math.round(all.greatest());
                    }
                    plugin.getLogger().info("FAWE_PROBE_REGION_SAMPLE"
                            + " label=" + label
                            + " group=" + anchor.regionGroup
                            + " region_id=" + currentRegion.id
                            + " owner=" + Bukkit.isOwnedByCurrentRegion(world, anchor.chunkX, anchor.chunkZ)
                            + " tps_5s=" + FoliaHarnessProbe.doubles(
                                    Bukkit.getRegionTPS(world, anchor.chunkX, anchor.chunkZ))
                            + " tick_avg_ns=" + tickAverageNanos
                            + " tick_max_ns=" + tickMaxNanos
                            + " source=rolling_5s"
                            + " thread=" + FoliaHarnessProbe.threadName());
                } catch (Throwable throwable) {
                    plugin.fail("perf-mark-" + label + "-group-" + anchor.regionGroup, throwable);
                }
            });
        }
    }

    private void runTournament(World world, Strategy strategy, int requestedChunks, String perfOp) {
        if (requestedChunks < 4 || requestedChunks > TOURNAMENT_TARGETS.size()) {
            throw new IllegalArgumentException("chunk count must be 4.." + TOURNAMENT_TARGETS.size());
        }
        List<Target> targets = List.copyOf(TOURNAMENT_TARGETS.subList(0, requestedChunks));
        long distinctGroups = targets.stream().map(Target::regionGroup).distinct().count();
        if (distinctGroups < 4) {
            throw new IllegalArgumentException("chunk count must cover all four region groups");
        }

        BlockState fill = switch (strategy) {
            case PER_CHUNK -> Blocks.GOLD_BLOCK.defaultBlockState();
            case BATCHED_NEIGHBOR -> Blocks.DIAMOND_BLOCK.defaultBlockState();
            case REGION_SWEEP -> Blocks.IRON_BLOCK.defaultBlockState();
        };

        List<RegionHeartbeat> heartbeats = startHeartbeats(world, targets);
        List<CompletableFuture<PreparedChunk>> futures = new ArrayList<>(targets.size());
        for (Target target : targets) {
            futures.add(prepare(world, target, fill, new AtomicInteger(), false));
        }

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                stopHeartbeats(heartbeats);
                plugin.fail("tournament-prepare-" + strategy.id, unwrap(throwable));
                return;
            }

            List<PreparedChunk> prepared = futures.stream().map(CompletableFuture::join).toList();
            Target kickoff = prepared.getFirst().target;
            // Ten clean region ticks give every heartbeat a real pre-commit baseline.
            Bukkit.getRegionScheduler().runDelayed(
                    plugin,
                    world,
                    kickoff.chunkX,
                    kickoff.chunkZ,
                    task -> dispatchTournament(world, strategy, prepared, heartbeats, distinctGroups, perfOp),
                    10
            );
        });
    }

    private void dispatchTournament(
            World world,
            Strategy strategy,
            List<PreparedChunk> prepared,
            List<RegionHeartbeat> heartbeats,
            long distinctGroups,
            String perfOp
    ) {
        long commitStart = System.nanoTime();
        heartbeats.forEach(heartbeat -> heartbeat.commitStartNanos.set(commitStart));
        QueueMeter queueMeter = new QueueMeter();
        AtomicInteger remaining = new AtomicInteger(prepared.size());
        AtomicInteger ownerMismatches = new AtomicInteger();
        AtomicInteger visibleBlocks = new AtomicInteger();

        Consumer<Collection<PreparedChunk>> scheduleGroup = group -> {
            Target anchor = group.iterator().next().target;
            queueMeter.schedule(world, anchor, () -> {
                long taskStarted = System.nanoTime();
                try {
                    for (PreparedChunk item : group) {
                        if (!Bukkit.isOwnedByCurrentRegion(world, item.target.chunkX, item.target.chunkZ)) {
                            ownerMismatches.incrementAndGet();
                            throw new IllegalStateException("group task does not own " + item.target);
                        }
                        CommitResult result = commitAndVerify(world, item);
                        visibleBlocks.addAndGet(result.matches);
                    }
                    queueMeter.maxTaskRunNanos.accumulateAndGet(System.nanoTime() - taskStarted, Math::max);
                    if (remaining.addAndGet(-group.size()) == 0) {
                        long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - commitStart);
                        // Let five post-commit ticks land so Folia's rolling tick report includes
                        // the commit, while preserving the already-captured visibility wall time.
                        Bukkit.getRegionScheduler().runDelayed(
                                plugin,
                                world,
                                anchor.chunkX,
                                anchor.chunkZ,
                                finalTask -> {
                                    stopHeartbeats(heartbeats);
                                    String heartbeatMetrics = heartbeats.stream()
                                            .map(RegionHeartbeat::summary)
                                            .collect(java.util.stream.Collectors.joining(";"));
                                    long observedRegions = heartbeats.stream()
                                            .mapToLong(heartbeat -> heartbeat.regionId)
                                            .filter(id -> id >= 0)
                                            .distinct()
                                            .count();
                                    if (perfOp != null) {
                                        lastQueue = new QueueSnapshot(
                                                perfOp,
                                                queueMeter.queued.get(),
                                                queueMeter.inflight.get(),
                                                remaining.get(),
                                                queueMeter.highWater.get(),
                                                queueMeter.inflightHighWater.get()
                                        );
                                        for (RegionHeartbeat heartbeat : heartbeats) {
                                            plugin.getLogger().info("FAWE_PROBE_REGION"
                                                    + " op=" + perfOp
                                                    + " " + heartbeat.perfSummary()
                                                    + " source=probe");
                                        }
                                        plugin.getLogger().info("FAWE_PROBE_COMMIT"
                                                + " op=" + perfOp
                                                + " strategy=" + strategy.id
                                                + " tasks=" + queueMeter.submitted.get()
                                                + " max_schedule_delay_us="
                                                + TimeUnit.NANOSECONDS.toMicros(
                                                        queueMeter.maxScheduleDelayNanos.get())
                                                + " max_commit_task_us="
                                                + TimeUnit.NANOSECONDS.toMicros(
                                                        queueMeter.maxTaskRunNanos.get())
                                                + " visibility_us=" + elapsedMicros
                                                + " source=probe");
                                        plugin.getLogger().info("FAWE_PROBE_PERF"
                                                + " op=" + perfOp
                                                + " strategy=" + strategy.id
                                                + " elapsed_ms=" + String.format(
                                                        Locale.ROOT, "%.3f", elapsedMicros / 1000.0)
                                                + " blocks=" + visibleBlocks.get()
                                                + " regions=" + observedRegions
                                                + " owner_mismatches=" + ownerMismatches.get()
                                                + " result=" + (ownerMismatches.get() == 0 ? "ok" : "fail")
                                                + " source=probe");
                                    }
                                    plugin.getLogger().info("FAWE_HARNESS_TOURNAMENT_OK"
                                            + " strategy=" + strategy.id
                                            + " chunks=" + prepared.size()
                                            + " target_groups=" + distinctGroups
                                            + " observed_regions=" + observedRegions
                                            + " commit_tasks=" + queueMeter.submitted.get()
                                            + " probe_queue_highwater=" + queueMeter.highWater.get()
                                            + " max_schedule_delay_us="
                                            + TimeUnit.NANOSECONDS.toMicros(
                                                    queueMeter.maxScheduleDelayNanos.get())
                                            + " max_commit_task_us="
                                            + TimeUnit.NANOSECONDS.toMicros(queueMeter.maxTaskRunNanos.get())
                                            + " visibility_us=" + elapsedMicros
                                            + " visible_blocks=" + visibleBlocks.get()
                                            + " expected_blocks=" + (prepared.size() * BLOCKS_PER_SECTION)
                                            + " owner_mismatches=" + ownerMismatches.get()
                                            + " region_ticks=" + heartbeatMetrics);
                                },
                                5
                        );
                    }
                } catch (Throwable commitFailure) {
                    stopHeartbeats(heartbeats);
                    plugin.fail("tournament-commit-" + strategy.id, commitFailure);
                }
            });
        };

        switch (strategy) {
            case PER_CHUNK -> prepared.forEach(item -> scheduleGroup.accept(List.of(item)));
            case BATCHED_NEIGHBOR -> groups(prepared, 2).forEach(scheduleGroup);
            case REGION_SWEEP -> groups(prepared, 4).forEach(scheduleGroup);
        }
    }

    void runPacketHazard(World world, String playerName, int chunkX, int chunkZ) {
        Player player = Bukkit.getPlayerExact(playerName);
        if (player == null) {
            throw new IllegalArgumentException("online player not found: " + playerName);
        }
        Target target = new Target(-1, chunkX, chunkZ);

        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            try {
                world.getChunkAt(chunkX, chunkZ);
                ServerLevel level = ((CraftWorld) world).getHandle();
                LevelChunk chunk = requireLoadedChunk(level, target);
                boolean playerOwnedByPacketThread = Bukkit.isOwnedByCurrentRegion(player);
                boolean trackedViewer = world.getPlayersSeeingChunk(chunkX, chunkZ).contains(player);
                ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                        chunk,
                        level.getChunkSource().getLightEngine(),
                        null,
                        null
                );
                ServerPlayer handle = ((CraftPlayer) player).getHandle();
                handle.connection.send(ClientboundChunkBatchStartPacket.INSTANCE);
                handle.connection.send(packet);
                handle.connection.send(new ClientboundChunkBatchFinishedPacket(1));

                boolean ackScheduled = player.getScheduler().execute(
                        plugin,
                        () -> player.sendMessage("FAWE_PACKET_DELIVERED chunk=" + chunkX + "," + chunkZ),
                        () -> plugin.getLogger().warning(
                                "FAWE_HARNESS_PACKET_RETIRED player=" + playerName),
                        1
                );
                plugin.getLogger().info("FAWE_HARNESS_PACKET_OK player=" + playerName
                        + " packet_chunk=" + chunkX + "," + chunkZ
                        + " cross_owner=" + !playerOwnedByPacketThread
                        + " tracked_viewer=" + trackedViewer
                        + " packet_ready=" + packet.isReady()
                        + " ack_scheduled=" + ackScheduled
                        + " send_thread=" + FoliaHarnessProbe.threadName()
                        + " client_ack=pending_bot_chat");
            } catch (Throwable throwable) {
                plugin.fail("packet-hazard", throwable);
            }
        });
    }

    void runNeighborHazard(World world, boolean suppressPhysics) {
        int chunkX = 80;
        int chunkZ = suppressPhysics ? 80 : 82;
        int x = (chunkX << 4) + 15;
        int z = (chunkZ << 4) + 8;
        int y = 82;

        Bukkit.getRegionScheduler().execute(plugin, world, chunkX, chunkZ, () -> {
            try {
                boolean neighborOwned = Bukkit.isOwnedByCurrentRegion(world, chunkX + 1, chunkZ);
                world.getBlockAt(x + 1, y, z).setType(Material.REDSTONE_WIRE, false);
                world.getBlockAt(x, y, z).setType(Material.AIR, false);
                world.getBlockAt(x, y, z).setType(Material.REDSTONE_BLOCK, !suppressPhysics);

                if (!suppressPhysics) {
                    world.dropItem(new Location(world, x + 0.5, y + 2, z + 0.5),
                            new ItemStack(Material.COBBLESTONE));
                    world.spawnFallingBlock(
                            new Location(world, x - 1 + 0.5, y + 12, z + 0.5),
                            Material.SAND.createBlockData()
                    );
                }

                long delay = suppressPhysics ? 2 : 60;
                Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task -> {
                    int eventCount = plugin.capturedEventCount();
                    plugin.finishEventCapture();
                    plugin.getLogger().info("FAWE_HARNESS_NEIGHBOR_OK mode="
                            + (suppressPhysics ? "suppressed" : "normal")
                            + " chunk_boundary=true"
                            + " neighbor_owned_by_caller=" + neighborOwned
                            + " events=" + eventCount
                            + " source=" + world.getBlockAt(x, y, z).getType()
                            + " neighbor=" + world.getBlockAt(x + 1, y, z).getType()
                            + " thread=" + FoliaHarnessProbe.threadName());
                }, delay);
            } catch (Throwable throwable) {
                plugin.finishEventCapture();
                plugin.fail("neighbor-hazard", throwable);
            }
        });
    }

    void runLighting(World world) {
        List<Target> groups = List.of(new Target(0, 96, 96), new Target(1, 224, 224));
        AtomicInteger remaining = new AtomicInteger(groups.size());
        AtomicInteger verified = new AtomicInteger();

        for (Target target : groups) {
            Bukkit.getRegionScheduler().execute(plugin, world, target.chunkX, target.chunkZ, () -> {
                try {
                    boolean neighborOwned = Bukkit.isOwnedByCurrentRegion(world, target.chunkX + 1, target.chunkZ);
                    if (!neighborOwned) {
                        plugin.getLogger().info("FAWE_HARNESS_LIGHTING_INCONCLUSIVE group=" + target.regionGroup
                                + " reason=adjacent_chunk_not_owned_by_caller");
                        if (remaining.decrementAndGet() == 0) {
                            logLightingSummary(verified.get(), groups.size());
                        }
                        return;
                    }

                    world.getChunkAt(target.chunkX, target.chunkZ);
                    world.getChunkAt(target.chunkX + 1, target.chunkZ);
                    int x = (target.chunkX << 4) + 15;
                    int y = SECTION_MIN_BLOCK_Y + 8;
                    int z = (target.chunkZ << 4) + 8;
                    world.getBlockAt(x, y, z).setType(Material.GLOWSTONE, false);
                    world.getBlockAt(x + 1, y, z).setType(Material.AIR, false);

                    ServerLevel level = ((CraftWorld) world).getHandle();
                    ThreadedLevelLightEngine lightEngine = level.getChunkSource().getLightEngine();
                    SectionPos sectionPos = SectionPos.of(target.chunkX, SECTION_Y, target.chunkZ);
                    DataLayer existing = lightEngine.getLayerListener(LightLayer.BLOCK)
                            .getDataLayerData(sectionPos);
                    lightEngine.queueSectionData(
                            LightLayer.BLOCK,
                            sectionPos,
                            existing == null ? new DataLayer() : existing.copy()
                    );
                    plugin.getLogger().info("FAWE_HARNESS_QUEUE_SECTION_OK group=" + target.regionGroup
                            + " owner=true thread=" + FoliaHarnessProbe.threadName());

                    List<ChunkPos> chunks = target.regionGroup == 0
                            ? List.of(new ChunkPos(target.chunkX, target.chunkZ))
                            : List.of(
                                    new ChunkPos(target.chunkX, target.chunkZ),
                                    new ChunkPos(target.chunkX + 1, target.chunkZ)
                            );
                    AtomicInteger acceptedCount = new AtomicInteger(-1);
                    int accepted = lightEngine.starlight$serverRelightChunks(
                            chunks,
                            chunkPos -> plugin.getLogger().info("FAWE_HARNESS_RELIGHT_CHUNK_CALLBACK"
                                    + " group=" + target.regionGroup
                                    + " chunk=" + chunkPos.x() + "," + chunkPos.z()
                                    + " owner=" + Bukkit.isOwnedByCurrentRegion(world, chunkPos.x(), chunkPos.z())
                                    + " thread=" + FoliaHarnessProbe.threadName()),
                            completed -> {
                                plugin.getLogger().info("FAWE_HARNESS_RELIGHT_COMPLETE_CALLBACK"
                                        + " group=" + target.regionGroup
                                        + " completed=" + completed
                                        + " thread=" + FoliaHarnessProbe.threadName());
                                Bukkit.getRegionScheduler().execute(
                                        plugin,
                                        world,
                                        target.chunkX,
                                        target.chunkZ,
                                        () -> {
                                            int neighborLight = world.getBlockAt(x + 1, y, z).getLightFromBlocks();
                                            if (neighborLight > 0) {
                                                verified.incrementAndGet();
                                            }
                                            plugin.getLogger().info("FAWE_HARNESS_RELIGHT_RESULT"
                                                    + " group=" + target.regionGroup
                                                    + " accepted=" + acceptedCount.get()
                                                    + " requested=" + chunks.size()
                                                    + " completed=" + completed
                                                    + " neighbor_block_light=" + neighborLight
                                                    + " cross_chunk_correct=" + (neighborLight > 0)
                                                    + " verify_thread=" + FoliaHarnessProbe.threadName());
                                            if (remaining.decrementAndGet() == 0) {
                                                logLightingSummary(verified.get(), groups.size());
                                            }
                                        }
                                );
                            }
                    );
                    acceptedCount.set(accepted);
                    plugin.getLogger().info("FAWE_HARNESS_RELIGHT_SUBMIT group=" + target.regionGroup
                            + " accepted=" + accepted
                            + " requested=" + chunks.size()
                            + " owner=true thread=" + FoliaHarnessProbe.threadName());
                } catch (Throwable throwable) {
                    plugin.fail("lighting-group-" + target.regionGroup, throwable);
                }
            });
        }
    }

    private void logLightingSummary(int verified, int groups) {
        plugin.getLogger().info("FAWE_HARNESS_LIGHTING_OK groups=" + groups
                + " cross_chunk_verified=" + verified
                + " concurrent_region_submits=" + groups);
    }

    private CompletableFuture<PreparedChunk> prepare(
            World world,
            Target target,
            BlockState fill,
            AtomicInteger regionTicks,
            boolean requireConcurrentTicks
    ) {
        CompletableFuture<PreparedChunk> future = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, world, target.chunkX, target.chunkZ, () -> {
            try {
                if (!Bukkit.isOwnedByCurrentRegion(world, target.chunkX, target.chunkZ)) {
                    throw new IllegalStateException("snapshot task is not owner: " + target);
                }
                world.getChunkAt(target.chunkX, target.chunkZ);
                world.addPluginChunkTicket(target.chunkX, target.chunkZ, plugin);
                ServerLevel level = ((CraftWorld) world).getHandle();
                LevelChunk chunk = requireLoadedChunk(level, target);
                int sectionIndex = level.getSectionIndexFromSectionY(SECTION_Y);
                LevelChunkSection detachedSnapshot = chunk.getSections()[sectionIndex].copy();
                int startTicks = regionTicks.get();

                workers.execute(() -> {
                    try {
                        LevelChunkSection prepared = new LevelChunkSection(
                                detachedSnapshot.getStates().copy(),
                                detachedSnapshot.getBiomes().copy()
                        );
                        for (int x = 0; x < 16; x++) {
                            for (int y = 0; y < 16; y++) {
                                for (int z = 0; z < 16; z++) {
                                    prepared.setBlockState(x, y, z, fill, false);
                                }
                            }
                        }
                        prepared.recalcBlockCounts();

                        if (requireConcurrentTicks) {
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            while (regionTicks.get() - startTicks < 3 && System.nanoTime() < deadline) {
                                Thread.sleep(1);
                            }
                            if (regionTicks.get() - startTicks < 3) {
                                throw new IllegalStateException("region did not tick during detached prepare");
                            }
                        }

                        future.complete(new PreparedChunk(
                                target,
                                prepared,
                                fill,
                                FoliaHarnessProbe.threadName(),
                                regionTicks.get() - startTicks
                        ));
                    } catch (Throwable throwable) {
                        future.completeExceptionally(throwable);
                    }
                });
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private CommitResult commitAndVerify(World world, PreparedChunk prepared) {
        Target target = prepared.target;
        boolean owner = Bukkit.isOwnedByCurrentRegion(world, target.chunkX, target.chunkZ);
        if (!owner) {
            throw new IllegalStateException("commit task is not owner: " + target);
        }

        ServerLevel level = ((CraftWorld) world).getHandle();
        LevelChunk chunk = requireLoadedChunk(level, target);
        int sectionIndex = level.getSectionIndexFromSectionY(SECTION_Y);
        chunk.getSections()[sectionIndex] = prepared.section;
        Heightmap.primeHeightmaps(chunk, EnumSet.of(
                Heightmap.Types.WORLD_SURFACE,
                Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES
        ));
        chunk.markUnsaved();
        level.getChunkSource().getLightEngine().updateSectionStatus(
                SectionPos.of(target.chunkX, SECTION_Y, target.chunkZ),
                prepared.section.hasOnlyAir()
        );

        int matches = countSection(chunk, target, prepared.fill);
        if (matches != BLOCKS_PER_SECTION) {
            throw new IllegalStateException("visible section mismatch at " + target
                    + ": " + matches + "/" + BLOCKS_PER_SECTION);
        }
        world.removePluginChunkTicket(target.chunkX, target.chunkZ, plugin);
        return new CommitResult(owner, matches);
    }

    private static int countSection(LevelChunk chunk, Target target, BlockState state) {
        int matches = 0;
        int baseX = target.chunkX << 4;
        int baseZ = target.chunkZ << 4;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (chunk.getBlockState(new BlockPos(baseX + x, SECTION_MIN_BLOCK_Y + y, baseZ + z)) == state) {
                        matches++;
                    }
                }
            }
        }
        return matches;
    }

    private static LevelChunk requireLoadedChunk(ServerLevel level, Target target) {
        LevelChunk chunk = level.getChunkIfLoaded(target.chunkX, target.chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("chunk not loaded after owner-thread load: " + target);
        }
        return chunk;
    }

    private List<RegionHeartbeat> startHeartbeats(World world, List<Target> targets) {
        Map<Integer, Target> anchors = new LinkedHashMap<>();
        targets.stream()
                .sorted(Comparator.comparingInt(Target::regionGroup))
                .forEach(target -> anchors.putIfAbsent(target.regionGroup, target));
        List<RegionHeartbeat> probes = new ArrayList<>();
        anchors.values().forEach(anchor -> {
            RegionHeartbeat heartbeat = new RegionHeartbeat(world, anchor);
            heartbeat.task = Bukkit.getRegionScheduler().runAtFixedRate(
                    plugin,
                    world,
                    anchor.chunkX,
                    anchor.chunkZ,
                    heartbeat::tick,
                    1,
                    1
            );
            probes.add(heartbeat);
        });
        return probes;
    }

    private static void stopHeartbeats(List<RegionHeartbeat> heartbeats) {
        heartbeats.forEach(heartbeat -> {
            ScheduledTask task = heartbeat.task;
            if (task != null) {
                task.cancel();
            }
        });
    }

    private static List<Collection<PreparedChunk>> groups(List<PreparedChunk> prepared, int size) {
        Map<Integer, List<PreparedChunk>> byRegion = new LinkedHashMap<>();
        prepared.forEach(item -> byRegion.computeIfAbsent(item.target.regionGroup, ignored -> new ArrayList<>())
                .add(item));
        List<Collection<PreparedChunk>> result = new ArrayList<>();
        for (List<PreparedChunk> region : byRegion.values()) {
            for (int start = 0; start < region.size(); start += size) {
                result.add(region.subList(start, Math.min(start + size, region.size())));
            }
        }
        return result;
    }

    private static List<Target> tournamentTargets() {
        List<Target> targets = new ArrayList<>(16);
        int[][] bases = {{32, 32}, {160, 32}, {32, 160}, {160, 160}};
        for (int x = 0; x < 2; x++) {
            for (int z = 0; z < 2; z++) {
                for (int group = 0; group < bases.length; group++) {
                    targets.add(new Target(group, bases[group][0] + x, bases[group][1] + z));
                }
            }
        }
        return List.copyOf(targets);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    enum Strategy {
        PER_CHUNK("per-chunk"),
        BATCHED_NEIGHBOR("batched-neighbor"),
        REGION_SWEEP("region-sweep");

        private final String id;

        Strategy(String id) {
            this.id = id;
        }

        static Strategy parse(String raw) {
            for (Strategy value : values()) {
                if (value.id.equalsIgnoreCase(raw)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("unknown strategy: " + raw);
        }
    }

    private record Target(int regionGroup, int chunkX, int chunkZ) {
        @Override
        public String toString() {
            return "group=" + regionGroup + ",chunk=" + chunkX + "," + chunkZ;
        }
    }

    private record PreparedChunk(
            Target target,
            LevelChunkSection section,
            BlockState fill,
            String prepareThread,
            int ticksDuringPrepare
    ) {
        private PreparedChunk {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(section, "section");
            Objects.requireNonNull(fill, "fill");
        }
    }

    private record CommitResult(boolean owner, int matches) {
    }

    private final class QueueMeter {
        private final AtomicInteger queued = new AtomicInteger();
        private final AtomicInteger inflight = new AtomicInteger();
        private final AtomicInteger highWater = new AtomicInteger();
        private final AtomicInteger inflightHighWater = new AtomicInteger();
        private final AtomicInteger submitted = new AtomicInteger();
        private final AtomicLong maxScheduleDelayNanos = new AtomicLong();
        private final AtomicLong maxTaskRunNanos = new AtomicLong();

        private void schedule(World world, Target target, Runnable runnable) {
            long enqueuedAt = System.nanoTime();
            submitted.incrementAndGet();
            int depth = queued.incrementAndGet();
            highWater.accumulateAndGet(depth, Math::max);
            Bukkit.getRegionScheduler().execute(plugin, world, target.chunkX, target.chunkZ, () -> {
                queued.decrementAndGet();
                int active = inflight.incrementAndGet();
                inflightHighWater.accumulateAndGet(active, Math::max);
                maxScheduleDelayNanos.accumulateAndGet(System.nanoTime() - enqueuedAt, Math::max);
                try {
                    runnable.run();
                } finally {
                    inflight.decrementAndGet();
                }
            });
        }
    }

    private record QueueSnapshot(
            String op,
            int depth,
            int inflight,
            int outstanding,
            int depthHighWater,
            int inflightHighWater
    ) {
        private static QueueSnapshot empty(String op) {
            return new QueueSnapshot(op, 0, 0, 0, 0, 0);
        }
    }

    private static final class RegionHeartbeat {
        private final World world;
        private final Target anchor;
        private final AtomicLong lastNanos = new AtomicLong();
        private final AtomicLong commitStartNanos = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong baselineMaxNanos = new AtomicLong();
        private final AtomicLong commitMaxNanos = new AtomicLong();
        private final AtomicInteger baselineSamples = new AtomicInteger();
        private final AtomicInteger commitSamples = new AtomicInteger();
        private volatile long regionId = -1;
        private volatile String tickThread = "pending";
        private volatile String tpsBefore = "pending";
        private volatile String tpsAfter = "pending";
        private volatile long tickTimeAverageBeforeNanos = -1;
        private volatile long tickTimeAverageAfterNanos = -1;
        private volatile long tickTimeMaxAfterNanos = -1;
        private volatile ScheduledTask task;

        private RegionHeartbeat(World world, Target anchor) {
            this.world = world;
            this.anchor = anchor;
        }

        private void tick(ScheduledTask ignored) {
            long now = System.nanoTime();
            long previous = lastNanos.getAndSet(now);
            var currentRegion = TickRegionScheduler.getCurrentRegion();
            regionId = currentRegion.id;
            tickThread = FoliaHarnessProbe.threadName();
            String tps = FoliaHarnessProbe.doubles(Bukkit.getRegionTPS(world, anchor.chunkX, anchor.chunkZ));
            if ("pending".equals(tpsBefore)) {
                tpsBefore = tps;
            }
            tpsAfter = tps;
            var report = currentRegion.getData().getRegionSchedulingHandle().getTickReport5s(now);
            if (report != null && report.timePerTickData() != null) {
                var all = report.timePerTickData().segmentAll();
                long average = Math.round(all.average());
                if (tickTimeAverageBeforeNanos < 0) {
                    tickTimeAverageBeforeNanos = average;
                }
                tickTimeAverageAfterNanos = average;
                tickTimeMaxAfterNanos = Math.round(all.greatest());
            }
            if (previous == 0) {
                return;
            }
            long interval = now - previous;
            if (now < commitStartNanos.get()) {
                baselineSamples.incrementAndGet();
                baselineMaxNanos.accumulateAndGet(interval, Math::max);
            } else {
                commitSamples.incrementAndGet();
                commitMaxNanos.accumulateAndGet(interval, Math::max);
            }
        }

        private String summary() {
            return String.format(
                    Locale.ROOT,
                    "g%d:region_id=%d,thread=%s,baseline_max_us=%d,commit_max_us=%d,baseline_samples=%d,commit_samples=%d,tick_avg_before_ns=%d,tick_avg_after_ns=%d,tick_max_after_ns=%d,tps_before=%s,tps_after=%s",
                    anchor.regionGroup,
                    regionId,
                    tickThread,
                    TimeUnit.NANOSECONDS.toMicros(baselineMaxNanos.get()),
                    TimeUnit.NANOSECONDS.toMicros(commitMaxNanos.get()),
                    baselineSamples.get(),
                    commitSamples.get(),
                    tickTimeAverageBeforeNanos,
                    tickTimeAverageAfterNanos,
                    tickTimeMaxAfterNanos,
                    tpsBefore,
                    tpsAfter
            );
        }

        private String perfSummary() {
            return String.format(
                    Locale.ROOT,
                    "group=%d region_id=%d thread=%s baseline_max_us=%d commit_max_us=%d "
                            + "baseline_samples=%d commit_samples=%d tick_avg_before_ns=%d "
                            + "tick_avg_after_ns=%d tick_max_after_ns=%d tps_before=%s tps_after=%s",
                    anchor.regionGroup,
                    regionId,
                    tickThread,
                    TimeUnit.NANOSECONDS.toMicros(baselineMaxNanos.get()),
                    TimeUnit.NANOSECONDS.toMicros(commitMaxNanos.get()),
                    baselineSamples.get(),
                    commitSamples.get(),
                    tickTimeAverageBeforeNanos,
                    tickTimeAverageAfterNanos,
                    tickTimeMaxAfterNanos,
                    tpsBefore,
                    tpsAfter
            );
        }
    }
}
