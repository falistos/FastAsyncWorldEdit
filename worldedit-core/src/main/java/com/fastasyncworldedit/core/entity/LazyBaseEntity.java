package com.fastasyncworldedit.core.entity;

import com.fastasyncworldedit.core.queue.implementation.QueueHandlerRouting;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.task.EntityTarget;
import com.fastasyncworldedit.core.util.task.FaweThreadContext;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.world.entity.EntityType;
import org.enginehub.linbus.tree.LinCompoundTag;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class LazyBaseEntity extends BaseEntity {

    private static final long OWNER_WAIT_SECONDS = 60L;

    @Nullable
    private final EntityTarget target;
    private Supplier<LinCompoundTag> saveTag;

    /** Compatibility-only constructor for backends that permit location-free live-state access. */
    public LazyBaseEntity(EntityType type, Supplier<LinCompoundTag> saveTag) {
        super(type);
        this.target = null;
        this.saveTag = Objects.requireNonNull(saveTag, "saveTag");
    }

    public LazyBaseEntity(EntityType type, EntityTarget target, Supplier<LinCompoundTag> saveTag) {
        super(type);
        this.target = Objects.requireNonNull(target, "target");
        this.saveTag = Objects.requireNonNull(saveTag, "saveTag");
    }

    @Nullable
    @Override
    public LinCompoundTag getNbt() {
        Supplier<LinCompoundTag> tmp = saveTag;
        if (tmp != null) {
            if (target == null) {
                loadLegacy(tmp);
            } else {
                loadTargeted(tmp);
            }
            saveTag = null;
        }
        return super.getNbt();
    }

    private void loadLegacy(Supplier<LinCompoundTag> supplier) {
        if (!QueueHandlerRouting.permitsLegacyLocationFreeLiveState()) {
            throw new IllegalStateException("Lazy entity NBT requires an EntityTarget on this backend");
        }
        if (FaweThreadContext.current().isGlobalContext()) {
            setNbt(supplier.get());
        } else {
            setNbt(TaskManager.taskManager().sync(supplier));
        }
    }

    private void loadTargeted(Supplier<LinCompoundTag> supplier) {
        FaweThreadContext context = FaweThreadContext.current();
        if (context.isTickThread() && !context.ownsEntity(target.entity())) {
            throw new IllegalStateException("A non-owning tick thread cannot await lazy entity NBT");
        }

        AtomicReference<LinCompoundTag> serialized = new AtomicReference<>();
        CompletionStage<Void> stage = QueueHandlerRouting.syncOn(target, ticket -> {
            ticket.assertOwns(target.entity());
            serialized.set(supplier.get());
        });
        awaitOwner(stage, context.isTickThread());
        setNbt(serialized.get());
    }

    private static void awaitOwner(CompletionStage<Void> stage, boolean tickThread) {
        var future = stage.toCompletableFuture();
        if (tickThread && !future.isDone()) {
            future.cancel(false);
            throw new IllegalStateException("Owning entity dispatch did not complete inline");
        }
        try {
            future.get(OWNER_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting lazy entity NBT", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Lazy entity NBT serialization failed", failure.getCause());
        } catch (TimeoutException timeout) {
            future.cancel(false);
            throw new IllegalStateException("Timed out awaiting lazy entity NBT", timeout);
        }
    }

}
