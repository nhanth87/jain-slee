/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.logging;

import org.apache.logging.log4j.ThreadContext;

/**
 * P1.3 — Structured logging utility that populates log4j2's {@link ThreadContext}
 * (the log4j2 equivalent of SLF4J's MDC) with per-event-delivery fields.
 *
 * <p>Callers wrap one event delivery with a {@code try { ... } finally { ... }}
 * block, calling {@link #start(String, String, String)} before the work and
 * {@link #finish(long, String)} in the {@code finally} clause. The
 * {@code finish} method also computes the elapsed nanoseconds between
 * {@code startNanos} and now, so the {@code durationNs} field is always
 * emitted on the closing line, and {@link #clear()} is invoked so a
 * pooled worker thread never leaks fields into the next event.
 *
 * <p>Fields exposed (per docs/micro-jainslee-production-roadmap.md §5.3):
 * <ul>
 *   <li>{@code sbbId} — owning SBB's identifier (last one invoked; populated
 *       lazily when the first SBB is dispatched inside the transaction).</li>
 *   <li>{@code aciName} — activity context's name.</li>
 *   <li>{@code eventType} — event class simple name.</li>
 *   <li>{@code durationNs} — elapsed wall-clock nanoseconds between
 *       {@code start} and {@code finish}.</li>
 *   <li>{@code txStatus} — {@code "COMMITTED"} or {@code "ROLLED_BACK"}.</li>
 *   <li>{@code nodeId} — placeholder for the eventual cluster node id; for
 *       P1 we always set the literal string {@code "local"}.</li>
 * </ul>
 */
public final class EventMdc {

    /** MDC key — SBB identifier (last SBB invoked in this dispatch). */
    public static final String KEY_SBB_ID = "sbbId";

    /** MDC key — Activity Context Interface name. */
    public static final String KEY_ACI_NAME = "aciName";

    /** MDC key — Event class simple name. */
    public static final String KEY_EVENT_TYPE = "eventType";

    /** MDC key — Elapsed nanoseconds between {@link #start} and {@link #finish}. */
    public static final String KEY_DURATION_NS = "durationNs";

    /** MDC key — Transaction commit/rollback status. */
    public static final String KEY_TX_STATUS = "txStatus";

    /** MDC key — Cluster node identifier (placeholder for P2). */
    public static final String KEY_NODE_ID = "nodeId";

    /** Placeholder cluster node identifier until P2 ClusterManager lands. */
    public static final String NODE_ID_LOCAL = "local";

    private EventMdc() {
        // no instances — utility class
    }

    /**
     * Populate the ThreadContext fields that are known at dispatch entry.
     * {@code durationNs} is intentionally not set here; it is computed in
     * {@link #finish(long, String)} once the work has elapsed.
     *
     * @param sbbId     owning SBB identifier, or {@code "?"} if not yet bound.
     * @param aciName   activity context name (never {@code null}).
     * @param eventType event class simple name (never {@code null}).
     */
    public static void start(String sbbId, String aciName, String eventType) {
        ThreadContext.put(KEY_SBB_ID, sbbId == null ? "?" : sbbId);
        ThreadContext.put(KEY_ACI_NAME, aciName == null ? "?" : aciName);
        ThreadContext.put(KEY_EVENT_TYPE, eventType == null ? "?" : eventType);
        // durationNs is intentionally absent here — finish() sets it.
        // txStatus is set by finish() so we don't pre-commit the field.
        ThreadContext.put(KEY_TX_STATUS, "PENDING");
        ThreadContext.put(KEY_NODE_ID, NODE_ID_LOCAL);
    }

    /**
     * Overwrite the SBB identifier (called once we know which SBB is being
     * dispatched — i.e. inside the per-SBB loop in the EventRouter). This
     * keeps the field accurate when multiple SBBs are attached to the same
     * activity context: each line of work can be correlated with the SBB
     * that emitted it.
     */
    public static void setSbbId(String sbbId) {
        ThreadContext.put(KEY_SBB_ID, sbbId == null ? "?" : sbbId);
    }

    /**
     * Compute the elapsed duration since {@code startNanos} and stamp the
     * transaction status. Intended to be called from a {@code finally} block
     * so the fields are emitted even when an exception bubbles out of the
     * dispatch path. The fields remain in the ThreadContext after this call
     * so the closing log line — if any — carries them; callers should invoke
     * {@link #clear()} as soon as the work that needed the MDC is done.
     *
     * @param startNanos {@link System#nanoTime()} value captured at
     *                   {@link #start} entry.
     * @param txStatus   {@code "COMMITTED"} or {@code "ROLLED_BACK"} (or any
     *                   other descriptive label — no enum is enforced).
     */
    public static void finish(long startNanos, String txStatus) {
        long elapsedNs = System.nanoTime() - startNanos;
        ThreadContext.put(KEY_DURATION_NS, Long.toString(elapsedNs));
        ThreadContext.put(KEY_TX_STATUS, txStatus == null ? "UNKNOWN" : txStatus);
    }

    /**
     * Remove every MDC field this class ever set. Safe to call multiple
     * times. Must be called once the worker thread is finished with the
     * event so the values do not bleed into the next event dispatched on
     * the same pooled/virtual thread.
     */
    public static void clear() {
        ThreadContext.remove(KEY_SBB_ID);
        ThreadContext.remove(KEY_ACI_NAME);
        ThreadContext.remove(KEY_EVENT_TYPE);
        ThreadContext.remove(KEY_DURATION_NS);
        ThreadContext.remove(KEY_TX_STATUS);
        ThreadContext.remove(KEY_NODE_ID);
    }
}