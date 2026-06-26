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
import com.microjainslee.api.TimerFiredEvent;
import org.restcomm.protocols.ss7.scheduler.api.TimerCallback;
import org.restcomm.protocols.ss7.scheduler.api.TimerRecord;
import org.restcomm.protocols.ss7.scheduler.api.TimerScheduler;
import org.restcomm.protocols.ss7.scheduler.api.TimerType;
import org.restcomm.protocols.ss7.scheduler.impl.LocalTimerAdapter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges micro-jainslee {@link com.microjainslee.api.TimerPort} to jSS7
 * {@link TimerScheduler}, posting timer fires to {@link EventRouter} instead
 * of invoking SBB code on the hashed-wheel thread.
 */
public final class SleeTimerSchedulerBridge {

    private static final String LOCAL_NODE_ID = "micro-jainslee";
    private static final TimerType SLEE_TIMER_TYPE = TimerType.SLEE_TIMER;

    private final TimerScheduler scheduler;
    private final EventRouter eventRouter;
    private final AtomicLong nextTimerId = new AtomicLong(1);
    private final ConcurrentHashMap<Long, TimerTarget> targetsByTimerId = new ConcurrentHashMap<Long, TimerTarget>();
    private final ConcurrentHashMap<SbbLocalObject, ActivityContextInterface> aciBySbb =
            new ConcurrentHashMap<SbbLocalObject, ActivityContextInterface>();

    public SleeTimerSchedulerBridge(EventRouter eventRouter, TimerScheduler scheduler) {
        this.eventRouter = eventRouter;
        this.scheduler = scheduler;
    }

    public static SleeTimerSchedulerBridge create(EventRouter eventRouter) {
        LocalTimerAdapter adapter = new LocalTimerAdapter("micro-jainslee-timer");
        adapter.start();
        return new SleeTimerSchedulerBridge(eventRouter, adapter);
    }

    public void bindActivityContext(SbbLocalObject sbbLocalObject, ActivityContextInterface aci) {
        aciBySbb.put(sbbLocalObject, aci);
    }

    public void unbindActivityContext(SbbLocalObject sbbLocalObject) {
        aciBySbb.remove(sbbLocalObject);
    }

    public long schedule(SbbLocalObject sbbLocalObject, long delayMillis) {
        ActivityContextInterface aci = resolveActivityContext(sbbLocalObject);
        long timerId = nextTimerId.getAndIncrement();
        long dialogId = dialogIdFor(sbbLocalObject);
        long now = System.currentTimeMillis();
        TimerRecord record = new TimerRecord(
                timerId,
                dialogId,
                SLEE_TIMER_TYPE,
                now + delayMillis,
                LOCAL_NODE_ID,
                1,
                now);
        targetsByTimerId.put(timerId, new TimerTarget(sbbLocalObject, aci));
        scheduler.schedule(record, delayMillis, timerFireCallback);
        return timerId;
    }

    public void cancel(long timerId) {
        targetsByTimerId.remove(timerId);
        scheduler.cancel(timerId);
    }

    public void cancelAll(SbbLocalObject sbbLocalObject) {
        scheduler.cancelAll(dialogIdFor(sbbLocalObject));
        for (java.util.Map.Entry<Long, TimerTarget> entry : targetsByTimerId.entrySet()) {
            if (entry.getValue().sbbLocalObject == sbbLocalObject) {
                targetsByTimerId.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    public void shutdown() {
        scheduler.stop();
        targetsByTimerId.clear();
        aciBySbb.clear();
    }

    public TimerScheduler getScheduler() {
        return scheduler;
    }

    private final TimerCallback timerFireCallback = new TimerCallback() {
        @Override
        public void onTimerFire(TimerRecord record) {
            TimerTarget target = targetsByTimerId.remove(record.getTimerId());
            if (target == null) {
                return;
            }
            TimerFiredEvent event = new TimerFiredEvent(record.getTimerId(), target.sbbLocalObject);
            eventRouter.routeEvent(event, target.activityContext);
        }
    };

    private ActivityContextInterface resolveActivityContext(SbbLocalObject sbbLocalObject) {
        ActivityContextInterface aci = aciBySbb.get(sbbLocalObject);
        if (aci != null) {
            return aci;
        }
        return new AnonymousActivityContext("timer-sbb-" + sbbLocalObject.getSbbID());
    }

    private static long dialogIdFor(SbbLocalObject sbbLocalObject) {
        return System.identityHashCode(sbbLocalObject);
    }

    private static final class TimerTarget {
        private final SbbLocalObject sbbLocalObject;
        private final ActivityContextInterface activityContext;

        private TimerTarget(SbbLocalObject sbbLocalObject, ActivityContextInterface activityContext) {
            this.sbbLocalObject = sbbLocalObject;
            this.activityContext = activityContext;
        }
    }

    private static final class AnonymousActivityContext implements ActivityContextInterface {
        private final String name;

        private AnonymousActivityContext(String name) {
            this.name = name;
        }

        @Override
        public String getActivityContextName() {
            return name;
        }

        @Override
        public void attach(SbbLocalObject sbbLocalObject) {
        }

        @Override
        public void detach(SbbLocalObject sbbLocalObject) {
        }
    }
}
