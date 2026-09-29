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

    private static final String DEFAULT_NODE_ID = "micro-jainslee";
    private static final TimerType SLEE_TIMER_TYPE = TimerType.SLEE_TIMER;

    private final TimerScheduler scheduler;
    private final EventRouter eventRouter;
    private final AtomicLong nextTimerId = new AtomicLong(1);
    /**
     * ADR 0007 D6 — one id per SBB entity. {@code System.identityHashCode} was used
     * before; identity hashes are not unique, so {@code cancelAll} for one SBB
     * could cancel another SBB's timers when their hashes collided.
     */
    private final AtomicLong nextOwnerId = new AtomicLong(1);
    private final ConcurrentHashMap<SbbLocalObject, Long> ownerIds = new ConcurrentHashMap<SbbLocalObject, Long>();
    /** ADR 0007 D6 — real node id in every {@link TimerRecord}, not a constant shared by all nodes. */
    private volatile String nodeId = DEFAULT_NODE_ID;
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

    /** Set by the container once the node id is known (cluster bound or configured). */
    public void setNodeId(String nodeId) {
        if (nodeId != null && !nodeId.isBlank()) {
            this.nodeId = nodeId;
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public void bindActivityContext(SbbLocalObject sbbLocalObject, ActivityContextInterface aci) {
        aciBySbb.put(sbbLocalObject, aci);
    }

    public void unbindActivityContext(SbbLocalObject sbbLocalObject) {
        aciBySbb.remove(sbbLocalObject);
        ownerIds.remove(sbbLocalObject);          // same lifetime as the ACI binding — no leak
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
                nodeId,
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
        Long ownerId = ownerIds.remove(sbbLocalObject);
        if (ownerId != null) {
            scheduler.cancelAll(ownerId);
        }
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
        ownerIds.clear();
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

    private long dialogIdFor(SbbLocalObject sbbLocalObject) {
        return ownerIds.computeIfAbsent(sbbLocalObject, k -> nextOwnerId.getAndIncrement());
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
