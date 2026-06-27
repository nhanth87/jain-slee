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

/**
 * Immutable configuration for the embedded micro JAIN-SLEE container.
 */
public final class MicroSleeConfiguration {

    private static final int DEFAULT_RING_BUFFER_SIZE = 1024;
    private static final int DEFAULT_SBB_POOL_MIN = 16;
    private static final int DEFAULT_SBB_POOL_MAX = 1024;

    private static final int DEFAULT_SBB_TYPE_POOL_MIN_IDLE = 0;

    private final int eventRouterBufferSize;
    private final boolean preferVirtualThreads;
    private final int sbbPoolMin;
    private final int sbbPoolMax;
    private final boolean sbbPerVirtualThread;
    private final int sbbTypePoolMinIdle;
    private final EventDeliveryMode eventDeliveryMode;

    private MicroSleeConfiguration(Builder builder) {
        this.eventRouterBufferSize = builder.eventRouterBufferSize;
        this.preferVirtualThreads = builder.preferVirtualThreads;
        this.sbbPoolMin = builder.sbbPoolMin;
        this.sbbPoolMax = builder.sbbPoolMax;
        this.sbbPerVirtualThread = builder.sbbPerVirtualThread;
        this.sbbTypePoolMinIdle = builder.sbbTypePoolMinIdle;
        this.eventDeliveryMode = builder.eventDeliveryMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static MicroSleeConfiguration defaults() {
        return builder().build();
    }

    public int getEventRouterBufferSize() {
        return eventRouterBufferSize;
    }

    public boolean isPreferVirtualThreads() {
        return preferVirtualThreads;
    }

    public int getSbbPoolMin() {
        return sbbPoolMin;
    }

    public int getSbbPoolMax() {
        return sbbPoolMax;
    }

    public boolean isSbbPerVirtualThread() {
        return sbbPerVirtualThread;
    }

    public int getSbbTypePoolMinIdle() {
        return sbbTypePoolMinIdle;
    }

    public EventDeliveryMode getEventDeliveryMode() {
        return eventDeliveryMode;
    }

    public static final class Builder {
        private int eventRouterBufferSize = DEFAULT_RING_BUFFER_SIZE;
        private boolean preferVirtualThreads = true;
        private int sbbPoolMin = DEFAULT_SBB_POOL_MIN;
        private int sbbPoolMax = DEFAULT_SBB_POOL_MAX;
        private boolean sbbPerVirtualThread = true;
        private int sbbTypePoolMinIdle = DEFAULT_SBB_TYPE_POOL_MIN_IDLE;
        private EventDeliveryMode eventDeliveryMode = EventDeliveryMode.SYNC;

        public Builder eventRouterBufferSize(int eventRouterBufferSize) {
            if (eventRouterBufferSize <= 0 || Integer.bitCount(eventRouterBufferSize) != 1) {
                throw new IllegalArgumentException("eventRouterBufferSize must be a positive power of two");
            }
            this.eventRouterBufferSize = eventRouterBufferSize;
            return this;
        }

        public Builder preferVirtualThreads(boolean preferVirtualThreads) {
            this.preferVirtualThreads = preferVirtualThreads;
            return this;
        }

        public Builder sbbPoolMin(int sbbPoolMin) {
            this.sbbPoolMin = sbbPoolMin;
            // Defer range validation until build() so callers can set min+max in any order.
            return this;
        }

        public Builder sbbPoolMax(int sbbPoolMax) {
            this.sbbPoolMax = sbbPoolMax;
            return this;
        }

        public Builder sbbPerVirtualThread(boolean sbbPerVirtualThread) {
            this.sbbPerVirtualThread = sbbPerVirtualThread;
            return this;
        }

        public Builder sbbTypePoolMinIdle(int sbbTypePoolMinIdle) {
            this.sbbTypePoolMinIdle = sbbTypePoolMinIdle;
            return this;
        }

        public Builder eventDeliveryMode(EventDeliveryMode eventDeliveryMode) {
            if (eventDeliveryMode != null) {
                this.eventDeliveryMode = eventDeliveryMode;
            }
            return this;
        }

        public MicroSleeConfiguration build() {
            if (sbbPoolMin < 0) {
                throw new IllegalArgumentException("sbbPoolMin must be >= 0 (was " + sbbPoolMin + ")");
            }
            if (sbbPoolMax < 1) {
                throw new IllegalArgumentException("sbbPoolMax must be >= 1 (was " + sbbPoolMax + ")");
            }
            if (sbbPoolMin > sbbPoolMax) {
                throw new IllegalArgumentException(
                        "sbbPoolMin (" + sbbPoolMin + ") must be <= sbbPoolMax (" + sbbPoolMax + ")");
            }
            if (sbbTypePoolMinIdle < 0) {
                throw new IllegalArgumentException(
                        "sbbTypePoolMinIdle must be >= 0 (was " + sbbTypePoolMinIdle + ")");
            }
            return new MicroSleeConfiguration(this);
        }
    }
}
