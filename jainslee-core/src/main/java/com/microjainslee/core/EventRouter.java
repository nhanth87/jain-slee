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

    public EventRouter(int bufferSize) {
        this(bufferSize, false);
    }

    public EventRouter(int bufferSize, boolean preferVirtualThreads) {
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
        for (SbbLocalObject localObject : attached) {
            Sbb sbb = localObject.getSbb();
            if (!(sbb instanceof SleeEventHandler)) {
                continue;
            }
            try {
                ((SleeEventHandler) sbb).onEvent(event, aci);
            } catch (Exception e) {
                sbb.sbbExceptionThrown(e, event, aci);
            }
        }
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
