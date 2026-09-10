package com.fastasyncworldedit.bukkit;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.task.FawePlatformBackend;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendSelectorTest {

    @Test
    void nonFoliaSelectionNeverLoadsTheFoliaProvider() {
        RecordingBackend bukkit = new RecordingBackend();
        AtomicBoolean foliaDiscovery = new AtomicBoolean();

        FawePlatformBackend selected = BackendSelector.selectAndRegister(
                false,
                () -> bukkit,
                () -> {
                    foliaDiscovery.set(true);
                    throw new AssertionError("Folia discovery must stay lazy");
                }
        );

        assertSame(bukkit, selected);
        assertEquals(List.of("context"), bukkit.events);
        assertFalse(foliaDiscovery.get());
    }

    @Test
    void missingFoliaBackendFailsWithOperatorMessageBeforeAnyRegistration() {
        AtomicBoolean bukkitConstruction = new AtomicBoolean();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> BackendSelector.selectAndRegister(
                        true,
                        () -> {
                            bukkitConstruction.set(true);
                            return new RecordingBackend();
                        },
                        List::<FawePlatformBackend>of
                )
        );

        assertEquals(BackendSelector.MISSING_FOLIA_BACKEND_MESSAGE, failure.getMessage());
        assertFalse(bukkitConstruction.get());
    }

    @Test
    void selectionRegistersBeforeEitherServiceCanReachAPredicate() {
        RecordingBackend folia = new RecordingBackend();

        FawePlatformBackend selected = BackendSelector.selectAndRegister(
                true,
                RecordingBackend::new,
                () -> List.of(folia)
        );
        selected.createTaskManager(new Object());
        selected.createQueueHandler();

        assertTrue(folia.registered);
        assertEquals(List.of("context", "task-manager", "queue-predicate"), folia.events);
    }

    private static final class RecordingBackend implements FawePlatformBackend {

        private final List<String> events = new ArrayList<>();
        private boolean registered;

        @Override
        public void registerThreadContext() {
            if (registered) {
                throw new IllegalStateException("duplicate registration");
            }
            registered = true;
            events.add("context");
        }

        @Override
        public TaskManager createTaskManager(Object platform) {
            requireRegistered();
            events.add("task-manager");
            return null;
        }

        @Override
        public QueueHandler createQueueHandler() {
            requireRegistered();
            events.add("queue-predicate");
            return null;
        }

        @Override
        public void shutdown() {
        }

        private void requireRegistered() {
            if (!registered) {
                throw new IllegalStateException("predicate reached before context registration");
            }
        }

    }

}
