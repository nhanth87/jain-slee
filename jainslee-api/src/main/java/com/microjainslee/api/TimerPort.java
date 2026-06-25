package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §9 — Timer Port interface.
 * Provides timer facilities.
 */
public interface TimerPort {
    void setTimer(long timeout, SbbLocalObject sbbLocalObject);
}