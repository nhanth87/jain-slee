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

/**
 * Thread-local registry for the logical transaction currently executing on an
 * activity context.
 */
public final class ActivityContextTransactionRegistry {

    private static final ThreadLocal<SbbTransactionContext> CURRENT =
            new ThreadLocal<SbbTransactionContext>();

    private ActivityContextTransactionRegistry() {
    }

    public static SbbTransactionContext begin(InMemoryActivityContext activityContext,
            SleeTimerSchedulerBridge timerBridge) {
        SbbTransactionContext context = new SbbTransactionContext(activityContext, timerBridge);
        context.begin();
        install(context);
        return context;
    }

    public static void install(SbbTransactionContext context) {
        CURRENT.set(context);
    }

    public static SbbTransactionContext current() {
        return CURRENT.get();
    }

    public static SbbTransactionContext currentFor(InMemoryActivityContext activityContext) {
        SbbTransactionContext context = CURRENT.get();
        if (context != null && context.isActive()
                && context.getActivityContext() == activityContext) {
            return context;
        }
        return null;
    }

    public static void clear(SbbTransactionContext context) {
        if (CURRENT.get() == context) {
            CURRENT.remove();
        }
    }
}
