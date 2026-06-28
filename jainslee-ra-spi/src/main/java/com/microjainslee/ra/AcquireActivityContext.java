/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra;

import com.microjainslee.api.ActivityContextInterface;

/**
 * Perfect Core S5 — seam for creating / acquiring an
 * {@link ActivityContextInterface} on demand. The kernel binds a
 * real factory (backed by {@code MicroSleeContainer.createActivityContext})
 * while tests bind a stub that returns a
 * {@link DefaultActivityContextInterface}.
 */
@FunctionalInterface
public interface AcquireActivityContext {
    ActivityContextInterface acquire(String name);
}
