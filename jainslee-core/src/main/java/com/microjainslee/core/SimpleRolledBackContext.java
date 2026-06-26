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
import com.microjainslee.api.RolledBackContext;

/**
 * Immutable {@link RolledBackContext} carrier used by
 * {@link SbbLifecycleManager#rolledBack(com.microjainslee.api.Sbb, RolledBackContext)}
 * to hand off context to {@link com.microjainslee.api.Sbb#sbbRolledBack}.
 *
 * <p>JAIN-SLEE 1.1 §6.10.1.1 defines exactly three accessors:
 * {@link #getEvent()}, {@link #getActivityContextInterface()}, and
 * {@link #isRemoveRolledBack()}; we store all three plus the original
 * SBB id for diagnostics.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class SimpleRolledBackContext implements RolledBackContext {

    private final Object event;
    private final ActivityContextInterface activityContextInterface;
    private final boolean removeRolledBack;

    public SimpleRolledBackContext(Object event,
                                   ActivityContextInterface activityContextInterface,
                                   boolean removeRolledBack) {
        this.event = event;
        this.activityContextInterface = activityContextInterface;
        this.removeRolledBack = removeRolledBack;
    }

    /** Convenience factory for a "no event" rollback callback. */
    public static SimpleRolledBackContext forCascadingRemove() {
        return new SimpleRolledBackContext(null, null, true);
    }

    @Override
    public Object getEvent() {
        return event;
    }

    @Override
    public ActivityContextInterface getActivityContextInterface() {
        return activityContextInterface;
    }

    @Override
    public boolean isRemoveRolledBack() {
        return removeRolledBack;
    }
}