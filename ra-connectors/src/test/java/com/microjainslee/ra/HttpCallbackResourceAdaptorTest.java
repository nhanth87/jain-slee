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
import com.microjainslee.ra.http.HttpCallbackResourceAdaptor;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class HttpCallbackResourceAdaptorTest {

    @Test
    public void bootstrapWiresSleeEndpointPort() {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            RaBootstrapContextImpl ctx = RaBootstrap.activate(
                    container, HttpCallbackResourceAdaptor.class, "http-callback");
            assertNotNull(ctx.getSleeEndpointPort());
            assertSame(ctx.getEndpoint(), ctx.getSleeEndpointPort());
        } finally {
            container.stop();
        }
    }

    @Test
    public void onHttpBeginCreatesActivityAndFiresEvent() {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            RaBootstrapContextImpl ctx = RaBootstrap.activate(
                    container, HttpCallbackResourceAdaptor.class, "http-callback");
            HttpCallbackResourceAdaptor ra =
                    (HttpCallbackResourceAdaptor) ctx.getResourceAdaptor();
            ra.onHttpBegin("sess-1", new com.microjainslee.api.SleeEvent() { });
            assertNotNull(ctx.getActivityContextHandle(
                    new HttpCallbackResourceAdaptor.HttpSessionActivity("sess-1")));
        } finally {
            container.stop();
        }
    }
}
