/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class Ss7RaConfigEndpointTest {

    @Test
    public void resolvedLocalEndpointFromIndex() {
        Ss7RaConfig cfg = new Ss7RaConfig()
                .sctpLocalEndpoints(List.of("10.0.0.1:2905", "10.0.0.2:2905"))
                .sctpEndpointIndex(1);
        assertEquals("10.0.0.2:2905", cfg.resolvedLocalEndpoint());
        assertEquals(2, cfg.allLocalEndpoints().size());
    }

    @Test
    public void resolvedLocalEndpointFallsBackToHostPort() {
        Ss7RaConfig cfg = new Ss7RaConfig().hostIp("192.168.1.1").hostPort(2910);
        assertEquals("192.168.1.1:2910", cfg.resolvedLocalEndpoint());
    }
}
