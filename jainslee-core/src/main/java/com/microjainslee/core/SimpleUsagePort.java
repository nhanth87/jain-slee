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

import com.microjainslee.api.UsagePort;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory usage counters for embedded mode.
 */
public final class SimpleUsagePort implements UsagePort {

    private final ConcurrentHashMap<String, AtomicLong> counters =
            new ConcurrentHashMap<String, AtomicLong>();
    private final ConcurrentHashMap<String, AtomicLong> lastSamples =
            new ConcurrentHashMap<String, AtomicLong>();

    @Override
    public void incrementCounter(String counterName) {
        if (counterName == null) {
            return;
        }
        counterFor(counterName).incrementAndGet();
    }

    @Override
    public void recordSample(String parameterName, long value) {
        if (parameterName == null) {
            return;
        }
        AtomicLong existing = lastSamples.get(parameterName);
        if (existing == null) {
            AtomicLong created = new AtomicLong(value);
            existing = lastSamples.putIfAbsent(parameterName, created);
            if (existing == null) {
                existing = created;
            } else {
                existing.set(value);
            }
        } else {
            existing.set(value);
        }
    }

    public long getCounter(String counterName) {
        AtomicLong counter = counters.get(counterName);
        return counter == null ? 0 : counter.get();
    }

    public long getLastSample(String parameterName) {
        AtomicLong sample = lastSamples.get(parameterName);
        return sample == null ? 0 : sample.get();
    }

    private AtomicLong counterFor(String name) {
        AtomicLong existing = counters.get(name);
        if (existing == null) {
            AtomicLong created = new AtomicLong();
            existing = counters.putIfAbsent(name, created);
            if (existing == null) {
                existing = created;
            }
        }
        return existing;
    }
}
