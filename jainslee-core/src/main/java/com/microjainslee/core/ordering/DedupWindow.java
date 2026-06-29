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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sprint S8 - sliding deduplication window keyed by
 * {@code (convergenceName, sequenceNumber)}.
 *
 * <h2>Problem (GAP-SR-4)</h2>
 * <p>At-least-once transports (gRPC, SS7 over SCTP, HTTP retry)
 * may deliver the same logical event multiple times. Without a
 * dedup window, an SBB would re-process {@code GrpcMenuRequest}
 * twice and emit duplicate downstream side effects.</p>
 *
 * <h2>Algorithm</h2>
 * <p>Backed by an access-order {@link LinkedHashMap} (LRU) capped at
 * {@link #DEFAULT_MAX_ENTRIES} entries. On
 * {@link #isDuplicate(String, long, long)}:</p>
 * <ol>
 *   <li>If the key is present and {@code expiry > nowMs} -&gt;
 *       return {@code true} (duplicate; caller drops the event).</li>
 *   <li>Otherwise insert / refresh the entry with
 *       {@code expiry = nowMs + windowMs} and return {@code false}.</li>
 * </ol>
 *
 * <p>{@link #evictExpired(long)} walks the map once and removes any
 * entries whose expiry has passed. The caller (typically a scheduled
 * task or the {@link OutOfOrderBuffer} evictor) is responsible for
 * invoking it.</p>
 *
 * <h2>Thread-safety</h2>
 * <p>The LRU map is wrapped in {@link Collections#synchronizedMap}.
 * All mutating ops go through that wrapper; the iteration in
 * {@link #evictExpired(long)} is explicitly synchronized on the
 * underlying map.</p>
 *
 * @author Tran Nhan (nhanth87)
 */
public final class DedupWindow {

    private static final Logger LOG = LogManager.getLogger(DedupWindow.class);

    /** Default LRU capacity. 65 536 entries ~= 1 MB of overhead. */
    public static final int DEFAULT_MAX_ENTRIES = 65_536;

    /** Default window length (ms) - 5 minutes. */
    public static final long DEFAULT_WINDOW_MS = 300_000L;

    /** Composite key. */
    private record DedupKey(String convergence, long seqNum) { }

    private final long windowMs;
    private final int maxEntries;

    /** LRU map: key -&gt; expiry wall-clock (ms). */
    private final Map<DedupKey, Long> seen;

    /** Total duplicate hits observed (monotonic). */
    private final java.util.concurrent.atomic.AtomicLong dedupHitCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** Total distinct entries admitted (monotonic). */
    private final java.util.concurrent.atomic.AtomicLong admitCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** Build with default capacity (65 536) and 5-minute window. */
    public DedupWindow() {
        this(DEFAULT_WINDOW_MS, DEFAULT_MAX_ENTRIES);
    }

    /** Build with custom window length and LRU cap. */
    public DedupWindow(long windowMs, int maxEntries) {
        if (windowMs <= 0L) {
            throw new IllegalArgumentException("windowMs must be > 0");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0");
        }
        this.windowMs = windowMs;
        this.maxEntries = maxEntries;
        final int cap = maxEntries;
        this.seen = Collections.synchronizedMap(
                new LinkedHashMap<DedupKey, Long>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<DedupKey, Long> e) {
                        return size() > cap;
                    }
                });
    }

    // Core API

    /**
     * Returns {@code true} if {@code (convergence, seqNum)} was seen
     * inside the current dedup window relative to {@code nowMs}.
     * Returns {@code false} if it is new (and records the entry).
     *
     * <p>The {@code nowMs} parameter is explicit so tests can drive the
     * clock deterministically.</p>
     */
    public boolean isDuplicate(String convergence, long seqNum, long nowMs) {
        if (convergence == null || convergence.isEmpty()) {
            return false; // cannot dedup without a key
        }
        if (seqNum <= 0L) {
            return false; // never treat non-positive seqs as duplicates
        }
        DedupKey key = new DedupKey(convergence, seqNum);
        Long expiry;
        synchronized (seen) {
            expiry = seen.get(key);
            if (expiry != null && expiry > nowMs) {
                dedupHitCount.incrementAndGet();
                LOG.debug("DedupWindow hit: convergence={} seq={} expiry={} now={}",
                        convergence, seqNum, expiry, nowMs);
                return true;
            }
            seen.put(key, nowMs + windowMs);
        }
        admitCount.incrementAndGet();
        return false;
    }

    /**
     * Walk the map and drop any entries whose expiry has passed.
     * Caller supplies {@code nowMs} for deterministic tests.
     *
     * @return number of entries removed.
     */
    public int evictExpired(long nowMs) {
        int removed = 0;
        synchronized (seen) {
            var it = seen.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue() <= nowMs) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * Remove all entries associated with {@code convergence}. Used at
     * session / activity end to free memory promptly.
     */
    public int purgeConvergence(String convergence) {
        if (convergence == null) return 0;
        int removed = 0;
        synchronized (seen) {
            var it = seen.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getKey().convergence.equals(convergence)) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    // Diagnostics

    /** Total duplicate hits since construction (monotonic). */
    public long getDedupHitCount() {
        return dedupHitCount.get();
    }

    /** Total distinct entries admitted since construction (monotonic). */
    public long getAdmitCount() {
        return admitCount.get();
    }

    /** Current LRU size. */
    public int size() {
        return seen.size();
    }

    /** Configured window length (ms). */
    public long getWindowMs() {
        return windowMs;
    }

    /** Configured LRU cap. */
    public int getMaxEntries() {
        return maxEntries;
    }

    /** Reset all state - primarily for tests. */
    public void clear() {
        synchronized (seen) {
            seen.clear();
        }
        dedupHitCount.set(0L);
        admitCount.set(0L);
    }
}
