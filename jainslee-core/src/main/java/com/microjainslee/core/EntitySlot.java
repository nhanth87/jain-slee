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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reusable virtual-thread event loop shell. The VT is spawned once and
 * rebound to new SBB instances across session lifecycles.
 */
final class EntitySlot {

    private static final Runnable POISON = new Runnable() {
        @Override public void run() { /* signal exit only */ }
    };

    private static final AtomicLong SLOT_SEQUENCE = new AtomicLong();

    private final long slotId = SLOT_SEQUENCE.incrementAndGet();
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
    private final AtomicBoolean parked = new AtomicBoolean(true);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final Future<?> loopFuture;

    private volatile String externalId;
    private volatile long entityId;
    private volatile Sbb boundSbb;

    EntitySlot(ExecutorService owner) {
        this.loopFuture = owner.submit(new EventLoop());
    }

    long getSlotId() {
        return slotId;
    }

    String getExternalId() {
        return externalId;
    }

    long getEntityId() {
        return entityId;
    }

    Sbb getBoundSbb() {
        return boundSbb;
    }

    boolean isBound() {
        return externalId != null;
    }

    boolean isParked() {
        return parked.get();
    }

    boolean isShutdown() {
        return shutdown.get();
    }

    Future<?> getLoopFuture() {
        return loopFuture;
    }

    BlockingQueue<Runnable> getQueue() {
        return queue;
    }

    void bind(String externalId, long entityId, Sbb sbb) {
        if (externalId == null || sbb == null) {
            throw new IllegalArgumentException("externalId and sbb are required");
        }
        this.externalId = externalId;
        this.entityId = entityId;
        this.boundSbb = sbb;
    }

    void unbind() {
        this.externalId = null;
        this.entityId = 0L;
        this.boundSbb = null;
        queue.clear();
        parked.set(true);
    }

    void submit(Runnable task) {
        if (shutdown.get()) {
            throw new IllegalStateException("Entity slot shut down: " + slotId);
        }
        if (!isBound()) {
            throw new IllegalStateException("Entity slot is not bound: " + slotId);
        }
        queue.offer(task);
    }

    void markShutdown() {
        shutdown.set(true);
        queue.offer(POISON);
    }

    private final class EventLoop implements Runnable {
        @Override
        public void run() {
            try {
                while (!shutdown.get()) {
                    Runnable task;
                    try {
                        task = queue.poll(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (task == null) {
                        parked.set(true);
                        if (queue.isEmpty()) {
                            continue;
                        }
                        parked.set(false);
                        continue;
                    }
                    if (task == POISON) {
                        return;
                    }
                    parked.set(false);
                    try {
                        task.run();
                    } catch (Throwable t) {
                        Sbb sbb = boundSbb;
                        if (sbb != null) {
                            try {
                                sbb.sbbExceptionThrown(
                                        t instanceof Exception ? (Exception) t : new RuntimeException(t),
                                        null, null);
                            } catch (Throwable ignored) {
                                // never let an exception escape the loop
                            }
                        }
                    }
                }
            } finally {
                Runnable remaining;
                while ((remaining = queue.poll()) != null) {
                    if (remaining == POISON) {
                        continue;
                    }
                    try {
                        remaining.run();
                    } catch (Throwable ignored) {
                        // swallow
                    }
                }
            }
        }
    }
}
