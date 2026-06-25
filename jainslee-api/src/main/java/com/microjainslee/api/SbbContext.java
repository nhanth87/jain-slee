package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §8.6 — SBB Context interface.
 * Provides access to SLEE facilities.
 */
public interface SbbContext {
    SbbLocalObject getSbbLocalObject();
    TimerPort getTimerFacility();
    ActivityContextNamingFacility getActivityContextNamingFacility();
    TracePort getTracer(String tracerName);
    UsagePort getUsageFacility();
}