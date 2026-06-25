package com.microjainslee.core;

import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.TimerPort;

/**
 * JAIN-SLEE 1.1 §9 — Timer Facility backed by jSS7 {@code LocalTimerAdapter}.
 */
public final class TimerPortImpl implements TimerPort {

    private final SleeTimerSchedulerBridge bridge;

    public TimerPortImpl(SleeTimerSchedulerBridge bridge) {
        this.bridge = bridge;
    }

    public static TimerPortImpl create(EventRouter eventRouter) {
        return new TimerPortImpl(SleeTimerSchedulerBridge.create(eventRouter));
    }

    @Override
    public long setTimer(long timeout, SbbLocalObject sbbLocalObject) {
        return bridge.schedule(sbbLocalObject, timeout);
    }

    @Override
    public void cancelTimer(long timerID) {
        bridge.cancel(timerID);
    }

    public SleeTimerSchedulerBridge getBridge() {
        return bridge;
    }
}
