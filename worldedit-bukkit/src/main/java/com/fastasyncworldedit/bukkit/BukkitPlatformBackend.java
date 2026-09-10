package com.fastasyncworldedit.bukkit;

import com.fastasyncworldedit.bukkit.adapter.BukkitQueueHandler;
import com.fastasyncworldedit.bukkit.util.BukkitTaskManager;
import com.fastasyncworldedit.bukkit.util.BukkitThreadContext;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.task.ContextResolver;
import com.fastasyncworldedit.core.util.task.FawePlatformBackend;
import com.fastasyncworldedit.core.util.task.TicketAuthority;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.AtomicBoolean;

/** Paper/Spigot service graph behind the core-owned bootstrap SPI. */
final class BukkitPlatformBackend implements FawePlatformBackend {

    private final AtomicBoolean shutdown = new AtomicBoolean();
    private State state = State.NEW;
    private TicketAuthority ticketAuthority;
    private TaskManager taskManager;
    private QueueHandler queueHandler;

    @Override
    public synchronized void registerThreadContext() {
        requireState(State.NEW, "register the Bukkit thread context");
        ContextResolver.register(new BukkitThreadContext());
        ticketAuthority = TicketAuthority.issue();
        state = State.REGISTERED;
    }

    @Override
    public synchronized TaskManager createTaskManager(Object platform) {
        requireState(State.REGISTERED, "create the Bukkit task manager");
        if (!(platform instanceof Plugin plugin)) {
            throw new IllegalArgumentException("The Bukkit backend requires a Plugin platform object");
        }
        taskManager = new BukkitTaskManager(plugin);
        state = State.TASK_MANAGER_CREATED;
        return taskManager;
    }

    @Override
    public synchronized QueueHandler createQueueHandler() {
        if (state == State.QUEUE_CREATED) {
            return queueHandler;
        }
        requireState(State.TASK_MANAGER_CREATED, "create the Bukkit queue handler");
        queueHandler = new BukkitQueueHandler(ticketAuthority);
        state = State.QUEUE_CREATED;
        return queueHandler;
    }

    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        QueueHandler currentQueue;
        TaskManager currentTaskManager;
        synchronized (this) {
            currentQueue = queueHandler;
            currentTaskManager = taskManager;
        }
        if (currentQueue != null) {
            currentQueue.shutdownExecutors();
        }
        if (currentTaskManager != null) {
            currentTaskManager.getPublicForkJoinPool().shutdownNow();
        }
    }

    private void requireState(State expected, String action) {
        if (state != expected) {
            throw new IllegalStateException("Cannot " + action + " while backend state is " + state);
        }
    }

    private enum State {
        NEW,
        REGISTERED,
        TASK_MANAGER_CREATED,
        QUEUE_CREATED
    }

}
