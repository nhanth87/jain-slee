/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §13 — internal event fired through the
 * {@code SleeEndpoint.activityEnded} path so attached SBBs receive
 * a uniform end-of-activity signal regardless of the underlying
 * protocol.
 */
public final class ActivityEndedEvent implements SleeEvent {

    private final ActivityHandle handle;

    public ActivityEndedEvent(ActivityHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("handle is required");
        }
        this.handle = handle;
    }

    public ActivityHandle getHandle() {
        return handle;
    }

    @Override
    public String toString() {
        return "ActivityEndedEvent[handle=" + handle.getId() + "]";
    }
}
