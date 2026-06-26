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
import static org.junit.Assert.assertTrue;

public class MicroSleeContainerAutoDeployTest {

    @Test
    public void start_autoRegistersSbbsFromClasspathIndex() {
        MicroSleeContainer container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
                        .preferVirtualThreads(false)
                        .build());
        container.start();
        try {
            ServiceID serviceID = new ServiceID("FixtureDU", "com.microjainslee", "1.0");
            assertTrue(container.getServiceRegistry().isActive(serviceID));
            assertEquals(ServiceState.ACTIVE, container.getServiceRegistry().getState(serviceID));
        } finally {
            container.stop();
        }
    }
}
