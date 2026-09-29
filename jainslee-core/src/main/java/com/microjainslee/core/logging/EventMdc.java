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

    /**
     * ADR 0007 D8 — the real cluster node id, stamped onto every log line.
     * <p>
     * Previously {@link #KEY_NODE_ID} was always the literal {@code "local"}, so
     * two nodes behind one log ship produced indistinguishable lines — a direct
     * blocker for diagnosing an active/active incident. Set once from the
     * container at start; default keeps the {@code "local"} placeholder for
     * single-JVM.
     */
    private static volatile String nodeId = NODE_ID_LOCAL;

    /** Records the real cluster node id for every MDC-stamped line. */
    public static void setNodeId(String id) {
        nodeId = (id == null || id.isBlank()) ? NODE_ID_LOCAL : id;
    }

    /** @return the node id stamped into MDC lines. */
    public static String nodeId() {
        return nodeId;
    }

    /**
     * ADR 0007 D7 / P4-d — MDC stamping is <b>off by default</b>.
     *
     * <p>
     * {@code start} + {@code setSbbId} + {@code finish} + {@code clear} cost
     * <b>15 {@code ThreadContext} put/remove operations per event delivery</b>,
     * each writing into the thread-local map, and {@code finish} additionally
     * allocates a {@code String} for {@code durationNs}. That was the single
     * most expensive always-on line in the router hot path, paid even when the
     * logger is at INFO and no pattern references these fields.
     *
     * <p>
     * Enable with {@code -Djainslee.mdc.enabled=true}, or let the container
     * enable it automatically when DEBUG is enabled for
     * {@code com.microjainslee.core}. Turned back on, the behaviour is
     * byte-identical to before.
     */
    public static final String PROP_ENABLED = "jainslee.mdc.enabled";

    private static final boolean ENABLED = resolveEnabled();

    private static boolean resolveEnabled() {
        String explicit = System.getProperty(PROP_ENABLED);
        if (explicit != null && !explicit.isBlank()) {
            return Boolean.parseBoolean(explicit);
        }
        // Auto-enable when the router's own logger would actually emit these
        // fields, so debugging needs no extra configuration.
        try {
            org.apache.logging.log4j.Logger l =
                    org.apache.logging.log4j.LogManager.getLogger("com.microjainslee.core");
            org.apache.logging.log4j.Level level = l.getLevel();
            return level != null && level.isMoreSpecificThan(org.apache.logging.log4j.Level.INFO)
                    && level.intLevel() <= org.apache.logging.log4j.Level.TRACE.intLevel();
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** @return whether MDC stamping happens at all. */
    public static boolean isEnabled() {
        return ENABLED;
    }

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
        if (!ENABLED) {
            return;
        }
        ThreadContext.put(KEY_SBB_ID, sbbId == null ? "?" : sbbId);
        ThreadContext.put(KEY_ACI_NAME, aciName == null ? "?" : aciName);
        ThreadContext.put(KEY_EVENT_TYPE, eventType == null ? "?" : eventType);
        // durationNs is intentionally absent here — finish() sets it.
        // txStatus is set by finish() so we don't pre-commit the field.
        ThreadContext.put(KEY_TX_STATUS, "PENDING");
        ThreadContext.put(KEY_NODE_ID, nodeId);
    }

    /**
     * Overwrite the SBB identifier (called once we know which SBB is being
     * dispatched — i.e. inside the per-SBB loop in the EventRouter). This
     * keeps the field accurate when multiple SBBs are attached to the same
     * activity context: each line of work can be correlated with the SBB
     * that emitted it.
     */
    public static void setSbbId(String sbbId) {
        if (!ENABLED) {
            return;
        }
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
        if (!ENABLED) {
            return;
        }
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
        if (!ENABLED) {
            return;
        }
        ThreadContext.remove(KEY_SBB_ID);
        ThreadContext.remove(KEY_ACI_NAME);
        ThreadContext.remove(KEY_EVENT_TYPE);
        ThreadContext.remove(KEY_DURATION_NS);
        ThreadContext.remove(KEY_TX_STATUS);
        ThreadContext.remove(KEY_NODE_ID);
    }
}