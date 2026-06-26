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
import com.microjainslee.api.InitialEventSelector;
import com.microjainslee.api.SleeEvent;

/**
 * Default {@link InitialEventSelector} — treats every event as initial until
 * an SBB customizer sets {@link #setInitialEvent(boolean)} to false.
 */
public final class DefaultInitialEventSelector implements InitialEventSelector {

    private final SleeEvent event;
    private final ActivityContextInterface activityContext;
    private volatile boolean initialEvent = true;
    private volatile String rootSbbId;

    public DefaultInitialEventSelector(SleeEvent event, ActivityContextInterface activityContext) {
        if (event == null || activityContext == null) {
            throw new IllegalArgumentException("event and activityContext are required");
        }
        this.event = event;
        this.activityContext = activityContext;
    }

    @Override
    public boolean isInitialEvent() {
        return initialEvent;
    }

    @Override
    public void setInitialEvent(boolean initial) {
        this.initialEvent = initial;
    }

    @Override
    public SleeEvent getEvent() {
        return event;
    }

    @Override
    public ActivityContextInterface getActivityContext() {
        return activityContext;
    }

    @Override
    public String getRootSbbId() {
        return rootSbbId;
    }

    @Override
    public void setRootSbbId(String rootSbbId) {
        this.rootSbbId = rootSbbId;
    }
}
