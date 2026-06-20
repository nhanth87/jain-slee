/*
 * RestComm JAIN SLEE - reusable event routing tasks (zero-GC hot path).
 */
package org.mobicents.slee.runtime.eventrouter.routingtask;

import org.mobicents.slee.container.SleeContainer;
import org.mobicents.slee.container.event.EventContext;

/**
 * Thread-local pool of {@link EventRoutingTaskImpl} instances for the event router hot path.
 * Each Disruptor worker thread reuses one task object instead of allocating per event.
 */
public final class EventRoutingTaskPool {

    private static final ThreadLocal<EventRoutingTaskImpl> TASK =
            ThreadLocal.withInitial(EventRoutingTaskImpl::new);

    private EventRoutingTaskPool() {
    }

    public static EventRoutingTaskImpl borrow(EventContext eventContext, SleeContainer sleeContainer) {
        EventRoutingTaskImpl task = TASK.get();
        task.init(eventContext, sleeContainer);
        return task;
    }

    public static void route(EventContext eventContext, SleeContainer sleeContainer) {
        EventRoutingTaskImpl task = borrow(eventContext, sleeContainer);
        try {
            task.run();
        } finally {
            task.resetAfterRun();
        }
    }
}
