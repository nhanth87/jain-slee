package com.microjainslee.core;

import com.microjainslee.api.*;
import com.lmax.disruptor.*;

/**
 * JAIN-SLEE 1.1 §8.2 — SBB Entity Pool.
 * Uses LMAX Disruptor for O(1) allocation.
 */
public class SbbEntityPool {
    private final RingBuffer<SbbEntity> ringBuffer;

    public SbbEntityPool(int poolSize) {
        this.ringBuffer = RingBuffer.createSingleProducer(
            SbbEntity::new, poolSize, new YieldingWaitStrategy());
    }

    public SbbEntity acquire() {
        long sequence = ringBuffer.next();
        try {
            return ringBuffer.get(sequence);
        } finally {
            ringBuffer.publish(sequence);
        }
    }

    public void release(SbbEntity entity) {
        // Return to pool
    }

    private static class SbbEntity {
        private Sbb sbb;
        private SbbContext context;
    }
}