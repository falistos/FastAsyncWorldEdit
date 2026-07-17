package com.fastasyncworldedit.harness;

import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Standalone Folia/NMS pipeline probe. Never package this class in FAWE. */
public final class FoliaHarnessProbe extends JavaPlugin implements Listener {

    static final String WORLD_NAME = "fawe-harness";
    static final String SCRATCH_WORLD_NAME = "fawe-scratch";

    private final AtomicBoolean captureEvents = new AtomicBoolean();
    private final AtomicInteger capturedEvents = new AtomicInteger();
    private ExecutorService workers;
    private PipelineProbe pipeline;

    @Override
    public void onLoad() {
        getLogger().info("FAWE_HARNESS_STARTUP_WORLDS phase=load worlds=" + worldNames());
    }

    @Override
    public void onEnable() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "fawe-pipeline-worker-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        workers = Executors.newFixedThreadPool(4, factory);
        pipeline = new PipelineProbe(this, workers);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("FAWE_HARNESS_STARTUP_WORLDS phase=enable worlds=" + worldNames());
    }

    @Override
    public void onDisable() {
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        getLogger().info("FAWE_HARNESS_SCRATCH_GENERATOR_REQUEST world=" + worldName
                + " id=" + (id == null ? "<null>" : id));
        return new ChunkGenerator() {
            @Override
            public boolean isParallelCapable() {
                return true;
            }
        };
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length == 0) {
            return false;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "fill-verify" -> runLegacyFill(sender);
                case "environment" -> runEnvironment();
                case "safety" -> pipeline.runSafety(requireWorld());
                case "verify-readback" -> pipeline.verifyReadback(requireWorld());
                case "tournament" -> {
                    if (args.length < 2 || args.length > 3) {
                        return false;
                    }
                    int chunks = args.length == 3 ? Integer.parseInt(args[2]) : 16;
                    pipeline.runTournament(requireWorld(), PipelineProbe.Strategy.parse(args[1]), chunks);
                }
                case "perf-mark" -> {
                    if (args.length != 2) {
                        return false;
                    }
                    pipeline.sampleMark(requireWorld(), args[1]);
                }
                case "perf-run" -> {
                    if (args.length < 3 || args.length > 4) {
                        return false;
                    }
                    int chunks = args.length == 4 ? Integer.parseInt(args[3]) : 16;
                    pipeline.runPerf(
                            requireWorld(),
                            args[1],
                            PipelineProbe.Strategy.parse(args[2]),
                            chunks
                    );
                }
                case "perf-queue" -> {
                    if (args.length != 2) {
                        return false;
                    }
                    pipeline.logQueue(args[1]);
                }
                case "hazard-packet" -> {
                    if (args.length != 4) {
                        return false;
                    }
                    pipeline.runPacketHazard(
                            requireWorld(),
                            args[1],
                            Integer.parseInt(args[2]),
                            Integer.parseInt(args[3])
                    );
                }
                case "hazard-neighbor" -> {
                    if (args.length != 2) {
                        return false;
                    }
                    boolean suppressPhysics = switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "suppressed" -> true;
                        case "normal" -> false;
                        default -> throw new IllegalArgumentException("expected suppressed|normal");
                    };
                    captureEvents.set(true);
                    capturedEvents.set(0);
                    pipeline.runNeighborHazard(requireWorld(), suppressPhysics);
                }
                case "lighting" -> pipeline.runLighting(requireWorld());
                case "scratch-world" -> runScratchWorld();
                default -> {
                    return false;
                }
            }
            sender.sendMessage("FAWE harness command accepted: " + String.join(" ", args));
        } catch (Throwable throwable) {
            fail(args[0], throwable);
        }
        return true;
    }

    private void runEnvironment() {
        World world = requireWorld();
        getLogger().info("FAWE_HARNESS_ENV_OK minecraft=" + Bukkit.getMinecraftVersion()
                + " bukkit=" + Bukkit.getBukkitVersion()
                + " name=" + Bukkit.getName().replace(' ', '_')
                + " paperlib_is_paper=" + PaperLib.isPaper()
                + " thread=" + threadName());

        world.getChunkAtAsync(12, 12, true).whenComplete((chunk, throwable) -> {
            if (throwable != null) {
                fail("getChunkAtAsync", throwable);
                return;
            }
            getLogger().info("FAWE_HARNESS_ASYNC_CHUNK_OK chunk=" + chunk.getX() + "," + chunk.getZ()
                    + " owner=" + Bukkit.isOwnedByCurrentRegion(world, chunk.getX(), chunk.getZ())
                    + " thread=" + threadName());
        });
    }

    private void runScratchWorld() {
        World scratch = Bukkit.getWorld(SCRATCH_WORLD_NAME);
        if (scratch == null) {
            getLogger().info("FAWE_HARNESS_SCRATCH_RESULT loaded=false worlds=" + worldNames());
            return;
        }

        Bukkit.getRegionScheduler().execute(this, scratch, 0, 0, () -> {
            try {
                boolean owner = Bukkit.isOwnedByCurrentRegion(scratch, 0, 0);
                Material material = scratch.getChunkAt(0, 0).getBlock(0, 64, 0).getType();
                getLogger().info("FAWE_HARNESS_SCRATCH_RESULT loaded=true chunk_read=true owner=" + owner
                        + " material=" + material + " thread=" + threadName());
            } catch (Throwable throwable) {
                fail("scratch-world", throwable);
            }
        });
    }

    private void runLegacyFill(CommandSender sender) {
        World world = requireWorld();
        Bukkit.getRegionScheduler().execute(this, world, 0, 0, () -> {
            if (!Bukkit.isOwnedByCurrentRegion(world, 0, 0)) {
                getLogger().severe("FAWE_HARNESS_VOLUME_FAIL reason=not-owner");
                return;
            }

            try {
                getLogger().info("FAWE_HARNESS_REGION_OWNER_OK owner=true thread=" + threadName());
                setLegacyVolume(world, Material.AIR);
                setLegacyVolume(world, Material.STONE);
                int matches = countLegacyVolume(world, Material.STONE);
                if (matches != 64) {
                    getLogger().severe("FAWE_HARNESS_VOLUME_FAIL blocks=" + matches
                            + " expected=64 material=STONE owner=true");
                    return;
                }
                getLogger().info("FAWE_HARNESS_VOLUME_OK blocks=64 material=STONE owner=true");
            } catch (Throwable throwable) {
                fail("legacy-fill", throwable);
            }
        });
        sender.sendMessage("FAWE harness fill scheduled for the owning region");
    }

    private static void setLegacyVolume(World world, Material material) {
        for (int x = 0; x <= 3; x++) {
            for (int y = 80; y <= 83; y++) {
                for (int z = 0; z <= 3; z++) {
                    world.getBlockAt(x, y, z).setType(material, false);
                }
            }
        }
    }

    private static int countLegacyVolume(World world, Material material) {
        int matches = 0;
        for (int x = 0; x <= 3; x++) {
            for (int y = 80; y <= 83; y++) {
                for (int z = 0; z <= 3; z++) {
                    if (world.getBlockAt(x, y, z).getType() == material) {
                        matches++;
                    }
                }
            }
        }
        return matches;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPhysics(BlockPhysicsEvent event) {
        recordEvent("BlockPhysicsEvent", event.getBlock().getWorld(),
                event.getBlock().getX() >> 4, event.getBlock().getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        recordEvent("EntityChangeBlockEvent", event.getBlock().getWorld(),
                event.getBlock().getX() >> 4, event.getBlock().getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onItemSpawn(ItemSpawnEvent event) {
        recordEvent("ItemSpawnEvent", event.getLocation().getWorld(),
                event.getLocation().getBlockX() >> 4, event.getLocation().getBlockZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        getLogger().info("FAWE_HARNESS_WORLD_LOAD world=" + event.getWorld().getName()
                + " thread=" + threadName());
    }

    private void recordEvent(String event, World world, int chunkX, int chunkZ) {
        if (!captureEvents.get()) {
            return;
        }
        int sequence = capturedEvents.incrementAndGet();
        if (sequence <= 16) {
            getLogger().info("FAWE_HARNESS_EVENT event=" + event
                    + " owner=" + Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)
                    + " chunk=" + chunkX + "," + chunkZ
                    + " thread=" + threadName());
        }
    }

    void finishEventCapture() {
        captureEvents.set(false);
    }

    int capturedEventCount() {
        return capturedEvents.get();
    }

    void fail(String leg, Throwable throwable) {
        getLogger().log(Level.SEVERE, "FAWE_HARNESS_PIPELINE_FAIL leg=" + leg, throwable);
    }

    private World requireWorld() {
        World world = Bukkit.getWorld(WORLD_NAME);
        if (world == null) {
            throw new IllegalStateException("world not found: " + WORLD_NAME);
        }
        return world;
    }

    private static String worldNames() {
        return Bukkit.getWorlds().stream().map(World::getName).sorted().collect(Collectors.joining(","));
    }

    static String threadName() {
        return Thread.currentThread().getName().replace(' ', '_');
    }

    static String doubles(double[] values) {
        return Arrays.stream(values)
                .mapToObj(value -> String.format(Locale.ROOT, "%.3f", value))
                .collect(Collectors.joining(","));
    }
}
