package com.microjainslee.core;

import com.microjainslee.api.ActivityContextNamingFacility;
import com.microjainslee.api.SbbContext;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.TimerPort;
import com.microjainslee.api.TracePort;
import com.microjainslee.api.UsagePort;

/**
 * Minimal embedded SBB context backed by in-memory facilities.
 */
public final class SimpleSbbContext implements SbbContext {

    private final SbbLocalObject localObject;
    private final TimerPort timerPort;
    private final ActivityContextNamingFacility namingFacility;
    private final UsagePort usagePort = new SimpleUsagePort();

    public SimpleSbbContext(SbbLocalObject localObject, TimerPort timerPort,
            ActivityContextNamingFacility namingFacility) {
        this.localObject = localObject;
        this.timerPort = timerPort;
        this.namingFacility = namingFacility;
    }

    @Override
    public SbbLocalObject getSbbLocalObject() {
        return localObject;
    }

    @Override
    public TimerPort getTimerFacility() {
        return timerPort;
    }

    @Override
    public ActivityContextNamingFacility getActivityContextNamingFacility() {
        return namingFacility;
    }

    @Override
    public TracePort getTracer(String tracerName) {
        return new SimpleTracePort(tracerName);
    }

    @Override
    public UsagePort getUsageFacility() {
        return usagePort;
    }
}
