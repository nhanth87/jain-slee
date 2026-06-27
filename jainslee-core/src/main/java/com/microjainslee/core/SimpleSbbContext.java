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
import com.microjainslee.api.AlarmFacility;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileTablePort;
import com.microjainslee.api.SbbContext;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.ServiceID;
import com.microjainslee.api.TimerPort;
import com.microjainslee.api.TracePort;
import com.microjainslee.api.UsagePort;

/**
 * Minimal embedded SBB context backed by in-memory facilities.
 * <p>
 * Tracks the current transaction's rollback flag so that
 * {@link #setRollbackOnly()} / {@link #getRollbackOnly()} reflect real state.
 */
public final class SimpleSbbContext implements SbbContext {

    private final ServiceID serviceID;
    private final SbbLocalObject localObject;
    private final SbbID sbbID;
    private final TimerPort timerPort;
    private final ActivityContextNamingFacility namingFacility;
    private final ProfileFacility profileFacility;
    private final AlarmFacility alarmFacility;
    private final UsagePort usagePort = new SimpleUsagePort();
    private volatile boolean rollbackOnly;

    public SimpleSbbContext(ServiceID serviceID, SbbLocalObject localObject, TimerPort timerPort,
            ActivityContextNamingFacility namingFacility) {
        this(serviceID, localObject, null, timerPort, namingFacility, null, null);
    }

    public SimpleSbbContext(ServiceID serviceID, SbbLocalObject localObject,
            SbbID sbbID, TimerPort timerPort,
            ActivityContextNamingFacility namingFacility) {
        this(serviceID, localObject, sbbID, timerPort, namingFacility, null, null);
    }

    /**
     * Full-arg constructor used by the container to thread every facility
     * reference through in one shot. The {@code profileFacility} argument is
     * exposed via {@link #getProfileFacility()}; pass {@code null} when
     * profile support is not wired (legacy test fixtures).
     */
    public SimpleSbbContext(ServiceID serviceID, SbbLocalObject localObject,
            SbbID sbbID, TimerPort timerPort,
            ActivityContextNamingFacility namingFacility,
            ProfileFacility profileFacility) {
        this(serviceID, localObject, sbbID, timerPort, namingFacility, profileFacility, null);
    }

    /**
     * Full-arg constructor including the alarm facility. The container uses
     * this variant to install the {@link SimpleAlarmFacility} it created
     * during {@link MicroSleeContainer} start-up.
     */
    public SimpleSbbContext(ServiceID serviceID, SbbLocalObject localObject,
            SbbID sbbID, TimerPort timerPort,
            ActivityContextNamingFacility namingFacility,
            ProfileFacility profileFacility,
            AlarmFacility alarmFacility) {
        if (serviceID == null) {
            throw new IllegalArgumentException("serviceID is required");
        }
        this.serviceID = serviceID;
        this.localObject = localObject;
        this.sbbID = sbbID;
        this.timerPort = timerPort;
        this.namingFacility = namingFacility;
        this.profileFacility = profileFacility;
        this.alarmFacility = alarmFacility;
    }

    @Override
    public ServiceID getService() {
        return serviceID;
    }

    @Override
    public SbbLocalObject getSbbLocalObject() {
        if (localObject != null && ((SimpleSbbLocalObject) localObject).isRemoved()) {
            throw new IllegalStateException(
                    "SBB entity is no longer valid");
        }
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

    @Override
    public AlarmFacility getAlarmFacility() {
        return alarmFacility;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns the {@link ProfileFacility} threaded through by the
     * container, or {@code null} when no profile backend was wired
     * (e.g. legacy test fixtures). The return type is the deprecated
     * {@link ProfileTablePort} marker that still extends
     * {@link ProfileFacility} so existing call-sites keep compiling.
     * The default in-memory facility implements {@link ProfileTablePort}
     * directly; embedders supplying a pure {@code ProfileFacility}
     * implementation would need to wrap it themselves.
     */
    @Override
    public ProfileTablePort getProfileFacility() {
        if (profileFacility == null) {
            return null;
        }
        if (profileFacility instanceof ProfileTablePort) {
            return (ProfileTablePort) profileFacility;
        }
        // Pure ProfileFacility impls (no legacy port) are not visible
        // through this return type — return null to honour the legacy
        // null-when-absent convention rather than ClassCast.
        return null;
    }

    @Override
    public void setRollbackOnly() {
        this.rollbackOnly = true;
    }

    @Override
    public boolean getRollbackOnly() {
        return rollbackOnly;
    }

    @Override
    public SbbID getSbb() {
        if (sbbID != null) {
            return sbbID;
        }
        // Fallback: derive from the SBB class name via the local object.
        if (localObject != null) {
            return new SbbID(localObject.getSbb().getClass().getName());
        }
        return new SbbID("unknown");
    }
}
