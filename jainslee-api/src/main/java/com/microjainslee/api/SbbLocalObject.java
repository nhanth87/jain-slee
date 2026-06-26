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
 * JAIN-SLEE 1.1 §8.5 — SBB Local Object interface.
 * Represents an SBB instance in an Activity Context.
 */
public interface SbbLocalObject {
    Sbb getSbb();
    SbbID getSbbID();

    /**
     * Returns the invocation priority for this SBB entity (0–9 in micro-jainslee).
     */
    int getPriority();

    /**
     * Removes this SBB entity and detaches it from any activity contexts.
     */
    void remove();

    /**
     * Returns {@code true} when this SBB entity has been removed.
     */
    boolean isRemoved();

    /**
     * Runs {@code action} on the owning SBB entity thread when the container
     * supports entity pooling; otherwise runs inline on the caller thread.
     */
    default void invokeLocally(Runnable action) {
        action.run();
    }
}