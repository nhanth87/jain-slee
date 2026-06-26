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
 * JAIN-SLEE timer-fired event delivered via the Event Router.
 */
public final class TimerFiredEvent implements SleeEvent {

    private final long timerId;
    private final SbbLocalObject sbbLocalObject;

    public TimerFiredEvent(long timerId, SbbLocalObject sbbLocalObject) {
        this.timerId = timerId;
        this.sbbLocalObject = sbbLocalObject;
    }

    public long getTimerId() {
        return timerId;
    }

    public SbbLocalObject getSbbLocalObject() {
        return sbbLocalObject;
    }
}
