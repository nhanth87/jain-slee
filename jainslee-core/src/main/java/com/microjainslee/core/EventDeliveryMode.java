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

/**
 * Controls how {@link EventRouter} hands events to SBB entities.
 */
public enum EventDeliveryMode {
    /** Block the Disruptor worker until the SBB finishes (spec-safe default). */
    SYNC,
    /** Publish to the SBB VT and commit the transaction on that thread (no latch). */
    ASYNC_COMMIT,
    /** Invoke {@code onEvent} on the Disruptor worker thread (tests only). */
    INLINE;

    public static EventDeliveryMode parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return SYNC;
        }
        String normalized = value.trim().toLowerCase().replace('_', '-');
        if ("async-commit".equals(normalized) || "async".equals(normalized)) {
            return ASYNC_COMMIT;
        }
        if ("inline".equals(normalized)) {
            return INLINE;
        }
        return SYNC;
    }
}
