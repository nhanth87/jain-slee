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

import org.jctools.queues.MpmcArrayQueue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of {@link EntitySlot} instances with long-lived virtual threads.
 */
final class EntitySlotPool {

    private final MpmcArrayQueue<EntitySlot> idle;
    private final ExecutorService executor;
    private final int max;
    private final AtomicInteger created = new AtomicInteger();

    EntitySlotPool(int min, int max, ExecutorService executor) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor is required");
        }
        this.max = max;
        this.executor = executor;
        int capacity = Math.max(2, nextPowerOfTwo(max));
        this.idle = new MpmcArrayQueue<EntitySlot>(capacity);
    }

    EntitySlot borrow() {
        EntitySlot pooled = idle.poll();
        if (pooled != null) {
            return pooled;
        }
        if (created.get() < max) {
            if (created.incrementAndGet() <= max) {
                return new EntitySlot(executor);
            }
            created.decrementAndGet();
        }
        EntitySlot extra = new EntitySlot(executor);
        created.incrementAndGet();
        return extra;
    }

    void release(EntitySlot slot) {
        if (slot == null || slot.isShutdown()) {
            return;
        }
        slot.unbind();
        if (!idle.offer(slot)) {
            slot.markShutdown();
            created.decrementAndGet();
        }
    }

    int prewarm(int count) {
        if (count <= 0) {
            return 0;
        }
        int target = Math.min(count, max);
        int warmed = 0;
        while (warmed < target && created.get() < max) {
            if (created.incrementAndGet() <= max) {
                EntitySlot slot = new EntitySlot(executor);
                if (idle.offer(slot)) {
                    warmed++;
                } else {
                    slot.markShutdown();
                    created.decrementAndGet();
                    break;
                }
            } else {
                created.decrementAndGet();
                break;
            }
        }
        return warmed;
    }

    int idleCount() {
        return idle.size();
    }

    int createdCount() {
        return created.get();
    }

    int getMax() {
        return max;
    }

    void shutdown() {
        EntitySlot slot;
        while ((slot = idle.poll()) != null) {
            slot.markShutdown();
        }
    }

    private static int nextPowerOfTwo(int v) {
        if (v <= 1) {
            return 2;
        }
        return Integer.highestOneBit(v - 1) << 1;
    }
}
