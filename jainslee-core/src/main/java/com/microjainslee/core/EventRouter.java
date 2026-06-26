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

import com.microjainslee.api.*;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JAIN-SLEE 1.1 §7.3 — Event Router.
 * Uses LMAX Disruptor for high-throughput event routing.
 */
public class EventRouter {
    private final Disruptor<EventWrapper> disruptor;
    private final ExecutorService executor;
    private final RingBuffer<EventWrapper> ringBuffer;
    private volatile VirtualThreadSbbEntityPool sbbEntityPool;
    private volatile SleeTimerSchedulerBridge timerBridge;
    private volatile ErrorHandlingPolicy errorHandlingPolicy;

    public EventRouter(int bufferSize) {
        this(bufferSize, false, false);
    }

    public EventRouter(int bufferSize, boolean preferVirtualThreads) {
        this(bufferSize, preferVirtualThreads, false);
    }

    public EventRouter(int bufferSize, boolean preferVirtualThreads, boolean perVirtualThread) {
        this.executor = MicroSleeExecutors.newEventExecutor(preferVirtualThreads);
        this.disruptor = new Disruptor<EventWrapper>(
                new EventFactory<EventWrapper>() {
                    @Override
                    public EventWrapper newInstance() {
                        return new EventWrapper();
                    }
                },
                bufferSize,
                executor,
                ProducerType.MULTI,
                new YieldingWaitStrategy());
        this.disruptor.handleEventsWith(new EventHandler<EventWrapper>() {
            @Override
            public void onEvent(EventWrapper wrapper, long sequence, boolean endOfBatch) {
                try {
                    dispatch(wrapper.event, wrapper.aci);
                } finally {
                    wrapper.clear();
                }
            }
        });
        this.ringBuffer = disruptor.start();
    }

    /**
     * Bind the per-SBB virtual-thread entity pool so dispatch() routes each
     * event onto the owning SBB thread rather than the EventRouter's worker.
     */
    public void bindSbbEntityPool(VirtualThreadSbbEntityPool pool) {
        this.sbbEntityPool = pool;
    }

    /**
     * Bind timer and error-handling support for logical transactions during dispatch.
     */
    public void bindTransactionSupport(SleeTimerSchedulerBridge timerBridge,
            ErrorHandlingPolicy errorHandlingPolicy) {
        this.timerBridge = timerBridge;
        this.errorHandlingPolicy = errorHandlingPolicy;
    }

    public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
        long sequence = ringBuffer.next();
        try {
            EventWrapper wrapper = ringBuffer.get(sequence);
            wrapper.setEvent(event);
            wrapper.setAci(aci);
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    public void shutdown() {
        disruptor.shutdown();
        executor.shutdown();
    }

    private void dispatch(SleeEvent event, ActivityContextInterface aci) {
        if (event == null || aci == null) {
            return;
        }
        if (!(aci instanceof InMemoryActivityContext)) {
            return;
        }

        InMemoryActivityContext activityContext = (InMemoryActivityContext) aci;
        if (activityContext.isSuspended()) {
            return;
        }

        ReentrantLock concurrencyLock = activityContext.lockForEvent(event);
        if (concurrencyLock != null) {
            concurrencyLock.lock();
        }
        try {
            dispatchUnderLock(event, aci, activityContext);
        } finally {
            if (concurrencyLock != null) {
                concurrencyLock.unlock();
            }
        }
    }

    private void dispatchUnderLock(SleeEvent event, ActivityContextInterface aci,
            InMemoryActivityContext activityContext) {
        SbbTransactionContext transaction = ActivityContextTransactionRegistry.begin(
                activityContext, timerBridge);
        boolean failed = false;
        try {
            List<SbbLocalObject> attached = new ArrayList<SbbLocalObject>(
                    activityContext.getAttachedSbbs());
            Collections.sort(attached, new Comparator<SbbLocalObject>() {
                @Override
                public int compare(SbbLocalObject left, SbbLocalObject right) {
                    return Integer.compare(right.getPriority(), left.getPriority());
                }
            });
            for (SbbLocalObject localObject : attached) {
                if (localObject.isRemoved()) {
                    continue;
                }
                Sbb sbb = localObject.getSbb();
                if (!(sbb instanceof SleeEventHandler)) {
                    continue;
                }
                SleeEventHandler handler = (SleeEventHandler) sbb;
                if (deliverEvent(localObject, handler, sbb, event, aci, transaction)) {
                    failed = true;
                    break;
                }
            }
            if (!failed) {
                transaction.commit();
            }
        } finally {
            ActivityContextTransactionRegistry.clear(transaction);
        }
    }

    private boolean deliverEvent(SbbLocalObject localObject, SleeEventHandler handler, Sbb sbb,
            SleeEvent event, ActivityContextInterface aci, SbbTransactionContext transaction) {
        VirtualThreadSbbEntityPool pool = this.sbbEntityPool;
        if (pool != null) {
            VirtualThreadSbbEntityPool.SbbEntity entity =
                    findEntity(pool, localObject.getSbbID().getId(), localObject);
            if (entity != null) {
                final AtomicReference<Exception> failure = new AtomicReference<Exception>();
                final CountDownLatch done = new CountDownLatch(1);
                entity.submit(new Runnable() {
                    @Override
                    public void run() {
                        ActivityContextTransactionRegistry.install(transaction);
                        try {
                            handler.onEvent(event, aci);
                        } catch (Exception e) {
                            failure.set(e);
                        } finally {
                            ActivityContextTransactionRegistry.clear(transaction);
                            done.countDown();
                        }
                    }
                });
                try {
                    if (!done.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "Timed out delivering event to SBB " + localObject.getSbbID());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted delivering event to SBB " + localObject.getSbbID(), e);
                }
                if (failure.get() != null) {
                    handleSbbException(failure.get(), localObject, event, aci, transaction);
                    return true;
                }
                return false;
            }
        }
        try {
            handler.onEvent(event, aci);
            return false;
        } catch (Exception e) {
            handleSbbException(e, localObject, event, aci, transaction);
            return true;
        }
    }

    private void handleSbbException(Exception exception, SbbLocalObject localObject, SleeEvent event,
            ActivityContextInterface aci, SbbTransactionContext transaction) {
        transaction.rollback();
        if (errorHandlingPolicy != null) {
            errorHandlingPolicy.onSbbException(localObject, exception, event, aci);
        } else {
            try {
                localObject.getSbb().sbbExceptionThrown(exception, event, aci);
            } catch (Throwable ignored) {
                // never let application exception handlers break dispatch
            }
        }
    }

    private static VirtualThreadSbbEntityPool.SbbEntity findEntity(
            VirtualThreadSbbEntityPool pool, String sbbId, SbbLocalObject localObject) {
        return pool.findEntity(sbbId);
    }

    private static class EventWrapper {
        private com.microjainslee.api.SleeEvent event;
        private ActivityContextInterface aci;

        public void setEvent(com.microjainslee.api.SleeEvent event) { this.event = event; }
        public void setAci(ActivityContextInterface aci) { this.aci = aci; }
        public void clear() {
            this.event = null;
            this.aci = null;
        }
    }
}
