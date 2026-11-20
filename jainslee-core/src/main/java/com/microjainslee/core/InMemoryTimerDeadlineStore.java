/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-JVM {@link TimerDeadlineStore}. Used when the cluster module is absent
 * and as the documented behaviour reference for the replicated implementation.
 */
public final class InMemoryTimerDeadlineStore implements TimerDeadlineStore {

    private final ConcurrentHashMap<Long, TimerDeadline> deadlines = new ConcurrentHashMap<>();

    @Override
    public void put(TimerDeadline deadline) {
        deadlines.put(deadline.timerId(), deadline);
    }

    @Override
    public void remove(long timerId) {
        deadlines.remove(timerId);
    }

    @Override
    public TimerDeadline get(long timerId) {
        return deadlines.get(timerId);
    }

    @Override
    public int size() {
        return deadlines.size();
    }
}
