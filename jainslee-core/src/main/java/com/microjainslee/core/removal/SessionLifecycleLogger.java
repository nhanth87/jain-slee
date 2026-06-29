/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.removal;

import java.util.function.Consumer;

/**
 * Sprint S6 - structured lifecycle subscriber that turns every
 * {@link EntityRemovalEvent} into a single line on the kernel log so
 * operators have a chronological audit trail of session creation,
 * activity, and disposal.
 *
 * <p>This class intentionally avoids a {@code Logger} field - the
 * {@code com.microjainslee.core.removal} package is built without a
 * log4j dependency on purpose so that {@link EntityRemovalBus} and its
 * subscribers can never accidentally introduce a circular class-init
 * edge through the kernel. We delegate the actual write to
 * {@code java.util.logging} (JUL) which is part of the JRE itself; the
 * embedder can route JUL to log4j via the standard
 * {@code -Djava.util.logging.configFile} hook when needed.
 *
 * <p>The subscriber is created lazily by
 * {@code MicroSleeContainer.start()} and registered on the bus exactly
 * once per start cycle; {@code MicroSleeContainer.stop()} unsubscribes
 * it so a stop/start round-trip never leaks listeners.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class SessionLifecycleLogger implements Consumer<EntityRemovalEvent> {

    private static final java.util.logging.Logger JUL =
            java.util.logging.Logger.getLogger("microjainslee.session.lifecycle");

    /**
     * Number of events this logger has consumed since it was constructed.
     * Monotonic, never reset; useful for tests and the
     * {@code MicroSleeContainer.getEntityRemovalCount()} metric.
     */
    private final java.util.concurrent.atomic.AtomicLong observedCount =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public void accept(EntityRemovalEvent event) {
        if (event == null) {
            return;
        }
        observedCount.incrementAndGet();
        // Single-line, fixed-width, machine-greppable. We avoid building a
        // complex object[] for the formatter to keep the hot path cheap -
        // entity removal is not on the per-event critical path but the bus
        // subscriber contract states "MUST be fast (< 1 us)".
        StringBuilder sb = new StringBuilder(128);
        sb.append("[session-lifecycle] removed entityId=").append(event.entityId());
        sb.append(" reason=").append(event.reason());
        if (event.convergenceKey() != null) {
            sb.append(" convergence=").append(event.convergenceKey());
        }
        sb.append(" tMs=").append(event.timestampMs());
        JUL.info(sb.toString());
    }

    /** Total events observed by this subscriber instance. */
    public long observedCount() {
        return observedCount.get();
    }
}
