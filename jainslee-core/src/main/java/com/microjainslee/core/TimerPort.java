package com.microjainslee.core;

import com.microjainslee.api.*;

/**
 * JAIN-SLEE 1.1 §9 — Timer Facility.
 * Provides timer services to SBBs.
 */
public interface TimerPort {
    void setTimer(long timeout, SbbLocalObject sbbLocalObject);
    void cancelTimer(long timerID);
}