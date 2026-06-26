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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;

/**
 * Default rollback: detach SBB from AC, cancel timers, notify SBB lifecycle hook.
 */
public final class DefaultErrorHandlingPolicy implements ErrorHandlingPolicy {

    private final SleeTimerSchedulerBridge timerBridge;

    public DefaultErrorHandlingPolicy(SleeTimerSchedulerBridge timerBridge) {
        if (timerBridge == null) {
            throw new IllegalArgumentException("timerBridge is required");
        }
        this.timerBridge = timerBridge;
    }

    @Override
    public void onSbbException(SbbLocalObject sbb, Exception e, SleeEvent event, ActivityContextInterface aci) {
        if (aci instanceof InMemoryActivityContext) {
            ((InMemoryActivityContext) aci).detachImmediate(sbb);
        } else if (aci != null) {
            aci.detach(sbb);
        }
        timerBridge.cancelAll(sbb);
        timerBridge.unbindActivityContext(sbb);
        sbb.getSbb().sbbExceptionThrown(e, event, aci);
    }
}
