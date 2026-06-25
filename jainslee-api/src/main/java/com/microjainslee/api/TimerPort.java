package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §9 — Timer Port interface.
 * Provides timer facilities.
 */
public interface TimerPort {
    /**
     * Schedules a one-shot timer for the given SBB.
     *
     * @return opaque timer id for {@link #cancelTimer(long)}
     */
    long setTimer(long timeout, SbbLocalObject sbbLocalObject);

    void cancelTimer(long timerID);
}