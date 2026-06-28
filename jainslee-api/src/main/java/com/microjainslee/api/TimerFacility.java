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
 * JAIN-SLEE 1.1 §9 — Timer Facility surface available to Resource Adaptors.
 * <p>
 * Distinct from the lower-level {@link TimerPort} used by SBBs: a
 * {@link ResourceAdaptorContext} exposes this interface so an RA can
 * set and cancel timers tied to its own lifecycle, independently from
 * any SBB entity's timers. The default in-memory implementation in
 * {@code jainslee-core} backs this with {@code SleeTimerSchedulerBridge}
 * so timer fires land on the SLEE event router.
 */
public interface TimerFacility {

    /**
     * Schedule a one-shot timer.
     *
     * @param durationMs time-to-fire in milliseconds
     * @return opaque timer id for {@link #cancelTimer(long)}
     */
    long setTimer(long durationMs);

    void cancelTimer(long timerId);
}
