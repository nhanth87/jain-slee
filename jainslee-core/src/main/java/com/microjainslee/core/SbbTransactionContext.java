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

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * JAIN-SLEE 1.1 §§ 5.14, 6.8 — logical transaction scoped to one activity context.
 *
 * <p>Tracks attach/detach and timer-binding side effects during event delivery so
 * they can be committed on success or rolled back when {@code sbbExceptionThrown}
 * is raised. No JTA/XA integration is required.
 */
public final class SbbTransactionContext {

    private final InMemoryActivityContext activityContext;
    private final SleeTimerSchedulerBridge timerBridge;
    private final Deque<Runnable> undoActions = new ArrayDeque<Runnable>();
    private boolean active;

    public SbbTransactionContext(InMemoryActivityContext activityContext,
            SleeTimerSchedulerBridge timerBridge) {
        if (activityContext == null) {
            throw new IllegalArgumentException("activityContext is required");
        }
        this.activityContext = activityContext;
        this.timerBridge = timerBridge;
    }

    public InMemoryActivityContext getActivityContext() {
        return activityContext;
    }

    public boolean isActive() {
        return active;
    }

    public void begin() {
        active = true;
        undoActions.clear();
    }

    public void commit() {
        active = false;
        undoActions.clear();
    }

    public void rollback() {
        active = false;
        while (!undoActions.isEmpty()) {
            undoActions.pop().run();
        }
    }

    /**
     * Records an attach to this transaction's activity context, applying it
     * immediately and enqueueing an undo action for rollback.
     */
    public void recordAttach(SbbLocalObject sbbLocalObject) {
        if (sbbLocalObject == null) {
            return;
        }
        if (!active) {
            applyAttach(sbbLocalObject);
            return;
        }
        applyAttach(sbbLocalObject);
        final SbbLocalObject target = sbbLocalObject;
        undoActions.push(new Runnable() {
            @Override
            public void run() {
                applyDetach(target);
            }
        });
    }

    /**
     * Records a detach from this transaction's activity context.
     */
    public void recordDetach(SbbLocalObject sbbLocalObject) {
        if (sbbLocalObject == null) {
            return;
        }
        if (!active) {
            applyDetach(sbbLocalObject);
            return;
        }
        applyDetach(sbbLocalObject);
        final SbbLocalObject target = sbbLocalObject;
        undoActions.push(new Runnable() {
            @Override
            public void run() {
                applyAttach(target);
            }
        });
    }

    /**
     * Records a timer-facility binding for the given SBB on this activity context.
     */
    public void recordTimerBind(SbbLocalObject sbbLocalObject) {
        if (sbbLocalObject == null || timerBridge == null) {
            return;
        }
        if (!active) {
            timerBridge.bindActivityContext(sbbLocalObject, activityContext);
            return;
        }
        timerBridge.bindActivityContext(sbbLocalObject, activityContext);
        final SbbLocalObject target = sbbLocalObject;
        undoActions.push(new Runnable() {
            @Override
            public void run() {
                timerBridge.unbindActivityContext(target);
            }
        });
    }

    void applyAttach(SbbLocalObject sbbLocalObject) {
        activityContext.attachImmediate(sbbLocalObject);
    }

    void applyDetach(SbbLocalObject sbbLocalObject) {
        activityContext.detachImmediate(sbbLocalObject);
        if (timerBridge != null) {
            timerBridge.unbindActivityContext(sbbLocalObject);
        }
    }
}
