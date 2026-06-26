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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * JAIN-SLEE 1.1 §8.2 — Per-SBB Virtual Thread Entity Pool.
 *
 * <p>Each registered SBB ID owns one dedicated Java virtual thread that
 * sequentially drains an internal {@link BlockingQueue} of {@link Runnable}s.
 * Every event handed to the SBB (whether dispatched by {@link EventRouter} or
 * submitted by a test) is enqueued onto that thread, giving the SBB
 * single-threaded event ordering as required by the JAIN-SLEE spec.
 *
 * <p>Java 8 source/target compatibility is preserved by going through
 * {@link MicroSleeExecutors#newVirtualThreadPerTaskExecutor()} which uses
 * reflection to invoke {@code Executors.newVirtualThreadPerTaskExecutor()}
 * when running on Java 21+; on older JVMs the pool silently falls back to
 * cached platform threads.
 */
public final class VirtualThreadSbbEntityPool {

    /** Per-SBB entity owning one parked virtual thread + its work queue. */
    public static final class SbbEntity {
        private final String sbbId;
        private final Sbb sbb;
        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>();
        private final AtomicBoolean parked = new AtomicBoolean(true);
        private final AtomicBoolean shutdown = new AtomicBoolean(false);
        private final Future<?> loopFuture;

        SbbEntity(String sbbId, Sbb sbb, ExecutorService owner) {
            this.sbbId = sbbId;
            this.sbb = sbb;
            this.loopFuture = owner.submit(new EventLoop());
        }

        /** Returns the SBB ID this entity was created for. */
        public String getSbbId() { return sbbId; }

        /** Returns the wrapped SBB instance (always the same singleton). */
        public Sbb getSbb() { return sbb; }

        /** Enqueue a Runnable to execute on this entity's owning virtual thread. */
        public void submit(Runnable task) {
            if (shutdown.get()) {
                throw new IllegalStateException("SBB entity has been shut down: " + sbbId);
            }
            queue.offer(task);
        }

        boolean isParked() { return parked.get(); }
        boolean isShutdown() { return shutdown.get(); }

        Future<?> getLoopFuture() { return loopFuture; }

        BlockingQueue<Runnable> getQueue() { return queue; }

        void markShutdown() { shutdown.set(true); }

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
                            // Re-check after marking parked: any racing submit()
                            // may have just appended; otherwise we just idle.
                            if (queue.isEmpty()) {
                                continue;
                            }
                            parked.set(false);
                            continue;
                        }
                        parked.set(false);
                        try {
                            task.run();
                        } catch (Throwable t) {
                            try {
                                sbb.sbbExceptionThrown(
                                        t instanceof Exception ? (Exception) t : new RuntimeException(t),
                                        null, null);
                            } catch (Throwable ignored) {
                                // never let an exception escape the loop
                            }
                        }
                    }
                } finally {
                    // drain any final tasks before the virtual thread ends
                    Runnable remaining;
                    while ((remaining = queue.poll()) != null) {
                        try { remaining.run(); } catch (Throwable ignored) { /* swallow */ }
                    }
                }
            }
        }
    }

    private final ConcurrentHashMap<String, SbbEntity> entities = new ConcurrentHashMap<String, SbbEntity>();
    private final int min;
    private final int max;
    private final boolean perVirtualThread;
    private final ExecutorService executor;
    private final AtomicInteger prewarmed = new AtomicInteger();
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
        this.perVirtualThread = perVirtualThread;
        ExecutorService chosen = null;
        if (perVirtualThread) {
            chosen = MicroSleeExecutors.newVirtualThreadPerTaskExecutor();
        }
        if (chosen == null) {
            chosen = java.util.concurrent.Executors.newCachedThreadPool();
        }
        this.executor = chosen;
    }

    /**
     * Acquire (or create) the {@link SbbEntity} for the given SBB ID. The first
     * call for an ID invokes the {@code factory} to materialize the SBB,
     * attaches the parked virtual thread, and remembers the entity. Subsequent
     * calls return the same entity — the spec mandates one logical entity per
     * SBB ID, so all events for that ID flow through the same virtual thread.
     */
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
        SbbEntity fresh = new SbbEntity(sbbId, factory.get(), executor);
        SbbEntity prior = entities.putIfAbsent(sbbId, fresh);
        if (prior != null) {
            // Another thread raced us — cancel our freshly-created one and reuse.
            fresh.markShutdown();
            fresh.getQueue().clear();
            fresh.getLoopFuture().cancel(true);
            return prior;
        }
        return fresh;
    }

    /**
     * Mark the entity as available again. With the per-ID model this is
     * essentially a no-op kept for backward compatibility; the same entity is
     * always returned by subsequent {@link #acquire(String, Supplier)} calls.
     */
    public void release(SbbEntity entity) {
        if (entity == null) return;
        entity.parked.set(false);
    }

    /**
     * Look up an existing {@link SbbEntity} by SBB ID. Returns {@code null}
     * when the ID has never been {@link #acquire(String, Supplier) acquired}.
     * Used by {@link EventRouter} to dispatch onto the owning virtual thread.
     */
    public SbbEntity findEntity(String sbbId) {
        if (sbbId == null) return null;
        return entities.get(sbbId);
    }

    /**
     * Pre-warm {@code count} parked virtual threads, capped by {@link #max}.
     * Returns the actual number pre-warmed.
     */
    public int prewarm(int count) {
        if (count <= 0) return 0;
        int target = Math.min(count, max);
        int warmed = 0;
        while (warmed < target && prewarmed.get() < max) {
            final int idx = prewarmed.getAndIncrement();
            final String id = "__prewarm__-" + Integer.toHexString(idx);
            if (entities.containsKey(id)) continue;
            SbbEntity e = new SbbEntity(id, new NoopSbb(), executor);
            if (entities.putIfAbsent(id, e) == null) {
                warmed++;
            } else {
                e.markShutdown();
                e.getQueue().clear();
                e.getLoopFuture().cancel(true);
            }
        }
        return warmed;
    }

    /** Returns the configured pre-warm size. */
    public int getMin() { return min; }

    /** Returns the configured maximum pool size. */
    public int getMax() { return max; }

    /** Returns the number of currently tracked entities. */
    public int size() { return entities.size(); }

    /** Returns the underlying executor (VT-backed when Java 21+ is available). */
    public ExecutorService getExecutor() { return executor; }

    /**
     * Returns {@code true} once {@link #shutdown()} has been invoked. Used by
     * {@link MicroSleeContainer} to detect a dead pool after a stop and rebuild
     * a fresh one when the container is restarted, so the stop/start round-trip
     * leaves the pool usable.
     */
    public boolean isShutdown() {
        return shuttingDown || executor.isShutdown();
    }

    /** Drain queues, signal loops to exit, and shut the executor down. */
    public void shutdown() {
        shuttingDown = true;
        for (SbbEntity entity : entities.values()) {
            entity.markShutdown();
            entity.getQueue().offer(POISON);
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (SbbEntity entity : entities.values()) {
            try {
                entity.getLoopFuture().get(1, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    private static final Runnable POISON = new Runnable() {
        @Override public void run() { /* signal exit only */ }
    };

    private static final class NoopSbb implements Sbb { }
}
