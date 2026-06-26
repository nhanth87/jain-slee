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

import java.util.List;
import java.util.concurrent.*;

/**
 * JAIN-SLEE 1.1 §7.3 — Event Router.
 * Uses LMAX Disruptor for high-throughput event routing.
 */
public class EventRouter {
    private final Disruptor<EventWrapper> disruptor;
    private final ExecutorService executor;
    private final RingBuffer<EventWrapper> ringBuffer;
    private volatile VirtualThreadSbbEntityPool sbbEntityPool;

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
        // perVirtualThread is wired up later via bindSbbEntityPool() so the
        // router does not need a circular reference to MicroSleeContainer.
    }

    /**
     * Bind the per-SBB virtual-thread entity pool so dispatch() routes each
     * event onto the owning SBB thread rather than the EventRouter's worker.
     */
    public void bindSbbEntityPool(VirtualThreadSbbEntityPool pool) {
        this.sbbEntityPool = pool;
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

        List<SbbLocalObject> attached = ((InMemoryActivityContext) aci).getAttachedSbbs();
        for (final SbbLocalObject localObject : attached) {
            final Sbb sbb = localObject.getSbb();
            if (!(sbb instanceof SleeEventHandler)) {
                continue;
            }
            final SleeEventHandler handler = (SleeEventHandler) sbb;
            final VirtualThreadSbbEntityPool pool = this.sbbEntityPool;
            // If the entity pool is bound and knows about this SBB ID,
            // deliver the event on the SBB's owning virtual thread so the
            // SBB sees single-threaded ordering per JAIN-SLEE §6.
            if (pool != null) {
                VirtualThreadSbbEntityPool.SbbEntity entity =
                        findEntity(pool, localObject.getSbbID().getId(), localObject);
                if (entity != null) {
                    entity.submit(new Runnable() {
                        @Override public void run() {
                            try {
                                handler.onEvent(event, aci);
                            } catch (Exception e) {
                                sbb.sbbExceptionThrown(e, event, aci);
                            }
                        }
                    });
                    continue;
                }
            }
            // Fallback: dispatch inline on the EventRouter worker thread.
            try {
                handler.onEvent(event, aci);
            } catch (Exception e) {
                sbb.sbbExceptionThrown(e, event, aci);
            }
        }
    }

    private static VirtualThreadSbbEntityPool.SbbEntity findEntity(
            VirtualThreadSbbEntityPool pool, String sbbId, SbbLocalObject localObject) {
        // The SbbEntity is keyed by SBB ID. We cannot expose ConcurrentHashMap
        // directly without a public accessor, so look it up via the pool's
        // public size()-and-future-based matching: the entity's Future is not
        // accessible, but we can probe the map via reflection-free accessor.
        // To keep the API tight, we add a small lookup helper to the pool.
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
