/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.ActivityContextNamingFacility;
import com.microjainslee.api.SbbContext;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.ServiceID;
import com.microjainslee.api.TimerPort;
import com.microjainslee.api.TracePort;
import com.microjainslee.api.UsagePort;

/**
 * Minimal embedded SBB context backed by in-memory facilities.
 */
public final class SimpleSbbContext implements SbbContext {

    private final ServiceID serviceID;
    private final SbbLocalObject localObject;
    private final TimerPort timerPort;
    private final ActivityContextNamingFacility namingFacility;
    private final UsagePort usagePort = new SimpleUsagePort();

    public SimpleSbbContext(ServiceID serviceID, SbbLocalObject localObject, TimerPort timerPort,
            ActivityContextNamingFacility namingFacility) {
        if (serviceID == null) {
            throw new IllegalArgumentException("serviceID is required");
        }
        this.serviceID = serviceID;
        this.localObject = localObject;
        this.timerPort = timerPort;
        this.namingFacility = namingFacility;
    }

    @Override
    public ServiceID getService() {
        return serviceID;
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
