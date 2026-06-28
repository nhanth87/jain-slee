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
import com.microjainslee.api.NullActivityFactory;

/**
 * Perfect Core S5 — fallback {@link NullActivityFactory} used when the
 * RA context is built without a kernel reference (tests, isolated RAs).
 * Returns a {@link DefaultActivityContextInterface}; the production
 * path through {@code ResourceAdaptorContextBuilder} substitutes the
 * kernel-backed implementation.
 */
public final class SimpleNullActivityFactory implements NullActivityFactory {

    public static final SimpleNullActivityFactory INSTANCE = new SimpleNullActivityFactory();

    private SimpleNullActivityFactory() {}

    @Override
    public ActivityContextInterface createNullActivity(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name is required");
        }
        return new DefaultActivityContextInterface(name, name);
    }
}
