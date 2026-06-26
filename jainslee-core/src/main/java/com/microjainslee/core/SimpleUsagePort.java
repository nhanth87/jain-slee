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

    @Override
    public void incrementCounter(String counterName) {
        if (counterName == null) {
            return;
        }
        AtomicLong existing = counters.get(counterName);
        if (existing == null) {
            AtomicLong created = new AtomicLong();
            existing = counters.putIfAbsent(counterName, created);
            if (existing == null) {
                existing = created;
            }
        }
        existing.incrementAndGet();
    }

    public long getCounter(String counterName) {
        AtomicLong counter = counters.get(counterName);
        return counter == null ? 0 : counter.get();
    }
}
