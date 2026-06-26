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

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;
import com.microjainslee.ra.mock.MockResourceAdaptor;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class RaBootstrapTest {

    @Test
    public void activate_wiresMockResourceAdaptorContext() {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            RaBootstrapContextImpl context = RaBootstrap.activate(
                    container, MockResourceAdaptor.class, "mock-ra-entity");
            assertNotNull(context);
            assertNotNull(context.getResourceAdaptor());
            assertSame(context, ((MockResourceAdaptor) context.getResourceAdaptor())
                    .getResourceAdaptorContext());
        } finally {
            container.stop();
        }
    }
}
