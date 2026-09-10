package com.fastasyncworldedit.bukkit;

import com.fastasyncworldedit.core.util.task.FawePlatformBackend;
import org.jetbrains.annotations.ApiStatus;

import java.util.Iterator;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/** Selects and registers exactly one backend after platform detection. */
@ApiStatus.Internal
public final class BackendSelector {

    static final String MISSING_FOLIA_BACKEND_MESSAGE =
            "FastAsyncWorldEdit detected Folia, but this FAWE artifact does not contain the Folia backend. "
                    + "Install the Paper/Mojang FAWE jar; the Spigot artifact cannot run on Folia.";

    private BackendSelector() {
    }

    public static FawePlatformBackend selectAndRegister(boolean folia, ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        return selectAndRegister(
                folia,
                BukkitPlatformBackend::new,
                () -> ServiceLoader.load(FawePlatformBackend.class, classLoader)
        );
    }

    static FawePlatformBackend selectAndRegister(
            boolean folia,
            Supplier<FawePlatformBackend> bukkitBackend,
            Supplier<? extends Iterable<FawePlatformBackend>> foliaBackends
    ) {
        Objects.requireNonNull(bukkitBackend, "bukkitBackend");
        Objects.requireNonNull(foliaBackends, "foliaBackends");
        FawePlatformBackend backend = folia
                ? selectFoliaBackend(foliaBackends)
                : Objects.requireNonNull(bukkitBackend.get(), "bukkit backend");
        backend.registerThreadContext();
        return backend;
    }

    private static FawePlatformBackend selectFoliaBackend(
            Supplier<? extends Iterable<FawePlatformBackend>> foliaBackends
    ) {
        try {
            Iterator<FawePlatformBackend> candidates = foliaBackends.get().iterator();
            if (!candidates.hasNext()) {
                throw missingFoliaBackend(null);
            }
            FawePlatformBackend backend = Objects.requireNonNull(candidates.next(), "Folia backend");
            if (candidates.hasNext()) {
                throw new IllegalStateException("Multiple Folia backends were discovered; refusing ambiguous bootstrap");
            }
            return backend;
        } catch (ServiceConfigurationError failure) {
            throw missingFoliaBackend(failure);
        }
    }

    private static IllegalStateException missingFoliaBackend(Throwable cause) {
        return new IllegalStateException(MISSING_FOLIA_BACKEND_MESSAGE, cause);
    }

}
