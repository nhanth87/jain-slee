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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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

    /**
     * Production P1.2 — optional external (JTA) transaction context. Typed
     * as {@link Object} so the kernel stays JTA-free. When bound, the
     * external context wraps every {@code deliverEvent} call (see
     * {@link EventRouter}) and this class can query the live tx status
     * via {@link #currentExternalStatus()} for diagnostic logging.
     */
    private volatile Object externalTransactionContext;

    /**
     * Cached reflective handle for {@code externalTransactionContext.currentStatus()}.
     * {@code null} when no external context is bound.
     */
    private volatile Method currentStatusMethod;

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

    /**
     * Production P1.2 — bind an external JTA {@code TransactionContext} so
     * status queries / diagnostics can inspect the live tx. The logical
     * undo stack ({@link #begin()} / {@link #recordAttach(SbbLocalObject)} /
     * {@link #rollback()}) is preserved regardless — this method only adds
     * observability, it does NOT change commit / rollback semantics.
     *
     * <p>The {@code txContext} MUST expose a public method
     * {@code int currentStatus()} for status lookup; otherwise an
     * {@link IllegalArgumentException} is raised.
     */
    public void setExternalTransactionContext(Object txContext) {
        if (txContext == null) {
            this.externalTransactionContext = null;
            this.currentStatusMethod = null;
            return;
        }
        Method m;
        try {
            m = txContext.getClass().getMethod("currentStatus");
        } catch (NoSuchMethodException nsme) {
            throw new IllegalArgumentException(
                    "external transaction context must expose currentStatus(): "
                            + txContext.getClass().getName(), nsme);
        }
        this.externalTransactionContext = txContext;
        this.currentStatusMethod = m;
    }

    /**
     * @return {@code true} when a JTA {@code TransactionContext} has been
     *         bound via {@link #setExternalTransactionContext(Object)}.
     */
    public boolean isJtaBacked() {
        return externalTransactionContext != null && currentStatusMethod != null;
    }

    /**
     * @return the external tx status code ({@code jakarta.transaction.Status}
     *         numeric values: 0 active, 1 marked rollback, 6 no tx, ...),
     *         or {@code -1} when no external context is bound or status
     *         lookup fails. Pure observation — does not affect undo stack.
     */
    public int currentExternalStatus() {
        Object ctx = externalTransactionContext;
        Method m = currentStatusMethod;
        if (ctx == null || m == null) {
            return -1;
        }
        try {
            Object result = m.invoke(ctx);
            if (result instanceof Integer) {
                return (Integer) result;
            }
            return -1;
        } catch (IllegalAccessException | InvocationTargetException iae) {
            return -1;
        }
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
