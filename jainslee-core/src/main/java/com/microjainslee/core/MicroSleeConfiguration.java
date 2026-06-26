package com.microjainslee.core;

/**
 * Immutable configuration for the embedded micro JAIN-SLEE container.
 */
public final class MicroSleeConfiguration {

    private static final int DEFAULT_RING_BUFFER_SIZE = 1024;

    private final int eventRouterBufferSize;
    private final boolean preferVirtualThreads;

    private MicroSleeConfiguration(Builder builder) {
        this.eventRouterBufferSize = builder.eventRouterBufferSize;
        this.preferVirtualThreads = builder.preferVirtualThreads;
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

    public static final class Builder {
        private int eventRouterBufferSize = DEFAULT_RING_BUFFER_SIZE;
        private boolean preferVirtualThreads = true;

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

        public MicroSleeConfiguration build() {
            return new MicroSleeConfiguration(this);
        }
    }
}
