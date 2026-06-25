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
