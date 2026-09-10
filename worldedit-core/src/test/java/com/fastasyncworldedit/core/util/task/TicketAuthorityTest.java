/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.fastasyncworldedit.core.util.task;

import com.fastasyncworldedit.core.entity.LazyBaseEntity;
import com.fastasyncworldedit.core.queue.implementation.QueueHandler;
import com.fastasyncworldedit.core.queue.implementation.QueueHandlerRouting;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.entity.EntityType;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.SAME_THREAD)
class TicketAuthorityTest {

    private static final MutableContext CONTEXT = new MutableContext();
    private static final TicketAuthority AUTHORITY = TicketAuthority.issue();

    @BeforeAll
    static void registerContext() {
        ContextResolver.register(CONTEXT);
    }

    @Test
    void rejectsSecondAuthorityIssuance() {
        assertThrows(IllegalStateException.class, TicketAuthority::issue);
    }

    @Test
    void regionTicketIsThreadBoundAndRetiredExactlyOnce() throws InterruptedException {
        World world = world("ticket-world");
        CONTEXT.ownedWorld = world;
        CONTEXT.ownedChunkX = 4;
        CONTEXT.ownedChunkZ = 7;
        RegionTicket ticket = AUTHORITY.mintRegion(world, 4, 7);

        assertSame(world, ticket.world());
        assertTrue(ticket.isLive());
        assertTrue(ticket.owns(4, 7));
        ticket.assertOwns(4, 7);

        AtomicReference<Throwable> escapedFailure = new AtomicReference<>();
        Thread escapedUse = new Thread(() -> escapedFailure.set(assertThrows(
                WrongOwnerException.class,
                () -> ticket.assertOwns(4, 7)
        )));
        escapedUse.start();
        escapedUse.join();
        assertTrue(escapedFailure.get() instanceof WrongOwnerException);

        AUTHORITY.retire(ticket);
        assertFalse(ticket.isLive());
        assertThrows(WrongOwnerException.class, () -> ticket.assertOwns(4, 7));
        assertThrows(IllegalStateException.class, () -> AUTHORITY.retire(ticket));
    }

    @Test
    void entityTicketRequiresTheExactEntityAndOwnerContext() throws InterruptedException {
        Entity entity = mock(Entity.class);
        Entity other = mock(Entity.class);
        CONTEXT.ownedEntity = entity;
        EntityTicket ticket = AUTHORITY.mintEntity(entity);

        assertSame(entity, ticket.entity());
        assertTrue(ticket.isLive());
        ticket.assertOwns(entity);
        assertThrows(IllegalStateException.class, () -> ticket.assertOwns(other));

        AtomicReference<Throwable> escapedFailure = new AtomicReference<>();
        Thread escapedUse = new Thread(() -> escapedFailure.set(assertThrows(
                IllegalStateException.class,
                () -> ticket.assertOwns(entity)
        )));
        escapedUse.start();
        escapedUse.join();
        assertTrue(escapedFailure.get() instanceof IllegalStateException);

        AUTHORITY.retire(ticket);
        assertFalse(ticket.isLive());
        assertThrows(IllegalStateException.class, () -> ticket.assertOwns(entity));
    }

    @Test
    void mintSequenceIsMonotonicAcrossTicketKinds() {
        World world = world("sequence-world");
        Entity entity = mock(Entity.class);
        CONTEXT.ownedWorld = world;
        CONTEXT.ownedChunkX = 1;
        CONTEXT.ownedChunkZ = 2;
        CONTEXT.ownedEntity = entity;

        RegionTicket regionTicket = AUTHORITY.mintRegion(world, 1, 2);
        AUTHORITY.retire(regionTicket);
        EntityTicket entityTicket = AUTHORITY.mintEntity(entity);
        AUTHORITY.retire(entityTicket);

        assertTrue(entityTicket.sequence() > regionTicket.sequence());
    }

    @Test
    void lazyEntityWorkerDispatchesToTheOwningContextInsteadOfRunningInline() throws Exception {
        Entity entity = mock(Entity.class);
        LinCompoundTag tag = LinCompoundTag.builder().build();
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> supplierThread = new AtomicReference<>();
        QueueHandler handler = mock(QueueHandler.class, invocation -> {
            if (!invocation.getMethod().getName().equals("syncOn")) {
                return invocation.callRealMethod();
            }
            EntityTarget target = invocation.getArgument(0);
            EntityTask task = invocation.getArgument(1);
            CompletableFuture<Void> result = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                CONTEXT.tickThread.set(true);
                CONTEXT.ownedEntity = target.entity();
                EntityTicket ticket = AUTHORITY.mintEntity(target.entity());
                try {
                    task.run(ticket);
                    result.complete(null);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                } finally {
                    AUTHORITY.retire(ticket);
                    CONTEXT.tickThread.remove();
                }
            });
            return result;
        });
        CONTEXT.tickThread.set(false);
        CONTEXT.ownedEntity = null;

        try (AutoCloseable ignored = overrideRouting(handler)) {
            LazyBaseEntity lazy = new LazyBaseEntity(
                    new EntityType("test:lazy"),
                    new EntityTarget(entity),
                    () -> {
                        supplierThread.set(Thread.currentThread());
                        assertTrue(CONTEXT.ownsEntity(entity));
                        return tag;
                    }
            );

            assertSame(tag, lazy.getNbt());
            assertTrue(supplierThread.get() != caller);
        } finally {
            CONTEXT.tickThread.remove();
            CONTEXT.ownedEntity = null;
        }
    }

    @Test
    void lazyEntityNonOwnerTickFailsBeforeDispatchOrSerialization() throws Exception {
        Entity entity = mock(Entity.class);
        AtomicInteger dispatches = new AtomicInteger();
        AtomicInteger sideEffects = new AtomicInteger();
        QueueHandler handler = mock(QueueHandler.class, invocation -> {
            if (invocation.getMethod().getName().equals("syncOn")) {
                dispatches.incrementAndGet();
                throw new AssertionError("A wrong-owner tick caller must fail before dispatch");
            }
            return invocation.callRealMethod();
        });
        CONTEXT.tickThread.set(true);
        CONTEXT.ownedEntity = null;
        LazyBaseEntity lazy = new LazyBaseEntity(
                new EntityType("test:wrong-owner"),
                new EntityTarget(entity),
                () -> {
                    sideEffects.incrementAndGet();
                    return LinCompoundTag.builder().build();
                }
        );

        try (AutoCloseable ignored = overrideRouting(handler)) {
            assertThrows(IllegalStateException.class, lazy::getNbt);
        } finally {
            CONTEXT.tickThread.remove();
        }
        assertTrue(dispatches.get() == 0);
        assertTrue(sideEffects.get() == 0);
    }

    private static AutoCloseable overrideRouting(QueueHandler handler) throws Exception {
        Method method = QueueHandlerRouting.class.getDeclaredMethod("overrideActiveForTesting", QueueHandler.class);
        method.setAccessible(true);
        return (AutoCloseable) method.invoke(null, handler);
    }

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        return world;
    }

    private static final class MutableContext implements FaweThreadContext {

        private final ThreadLocal<Boolean> tickThread = ThreadLocal.withInitial(() -> true);
        private volatile World ownedWorld;
        private volatile int ownedChunkX;
        private volatile int ownedChunkZ;
        private volatile Entity ownedEntity;

        @Override
        public boolean isTickThread() {
            return tickThread.get();
        }

        @Override
        public boolean ownsChunk(World world, int chunkX, int chunkZ) {
            return world == ownedWorld && chunkX == ownedChunkX && chunkZ == ownedChunkZ;
        }

        @Override
        public boolean ownsEntity(Entity entity) {
            return entity == ownedEntity;
        }

        @Override
        public boolean isGlobalContext() {
            return false;
        }

        @Override
        public boolean isFaweWorker() {
            return false;
        }

    }

}
