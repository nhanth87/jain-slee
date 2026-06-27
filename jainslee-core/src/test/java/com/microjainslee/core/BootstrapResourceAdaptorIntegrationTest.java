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

import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class BootstrapResourceAdaptorIntegrationTest {

    @Test
    public void bootstrapResourceAdaptorWiresContextAndSleeEndpointPort() {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            RaBootstrapContextImpl ctx = container.bootstrapResourceAdaptor(
                    NoOpTestResourceAdaptor.class.getName(), "test-ra");
            assertNotNull(ctx.getResourceAdaptor());
            assertNotNull(ctx.getSleeEndpointPort());
            assertSame(ctx.getEndpoint(), ctx.getSleeEndpointPort());
            assertSame(ctx, ((NoOpTestResourceAdaptor) ctx.getResourceAdaptor())
                    .getResourceAdaptorContext());
        } finally {
            container.stop();
        }
    }

    /** Minimal RA for bootstrap wiring tests (replaces ra-connectors mock). */
    public static final class NoOpTestResourceAdaptor implements ResourceAdaptor {
        private ResourceAdaptorContext context;

        @Override
        public void setResourceAdaptorContext(ResourceAdaptorContext context) {
            this.context = context;
        }

        @Override public void raConfigure() { }
        @Override public void raActive() { }
        @Override public void raStopping() { }
        @Override public void raInactive() { }
        @Override public void raUnconfigure() { }

        ResourceAdaptorContext getResourceAdaptorContext() {
            return context;
        }
    }
}
