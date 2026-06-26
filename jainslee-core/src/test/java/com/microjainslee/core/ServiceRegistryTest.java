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

import com.microjainslee.api.ServiceID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceRegistryTest {

    @Test
    public void activateAndStop_transitionLifecycle() {
        ServiceRegistry registry = new ServiceRegistry();
        ServiceID id = new ServiceID("Demo", "com.example", "1.0");

        assertEquals(ServiceState.INACTIVE, registry.getState(id));
        registry.activate(id);
        assertEquals(ServiceState.ACTIVE, registry.getState(id));
        assertTrue(registry.isActive(id));

        registry.stop(id);
        assertEquals(ServiceState.INACTIVE, registry.getState(id));
        assertFalse(registry.isActive(id));
    }
}
