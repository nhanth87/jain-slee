/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.camel;

import com.microjainslee.ra.camel.CamelRaConfig.CamelConsumerSpec;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Link-status truth: {@link CamelResourceAdaptor#isActive()} is local lifecycle;
 * peer/broker readiness is {@link CamelResourceAdaptor#isBrokerReady()}.
 */
public class CamelResourceAdaptorBrokerReadyTest {

    private CamelResourceAdaptor ra;

    @After
    public void tearDown() {
        if (ra != null) {
            ra.raUnconfigure();
            ra = null;
        }
    }

    @Test
    public void inactiveIsNotBrokerReady() {
        ra = new CamelResourceAdaptor();
        assertFalse(ra.isActive());
        assertFalse(ra.isBrokerReady());
        assertFalse(ra.isPeerReady());
        assertEquals("camel:inactive", ra.brokerDetail());
        CamelRaEndpoint ep = new CamelRaEndpoint(ra);
        assertFalse(ep.isActive());
        assertFalse(ep.isBrokerReady());
    }

    @Test
    public void activeWithStartedRoutesIsBrokerReady() {
        ra = new CamelResourceAdaptor();
        ra.setConfig(new CamelRaConfig()
                .name("camel-ready-test")
                .consume(CamelConsumerSpec.inOnly("direct:broker-ready")));
        ra.raConfigure();
        ra.raActive();
        assertTrue(ra.isActive());
        assertTrue(ra.isBrokerReady());
        assertTrue(ra.isPeerReady());
        assertTrue(ra.brokerDetail().startsWith("camel:ready"));
        CamelRaEndpoint ep = new CamelRaEndpoint(ra);
        assertTrue(ep.isBrokerReady());
        assertEquals(ra.brokerDetail(), ep.brokerDetail());
    }
}
