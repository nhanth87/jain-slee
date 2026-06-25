package com.microjainslee.core;

import com.microjainslee.api.*;
import com.lmax.disruptor.*;
import java.util.concurrent.*;

/**
 * JAIN-SLEE 1.1 §7.3 — Event Router.
 * Uses LMAX Disruptor for high-throughput event routing.
 */
public class EventRouter {
    private final RingBuffer<EventWrapper> ringBuffer;
    private final ExecutorService executor;

    public EventRouter(int bufferSize) {
        this.ringBuffer = RingBuffer.createSingleProducer(
            EventWrapper::new, bufferSize, new YieldingWaitStrategy());
        this.executor = Executors.newCachedThreadPool();
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

    private static class EventWrapper {
        private com.microjainslee.api.SleeEvent event;
        private ActivityContextInterface aci;

        public void setEvent(com.microjainslee.api.SleeEvent event) { this.event = event; }
        public void setAci(ActivityContextInterface aci) { this.aci = aci; }
    }
}