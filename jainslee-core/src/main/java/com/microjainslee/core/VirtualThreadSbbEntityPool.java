/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.Sbb;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * JAIN-SLEE 1.1 §8.2 — Per-SBB Virtual Thread Entity Pool with slot recycling.
 *
 * <p>Each active SBB entity id maps to a {@link EntitySlot} borrowed from an
 * internal {@link EntitySlotPool}. When the entity is released the slot (and
 * its parked virtual thread) is returned for reuse by a future session.
 */
public final class VirtualThreadSbbEntityPool {

    /** Per-SBB entity facade over a reusable {@link EntitySlot}. */
    public static final class SbbEntity {
        private final EntitySlot slot;
        private final String sbbId;
        private final Sbb sbb;

        SbbEntity(EntitySlot slot, String sbbId, Sbb sbb) {
            this.slot = slot;
            this.sbbId = sbbId;
            this.sbb = sbb;
        }

        EntitySlot getSlot() {
            return slot;
        }

        public String getSbbId() {
            return sbbId;
        }

        public Sbb getSbb() {
            return sbb;
        }

        public void submit(Runnable task) {
            slot.submit(task);
        }

        boolean isParked() {
            return slot.isParked();
        }

        boolean isShutdown() {
            return slot.isShutdown();
        }
    }

    private final ConcurrentHashMap<String, SbbEntity> entities =
            new ConcurrentHashMap<String, SbbEntity>();
    private final EntitySlotPool slotPool;
    private final int min;
    private final int max;
    private final ExecutorService executor;
    private volatile boolean shuttingDown;

    public VirtualThreadSbbEntityPool(int min, int max, boolean perVirtualThread) {
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0");
        }
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1");
        }
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
        }
        this.min = min;
        this.max = max;
        ExecutorService chosen = null;
        if (perVirtualThread) {
            chosen = MicroSleeExecutors.newVirtualThreadPerTaskExecutor();
        }
        if (chosen == null) {
            chosen = java.util.concurrent.Executors.newCachedThreadPool();
        }
        this.executor = chosen;
        this.slotPool = new EntitySlotPool(min, max, executor);
    }

    public SbbEntity acquire(String sbbId, Supplier<Sbb> factory) {
        if (shuttingDown) {
            throw new IllegalStateException("SbbEntityPool is shutting down");
        }
        if (sbbId == null || factory == null) {
            throw new IllegalArgumentException("sbbId and factory are required");
        }
        SbbEntity existing = entities.get(sbbId);
        if (existing != null) {
            return existing;
        }
        Sbb sbb = factory.get();
        EntitySlot slot = slotPool.borrow();
        slot.bind(sbbId, 0L, sbb);
        SbbEntity fresh = new SbbEntity(slot, sbbId, sbb);
        SbbEntity prior = entities.putIfAbsent(sbbId, fresh);
        if (prior != null) {
            slot.unbind();
            slotPool.release(slot);
            return prior;
        }
        return fresh;
    }

    public SbbEntity acquire(String sbbId, long entityId, Sbb sbb) {
        if (shuttingDown) {
            throw new IllegalStateException("SbbEntityPool is shutting down");
        }
        if (sbbId == null || sbb == null) {
            throw new IllegalArgumentException("sbbId and sbb are required");
        }
        SbbEntity existing = entities.get(sbbId);
        if (existing != null) {
            return existing;
        }
        EntitySlot slot = slotPool.borrow();
        slot.bind(sbbId, entityId, sbb);
        SbbEntity fresh = new SbbEntity(slot, sbbId, sbb);
        SbbEntity prior = entities.putIfAbsent(sbbId, fresh);
        if (prior != null) {
            slot.unbind();
            slotPool.release(slot);
            return prior;
        }
        return fresh;
    }

    /**
     * Release the entity for {@code sbbId} and return its slot to the idle pool.
     */
    public void release(SbbEntity entity) {
        if (entity == null) {
            return;
        }
        entities.remove(entity.getSbbId(), entity);
        EntitySlot slot = entity.getSlot();
        slot.unbind();
        slotPool.release(slot);
    }

    public void releaseById(String sbbId) {
        SbbEntity entity = entities.remove(sbbId);
        if (entity != null) {
            EntitySlot slot = entity.getSlot();
            slot.unbind();
            slotPool.release(slot);
        }
    }

    public SbbEntity findEntity(String sbbId) {
        if (sbbId == null) {
            return null;
        }
        return entities.get(sbbId);
    }

    public int prewarm(int count) {
        return slotPool.prewarm(count);
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    public int size() {
        return entities.size();
    }

    public int idleSlotCount() {
        return slotPool.idleCount();
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public boolean isShutdown() {
        return shuttingDown || executor.isShutdown();
    }

    public void shutdown() {
        shuttingDown = true;
        for (SbbEntity entity : entities.values()) {
            entity.getSlot().markShutdown();
        }
        slotPool.shutdown();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        entities.clear();
    }

    private static final class NoopSbb implements Sbb { }
}
