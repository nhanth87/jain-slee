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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic allocator for internal entity ids.
 */
final class EntityIdAllocator {

    private final AtomicLong next = new AtomicLong(1L);

    long allocate() {
        return next.getAndIncrement();
    }
}
