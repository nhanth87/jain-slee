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

import com.microjainslee.api.Sbb;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Configuration for a per-class {@link SbbTypePool}.
 */
public final class SbbTypePoolConfig {

    private final int minIdle;
    private final int maxActive;
    private final Supplier<Sbb> factory;
    private final Consumer<Sbb> resetHook;
    private final EventMask defaultEventMask;

    private SbbTypePoolConfig(Builder builder) {
        this.minIdle = builder.minIdle;
        this.maxActive = builder.maxActive;
        this.factory = builder.factory;
        this.resetHook = builder.resetHook;
        this.defaultEventMask = builder.defaultEventMask != null
                ? builder.defaultEventMask : EventMask.ACCEPT_ALL;
    }

    public static Builder builder(Supplier<Sbb> factory) {
        return new Builder(factory);
    }

    public int getMinIdle() {
        return minIdle;
    }

    public int getMaxActive() {
        return maxActive;
    }

    public Supplier<Sbb> getFactory() {
        return factory;
    }

    public Consumer<Sbb> getResetHook() {
        return resetHook;
    }

    public EventMask getDefaultEventMask() {
        return defaultEventMask;
    }

    public static final class Builder {
        private final Supplier<Sbb> factory;
        private int minIdle;
        private int maxActive = 4096;
        private Consumer<Sbb> resetHook;
        private EventMask defaultEventMask;

        private Builder(Supplier<Sbb> factory) {
            if (factory == null) {
                throw new IllegalArgumentException("factory is required");
            }
            this.factory = factory;
        }

        public Builder minIdle(int minIdle) {
            this.minIdle = minIdle;
            return this;
        }

        public Builder maxActive(int maxActive) {
            this.maxActive = maxActive;
            return this;
        }

        public Builder resetHook(Consumer<Sbb> resetHook) {
            this.resetHook = resetHook;
            return this;
        }

        public Builder defaultEventMask(EventMask defaultEventMask) {
            this.defaultEventMask = defaultEventMask;
            return this;
        }

        public SbbTypePoolConfig build() {
            if (maxActive < 1) {
                throw new IllegalArgumentException("maxActive must be >= 1");
            }
            if (minIdle < 0 || minIdle > maxActive) {
                throw new IllegalArgumentException("minIdle must be in [0, maxActive]");
            }
            return new SbbTypePoolConfig(this);
        }
    }
}
