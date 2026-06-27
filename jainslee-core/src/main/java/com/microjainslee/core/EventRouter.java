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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JAIN-SLEE 1.1 §7.3 — Event Router.
 * Uses LMAX Disruptor for high-throughput event routing.
 *
 * <p>§8.6 — the router applies each attached SBB's {@link EventMask}
 * before invoking {@code onEvent}, which is the single largest
 * per-event cost saving called out in the micro-jainslee audit (G1).
 */
public class EventRouter {

    private static final Logger LOG = LogManager.getLogger(EventRouter.class);

    private final Disruptor<EventWrapper> disruptor;
    private final ExecutorService executor;
    private final RingBuffer<EventWrapper> ringBuffer;
    private volatile VirtualThreadSbbEntityPool sbbEntityPool;
    private volatile SleeTimerSchedulerBridge timerBridge;
    private volatile ErrorHandlingPolicy errorHandlingPolicy;

    /**
     * Running count of events skipped because no attached SBB had a matching
     * {@link EventMask}. Exposed via {@link #getSkippedMaskCount()} for
     * diagnostics; never reset.
     */
    private final AtomicLong skippedMaskCount = new AtomicLong();

    public EventRouter(int bufferSize) {
        this(bufferSize, false, false);
    }

    public EventRouter(int bufferSize, boolean preferVirtualThreads) {
        this(bufferSize, preferVirtualThreads, false);
    }

    public EventRouter(int bufferSize, boolean preferVirtualThreads, boolean perVirtualThread) {
        this.executor = MicroSleeExecutors.newEventExecutor(preferVirtualThreads);
        // Disruptor 3.4.2 still uses the (factory, ringSize, executor,
        // producerType, waitStrategy) constructor — the builder API
        // `Disruptor.<T>newBuilder()` only landed in Disruptor 3.4.4+.
        // The 5-arg ctor is marked @Deprecated in newer versions as a
        // forward-compat hint; suppress here because we cannot yet
        // bump the dep without breaking the other disruptor users.
        @SuppressWarnings("deprecation")
        Disruptor<EventWrapper> built = new Disruptor<EventWrapper>(
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
        this.disruptor = built;
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
        // ScopedValue.where binds the new transaction to the caller's
        // structured scope for the duration of this dispatch. The
        // surrounding code that calls CURRENT.get() (via
        // currentFor or transaction.recordAttach) will see the value
        // inside this scope.
        final boolean[] failedHolder = new boolean[] { false };
        ScopedValue.where(ActivityContextTransactionRegistry.CURRENT, transaction)
                .run(new Runnable() {
                    @Override
                    public void run() {
                        dispatchWithTransaction(event, aci, activityContext,
                                transaction, failedHolder);
                    }
                });
    }

    private void dispatchWithTransaction(SleeEvent event, ActivityContextInterface aci,
            InMemoryActivityContext activityContext,
            SbbTransactionContext transaction, boolean[] failedHolder) {
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
                // JAIN-SLEE 1.1 §8.6 — apply the SBB's EventMask before invoking
                // onEvent. Without this filter the router spends a transaction,
                // a virtual-thread handoff, and a stack frame per attached SBB
                // per event — the single biggest hot-loop waste called out in
                // docs/micro-jainslee-audit-v2.md (G1). Skipped events are
                // counted (cheap) and logged at debug only.
                if (!acceptsEvent(localObject, event)) {
                    skippedMaskCount.incrementAndGet();
                    LOG.debug("Event {} skipped by SBB {} (mask)",
                            event.getClass().getName(),
                            localObject.getSbbID() != null ? localObject.getSbbID().getId() : "?");
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

    /**
     * §8.6 — does this SBB entity's {@link EventMask} accept {@code event}?
     *
     * <p>Implementation notes:
     * <ul>
     *   <li>When the SBB is a {@link SimpleSbbLocalObject} we read the mask
     *       out of its {@link SbbEntityState}; this is one volatile read
     *       and a single {@code switch} on the mask mode discriminator.</li>
     *   <li>For non-{@code SimpleSbbLocalObject} SBBs (third-party
     *       implementations) we conservatively assume {@link EventMask#ACCEPT_ALL}
     *       so we don't break the existing public surface.</li>
     * </ul>
     */
    private static boolean acceptsEvent(SbbLocalObject localObject, SleeEvent event) {
        if (localObject instanceof SimpleSbbLocalObject) {
            EventMask mask = ((SimpleSbbLocalObject) localObject).getEntityState().getEventMask();
            return mask == EventMask.ACCEPT_ALL || mask.matches(event);
        }
        // Conservative default — unknown SBB shapes get every event.
        return true;
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
                        // ScopedValue.where binds the transaction to the
                        // structured scope for the duration of this runnable.
                        // When the runnable returns, the scope exits and the
                        // value is automatically cleared — safe across
                        // virtual threads.
                        ScopedValue.where(ActivityContextTransactionRegistry.CURRENT,
                                transaction).run(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    handler.onEvent(event, aci);
                                } catch (Exception e) {
                                    failure.set(e);
                                } finally {
                                    done.countDown();
                                }
                            }
                        });
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

    /**
     * @return the number of (event × attached-SBB) pairs skipped because the
     *         SBB's {@link EventMask} did not accept the event. Useful for
     *         verifying the §8.6 filter is wired correctly during bring-up.
     */
    public long getSkippedMaskCount() {
        return skippedMaskCount.get();
    }
}
