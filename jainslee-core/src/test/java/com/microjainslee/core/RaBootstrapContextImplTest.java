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

import com.microjainslee.api.ActivityContextHandle;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class RaBootstrapContextImplTest {

    @Test
    public void createAndLookupActivityContextHandle() {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            RaBootstrapContextImpl context = new RaBootstrapContextImpl(container, "mock-ra");
            Object activity = new Object();

            assertNull(context.getActivityContextHandle(activity));

            ActivityContextHandle created = context.createActivityContextHandle(activity);
            assertNotNull(created);
            assertSame(created, context.getActivityContextHandle(activity));
            assertEquals(created, context.createActivityContextHandle(activity));
            assertNotNull(container.getActivityContextNamingFacility().lookup(created.getId()));
        } finally {
            container.stop();
        }
    }
}
