package com.fastasyncworldedit.core.util.task;

import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.TaskManager;

/**
 * Core-owned bootstrap SPI for a platform backend.
 *
 * <p>The platform object is deliberately opaque so core never links a downstream platform type.
 * A selected backend registers its thread context before any service factory may be called.</p>
 */
public interface FawePlatformBackend {

    /** Registers the backend context and issues its sole ticket authority. Exactly once. */
    void registerThreadContext();

    /** Creates the platform task manager after context registration. Exactly once. */
    TaskManager createTaskManager(Object platform);

    /** Creates the queue and its collaborators after the task manager exists. Exactly once. */
    QueueHandler createQueueHandler();

    /** Starts the backend's ordered, idempotent shutdown. */
    void shutdown();

}
