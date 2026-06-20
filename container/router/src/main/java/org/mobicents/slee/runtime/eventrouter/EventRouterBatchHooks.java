/*
 * RestComm JAIN SLEE - end-of-batch hooks for Disruptor event router (Phase 2 TX batching).
 */
package org.mobicents.slee.runtime.eventrouter;

/**
 * Extension point invoked on each Disruptor worker thread when {@code endOfBatch} is true.
 * Phase 2 may register transaction batch commit logic here.
 */
public final class EventRouterBatchHooks {

    private static volatile Runnable endOfBatchHook;

    private EventRouterBatchHooks() {
    }

    public static void setEndOfBatchHook(Runnable hook) {
        endOfBatchHook = hook;
    }

    public static void onEndOfBatch() {
        Runnable hook = endOfBatchHook;
        if (hook != null) {
            hook.run();
        }
    }
}
