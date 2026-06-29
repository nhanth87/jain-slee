/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.ordering;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.microjainslee.api.SequencedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sprint S8 - per-convergence bounded FIFO buffer for out-of-order
 * {@link SequencedEvent}s.
 *
 * <h2>Problem (GAP-SR-3)</h2>
 * <p>In a real-world USSD flow the upstream network may deliver a
 * non-initial event (e.g. a follow-up {@code GrpcMenuRequestEvent})
 * <em>before</em> the initial {@code Ss7UssdBeginEvent} has been
 * processed by IES. Without buffering, the event would be dropped
 * because no SBB entity exists yet for the convergence key. The
 * buffer holds the event until the entity is allocated, then drains
 * it FIFO.</p>
 *
 * <h2>Capacity &amp; TTL</h2>
 * <ul>
 *   <li>Per-convergence capacity cap (default {@value #DEFAULT_CAPACITY}).
 *       When the buffer is full, the oldest entry is dropped with a
 *       WARN log - preventing unbounded memory growth under a
 *       run-away producer.</li>
 *   <li>TTL eviction: a daemon {@link ScheduledExecutorService}
 *       sweeps every {@code sweepIntervalMs} ms (default 30 s) and
 *       removes entries older than {@code windowMs} (default
 *       {@value #DEFAULT_WINDOW_MS} ms). Convergences whose deque
 *       empties out are removed from the index.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>The index is a {@link ConcurrentHashMap}; each per-convergence
 * {@link ArrayDeque} is guarded by its own intrinsic monitor. The
 * index is only mutated under the deque's lock when removing an
 * emptied deque.</p>
 *
 * @author Tran Nhan (nhanth87)
 */
public final class OutOfOrderBuffer {

    private static final Logger LOG = LogManager.getLogger(OutOfOrderBuffer.class);

    /** Default per-convergence capacity (events). */
    public static final int DEFAULT_CAPACITY = 1024;

    /** Default TTL window (ms). */
    public static final long DEFAULT_WINDOW_MS = 60_000L;

    /** Default evictor sweep interval (ms). */
    public static final long DEFAULT_SWEEP_INTERVAL_MS = 30_000L;

    /** A buffered event with its receiving timestamp. */
    public record BufferedEvent(SequencedEvent event, Object activityContext, long receivedAtMs) {
        public BufferedEvent {
            if (event == null) throw new IllegalArgumentException("event is required");
            if (activityContext == null) throw new IllegalArgumentException("activityContext is required");
            if (receivedAtMs <= 0L) throw new IllegalArgumentException("receivedAtMs must be positive");
        }
    }

    private final int perConvergenceCapacity;
    private final long windowMs;
    private final long sweepIntervalMs;

    /** Index: convergenceName -> deque guarded by its intrinsic monitor. */
    private final ConcurrentHashMap<String, Deque<BufferedEvent>> buffers =
            new ConcurrentHashMap<>();

    /** Per-convergence highest sequence number seen so far. */
    private final ConcurrentHashMap<String, Long> nextExpectedSeq =
            new ConcurrentHashMap<>();

    /** Total events currently buffered (sum across all convergences). */
    private final java.util.concurrent.atomic.AtomicLong bufferedCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** Total events dropped due to capacity overflow (monotonic). */
    private final java.util.concurrent.atomic.AtomicLong overflowDrops =
            new java.util.concurrent.atomic.AtomicLong();

    /** Single-thread daemon executor for TTL eviction. */
    private final ScheduledExecutorService evictorExecutor;

    /** Build with default capacity (1024) and TTL (60s). */
    public OutOfOrderBuffer() {
        this(DEFAULT_CAPACITY, DEFAULT_WINDOW_MS, DEFAULT_SWEEP_INTERVAL_MS);
    }

    /** Build with custom capacity and TTL. Sweep is clamped to [1s, 30s]. */
    public OutOfOrderBuffer(int perConvergenceCapacity, long windowMs) {
        this(perConvergenceCapacity, windowMs, clampSweep(windowMs / 2));
    }

    /** Full constructor - primarily for tests that want a tight sweep interval. */
    public OutOfOrderBuffer(int perConvergenceCapacity, long windowMs, long sweepIntervalMs) {
        if (perConvergenceCapacity <= 0) {
            throw new IllegalArgumentException("perConvergenceCapacity must be > 0");
        }
        if (windowMs <= 0L) {
            throw new IllegalArgumentException("windowMs must be > 0");
        }
        if (sweepIntervalMs <= 0L) {
            throw new IllegalArgumentException("sweepIntervalMs must be > 0");
        }
        this.perConvergenceCapacity = perConvergenceCapacity;
        this.windowMs = windowMs;
        this.sweepIntervalMs = sweepIntervalMs;
        Thread.UncaughtExceptionHandler ueh = (t, ex) ->
                LOG.warn("OutOfOrderBuffer evictor threw: {}", ex.getMessage(), ex);
        this.evictorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofPlatform()
                    .name("jainslee-oob-evictor")
                    .daemon(true)
                    .uncaughtExceptionHandler(ueh)
                    .unstarted(r);
            return t;
        });
        this.evictorExecutor.scheduleAtFixedRate(
                this::evictExpiredSafely,
                sweepIntervalMs, sweepIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("OutOfOrderBuffer started: capacity={} windowMs={} sweepMs={}",
                perConvergenceCapacity, windowMs, sweepIntervalMs);
    }

    private static long clampSweep(long candidate) {
        if (candidate < 1_000L) return 1_000L;
        if (candidate > 30_000L) return 30_000L;
        return candidate;
    }

    // Producer API

    /**
     * Buffer an out-of-order event for its {@link SequencedEvent#getConvergenceName()}.
     *
     * <p>If the buffer for this convergence is already at
     * {@link #perConvergenceCapacity}, the <em>oldest</em> entry is
     * evicted (FIFO overflow) and the counter {@link #getOverflowDrops()}
     * is incremented.</p>
     *
     * @return {@code true} if the event was buffered; {@code false}
     *         when {@code event} is null or missing a convergence key
     *         or activity context.
     */
    public boolean enqueue(SequencedEvent event, Object activityContext) {
        if (event == null) return false;
        String key = event.getConvergenceName();
        if (key == null || key.isEmpty()) {
            LOG.warn("OutOfOrderBuffer.enqueue: missing convergence on {}", event);
            return false;
        }
        if (activityContext == null) {
            LOG.warn("OutOfOrderBuffer.enqueue: missing activityContext on {}", event);
            return false;
        }
        long nowMs = System.currentTimeMillis();
        BufferedEvent be = new BufferedEvent(event, activityContext, nowMs);

        Deque<BufferedEvent> deque = buffers.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= perConvergenceCapacity) {
                BufferedEvent dropped = deque.pollFirst();
                if (dropped != null) {
                    bufferedCount.decrementAndGet();
                    overflowDrops.incrementAndGet();
                    LOG.warn("OutOfOrderBuffer overflow: convergence={} dropped seq={} (capacity={})",
                            key, dropped.event().getSequenceNumber(), perConvergenceCapacity);
                }
            }
            deque.addLast(be);
            bufferedCount.incrementAndGet();
            long seq = event.getSequenceNumber();
            nextExpectedSeq.merge(key, seq, Math::max);
        }
        LOG.debug("OutOfOrderBuffer.enqueue: convergence={} seq={} size={}",
                key, event.getSequenceNumber(), deque.size());
        return true;
    }

    // Consumer API - drain once entity is allocated

    /**
     * Drain up to {@code maxItems} events whose sequence number is
     * {@code <= currentExpectedSeq} for the given convergence, in FIFO
     * order. Events with a higher sequence stay buffered.
     *
     * <p>If {@code maxItems <= 0} the method drains everything that
     * satisfies the sequence predicate.</p>
     *
     * <p>The internal {@code nextExpectedSeq} counter is advanced to
     * the highest sequence number seen across the drained batch.</p>
     */
    public List<BufferedEvent> drainReady(String convergenceKey,
                                           long currentExpectedSeq,
                                           int maxItems) {
        if (convergenceKey == null) return List.of();
        Deque<BufferedEvent> deque = buffers.get(convergenceKey);
        if (deque == null) return List.of();

        List<BufferedEvent> out = new ArrayList<>();
        synchronized (deque) {
            while (!deque.isEmpty()) {
                BufferedEvent head = deque.peekFirst();
                long headSeq = head.event().getSequenceNumber();
                if (headSeq <= currentExpectedSeq) {
                    deque.pollFirst();
                    bufferedCount.decrementAndGet();
                    out.add(head);
                    if (maxItems > 0 && out.size() >= maxItems) break;
                } else {
                    // FIFO-ordered: cannot drain past the gap.
                    break;
                }
            }
            if (deque.isEmpty()) {
                buffers.remove(convergenceKey, deque);
            }
        }
        if (!out.isEmpty()) {
            long maxDrained = out.get(out.size() - 1).event().getSequenceNumber();
            nextExpectedSeq.merge(convergenceKey, maxDrained, Math::max);
            LOG.debug("OutOfOrderBuffer.drainReady: convergence={} expected={} drained={}",
                    convergenceKey, currentExpectedSeq, out.size());
        }
        return out;
    }

    /**
     * Drain <em>all</em> buffered events for {@code convergenceKey}
     * regardless of sequence (used at session-end / activity-ended).
     */
    public List<BufferedEvent> drainAll(String convergenceKey) {
        if (convergenceKey == null) return List.of();
        Deque<BufferedEvent> deque = buffers.remove(convergenceKey);
        if (deque == null) return List.of();
        List<BufferedEvent> out;
        synchronized (deque) {
            out = new ArrayList<>(deque);
            deque.clear();
        }
        bufferedCount.addAndGet(-out.size());
        return out;
    }

    /**
     * Returns the highest sequence number seen so far for
     * {@code convergenceKey}. Returns {@code 0} when the convergence
     * has never been seen (caller should treat 0 as "expect seq 1").
     */
    public long getNextExpectedSeq(String convergenceKey) {
        if (convergenceKey == null) return 0L;
        Long v = nextExpectedSeq.get(convergenceKey);
        return v == null ? 0L : v;
    }

    // Eviction and lifecycle

    /**
     * Evict all entries older than {@link #windowMs} across every
     * convergence. Safe to call manually from tests; the scheduled
     * executor calls it on a fixed cadence.
     *
     * @return total number of entries removed by this sweep.
     */
    public int evictExpired() {
        long cutoff = System.currentTimeMillis() - windowMs;
        int totalDropped = 0;
        for (Map.Entry<String, Deque<BufferedEvent>> e : buffers.entrySet()) {
            Deque<BufferedEvent> deque = e.getValue();
            int dropped = 0;
            synchronized (deque) {
                while (!deque.isEmpty() && deque.peekFirst().receivedAtMs() < cutoff) {
                    deque.pollFirst();
                    dropped++;
                }
                if (deque.isEmpty()) {
                    buffers.remove(e.getKey(), deque);
                }
            }
            bufferedCount.addAndGet(-dropped);
            totalDropped += dropped;
        }
        if (totalDropped > 0) {
            LOG.debug("OutOfOrderBuffer.evictExpired: dropped={} cutoff={}", totalDropped, cutoff);
        }
        return totalDropped;
    }

    private void evictExpiredSafely() {
        try {
            evictExpired();
        } catch (Throwable t) {
            LOG.warn("OutOfOrderBuffer evictExpired failed: {}", t.getMessage(), t);
        }
    }

    /** Cancel the background evictor. Idempotent. */
    public void shutdown() {
        evictorExecutor.shutdownNow();
    }

    // Diagnostics

    /** Number of distinct convergences currently buffered. */
    public int bufferedConvergenceCount() {
        return buffers.size();
    }

    /** Total events buffered across all convergences (approximate). */
    public long bufferedEventCount() {
        return bufferedCount.get();
    }

    /** Cumulative overflow-drop count (monotonic). */
    public long getOverflowDrops() {
        return overflowDrops.get();
    }

    /** Configured per-convergence capacity. */
    public int getPerConvergenceCapacity() {
        return perConvergenceCapacity;
    }

    /** Configured TTL window (ms). */
    public long getWindowMs() {
        return windowMs;
    }

    /** Snapshot the current buffer depth for {@code convergenceKey} (0 if none). */
    public int depthFor(String convergenceKey) {
        if (convergenceKey == null) return 0;
        Deque<BufferedEvent> deque = buffers.get(convergenceKey);
        if (deque == null) return 0;
        synchronized (deque) {
            return deque.size();
        }
    }
}
