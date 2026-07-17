package com.fastasyncworldedit.bukkit.folia;

import com.sk89q.worldedit.internal.util.LogManagerCompat;
import org.apache.logging.log4j.Logger;
import org.bukkit.Bukkit;

import java.util.Set;

/**
 * Static Folia detection and fail-closed certified-matrix check.
 *
 * <p>No folia-api types appear in this class's method signatures or field types: detection
 * is done purely by reflection so the class remains loadable (and this check callable) on
 * Spigot/Paper servers that never have folia-api on the classpath. Detection runs before
 * Paper detection (spec §2) and must be the very first thing checked on plugin load.</p>
 */
public final class FoliaSupport {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    // Folia port: certified matrix (architecture.md §2, spec.md §2/§2b amended by A2.1) -
    // only these builds are certified against adapter-26.1. Keep in sync with the adapter
    // matrix; 26.2 joins only via a new signed amendment once Folia publishes it.
    private static final Set<String> CERTIFIED_MINECRAFT_VERSIONS = Set.of("26.1.2");

    private static final boolean FOLIA = detectFolia();

    private FoliaSupport() {
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Whether the server is running Folia. Result is computed once via reflection and
     * cached; safe to call on any platform.
     */
    public static boolean isFolia() {
        return FOLIA;
    }

    /**
     * Aborts plugin enablement when Folia is detected but the running build is outside
     * the certified matrix. Must be called before any listener, executor, or world access
     * (spec §2b) - i.e. at the very top of plugin load.
     *
     * @throws UnsupportedFoliaVersionException if the detected Folia build is not certified
     */
    public static void checkCertifiedOrFail() throws UnsupportedFoliaVersionException {
        // Folia port: Bukkit#getMinecraftVersion() is the only statically-known signal here;
        // exact runtime string format on Folia 26.1.1/26.1.2 is unverified without a live
        // server and must be confirmed once the harness (task 01) is available.
        String minecraftVersion = Bukkit.getMinecraftVersion();
        if (!CERTIFIED_MINECRAFT_VERSIONS.contains(minecraftVersion)) {
            String message = "FastAsyncWorldEdit detected Folia (Minecraft version '" + minecraftVersion
                    + "'), which is outside the certified matrix " + CERTIFIED_MINECRAFT_VERSIONS + ". "
                    + "FAWE will not enable on uncertified Folia builds, to avoid unsafe cross-region world access. "
                    + "Please run a certified Folia build or downgrade/upgrade FAWE.";
            LOGGER.error(message);
            throw new UnsupportedFoliaVersionException(message);
        }
    }

}
