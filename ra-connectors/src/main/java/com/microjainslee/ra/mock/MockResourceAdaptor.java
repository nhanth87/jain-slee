/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.mock;

import com.microjainslee.api.*;

/**
 * Mock Resource Adaptor for testing.
 */
public class MockResourceAdaptor implements ResourceAdaptor {
    private ResourceAdaptorContext context;

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
    }

    @Override
    public void raConfigure() {}

    @Override
    public void raActive() {}

    @Override
    public void raStopping() {}

    @Override
    public void raInactive() {}

    @Override
    public void raUnconfigure() {}

    public void sendEvent(SleeEvent event) {
        // Mock event sending
    }
}