/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import java.io.Serializable;
import java.util.Objects;

/**
 * ADR 0007 D6 — replicated absolute timer deadlines.
 *
 * <p>A {@code T}-timer armed on a dead node dies with it: the jSS7 wheel lives
 * on one heap and nothing is ever written anywhere ({@code TimerRecord} already
 * carries {@code nodeId}, but nobody stores it). This store replicates the
 * <b>absolute deadline</b> (epoch ms, never {@code nanoTime} — meaningless
 * off-JVM), never the timer handle. Every node arms a local timer; the node
 * that fires checks the dialog lease first, so only the owner delivers the
 * {@code TimerFiredEvent}.
 *
 * <p>Core ships the interface plus an in-memory implementation. The cluster
 * module provides the replicated one ({@code ClusterTimerDeadlineStore} on
 * cache {@code slee-timer-deadlines}); the bridge is agnostic.
 */
public interface TimerDeadlineStore {

    /** Portable deadline record — allow-list clean ({@code com.microjainslee.*}). */
    record TimerDeadline(
            long timerId,
            String activityContextName,
            long expiresAtEpochMs,
            long ownerId) implements Serializable {
        public TimerDeadline {
            Objects.requireNonNull(activityContextName, "activityContextName");
        }
    }

    /** Record (or overwrite) the absolute deadline of an armed timer. */
    void put(TimerDeadline deadline);

    /** Drop the record when the timer is cancelled or has fired. */
    void remove(long timerId);

    /** @return the replicated deadline, or {@code null} when unknown. */
    TimerDeadline get(long timerId);

    /** Number of tracked deadlines (for heartbeat/GC wiring). */
    int size();
}
