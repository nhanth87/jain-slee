/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Link-status truth: {@link Ss7ResourceAdaptor#isActive()} is local lifecycle only;
 * peer route readiness is {@link Ss7ResourceAdaptor#isM3uaRouteReady()}.
 */
public class Ss7ResourceAdaptorRouteReadyTest {

    @Test
    public void inactiveRaIsNotM3uaRouteReady() {
        Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
        assertFalse(ra.isActive());
        assertFalse(ra.isM3uaRouteReady());
        Ss7RaEndpoint ep = new Ss7RaEndpoint(ra);
        assertFalse(ep.isActive());
        assertFalse(ep.isM3uaRouteReady());
    }

    @Test
    public void endpointDelegatesRouteReadyToAdaptor() {
        Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
        Ss7RaEndpoint ep = new Ss7RaEndpoint(ra, "ra-jss7-lab");
        assertTrue(ep.getRaName().equals("ra-jss7-lab"));
        assertFalse("LISTEN/lifecycle alone must not imply route ready",
                ep.isM3uaRouteReady());
    }
}
