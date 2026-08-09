/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Gate A — RA-only path into SBB entity checkpoint.
 *
 * <p>SBBs must never call this. Bound to {@code MicroSleeContainer} (or any
 * object exposing {@code checkpointSbbEntity(String)}) via
 * {@link #bindContainer(Object)} / {@link #bindFunction(Function)}.
 */
public final class RaCheckpointBridge {

    private static final Logger LOG = LogManager.getLogger(RaCheckpointBridge.class);

    private final AtomicReference<Function<String, Boolean>> checkpointFn = new AtomicReference<>();
    private final RaHaMetrics metrics;

    public RaCheckpointBridge() {
        this(new RaHaMetrics());
    }

    public RaCheckpointBridge(RaHaMetrics metrics) {
        this.metrics = metrics == null ? new RaHaMetrics() : metrics;
    }

    public RaHaMetrics metrics() {
        return metrics;
    }

    /** Bind a direct function (tests). */
    public void bindFunction(Function<String, Boolean> fn) {
        checkpointFn.set(fn);
    }

    /**
     * Reflective bind to {@code MicroSleeContainer#checkpointSbbEntity(String)}.
     */
    public void bindContainer(Object microSleeContainer) {
        if (microSleeContainer == null) {
            checkpointFn.set(null);
            return;
        }
        checkpointFn.set(sbbId -> invokeCheckpoint(microSleeContainer, sbbId));
    }

    /**
     * @return {@code true} when checkpoint wrote / coalesced; {@code false} when unbound / fail
     */
    public boolean checkpoint(String sbbId) {
        if (sbbId == null || sbbId.isBlank()) {
            metrics.raCheckpointSkip();
            return false;
        }
        Function<String, Boolean> fn = checkpointFn.get();
        if (fn == null) {
            metrics.raCheckpointSkip();
            return false;
        }
        try {
            boolean ok = Boolean.TRUE.equals(fn.apply(sbbId));
            if (ok) {
                metrics.raCheckpointOk();
            } else {
                metrics.raCheckpointSkip();
            }
            return ok;
        } catch (RuntimeException e) {
            LOG.debug("RA checkpoint('{}') failed: {}", sbbId, e.toString());
            metrics.raCheckpointSkip();
            return false;
        }
    }

    private static Boolean invokeCheckpoint(Object container, String sbbId) {
        Objects.requireNonNull(container, "container");
        try {
            Object result = container.getClass()
                    .getMethod("checkpointSbbEntity", String.class)
                    .invoke(container, sbbId);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("checkpointSbbEntity reflective invoke failed", e);
        }
    }
}
