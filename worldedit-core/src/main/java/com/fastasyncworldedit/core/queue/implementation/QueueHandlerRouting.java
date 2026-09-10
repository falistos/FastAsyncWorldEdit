package com.fastasyncworldedit.core.queue.implementation;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.util.task.ChunkTarget;
import com.fastasyncworldedit.core.util.task.EntityTarget;
import com.fastasyncworldedit.core.util.task.EntityTask;
import com.fastasyncworldedit.core.util.task.GlobalTask;
import com.fastasyncworldedit.core.util.task.RegionCall;
import com.fastasyncworldedit.core.util.task.RegionTask;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * INTERNAL-NONAPI cross-package facade for target-bearing live-state routing.
 * Callers receive no backend dispatcher or platform scheduler.
 */
@ApiStatus.Internal
public final class QueueHandlerRouting {

    private static final ThreadLocal<QueueHandler> TEST_ACTIVE = new ThreadLocal<>();

    private QueueHandlerRouting() {
    }

    public static <T> CompletionStage<T> syncOn(ChunkTarget target, RegionCall<T> call) {
        return active().syncOn(
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(call, "call")
        );
    }

    public static CompletionStage<Void> syncOn(ChunkTarget target, RegionTask task) {
        return active().syncOn(
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(task, "task")
        );
    }

    public static CompletionStage<Void> syncOn(EntityTarget target, EntityTask task) {
        return active().syncOn(
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(task, "task")
        );
    }

    public static CompletionStage<Void> syncOnGlobal(GlobalTask task) {
        return active().syncOnGlobal(Objects.requireNonNull(task, "task"));
    }

    public static boolean permitsLegacyLocationFreeLiveState() {
        return active().permitsLegacyLocationFreeLiveState();
    }

    private static QueueHandler active() {
        QueueHandler testHandler = TEST_ACTIVE.get();
        if (testHandler != null) {
            return testHandler;
        }
        Fawe fawe = Fawe.instance();
        if (fawe == null) {
            throw new IllegalStateException("FAWE is not initialized; target-bearing routing is unavailable");
        }
        return Objects.requireNonNull(fawe.getQueueHandler(), "active queue handler");
    }

    static AutoCloseable overrideActiveForTesting(QueueHandler handler) {
        QueueHandler previous = TEST_ACTIVE.get();
        TEST_ACTIVE.set(Objects.requireNonNull(handler, "handler"));
        return () -> {
            if (previous == null) {
                TEST_ACTIVE.remove();
            } else {
                TEST_ACTIVE.set(previous);
            }
        };
    }

}
