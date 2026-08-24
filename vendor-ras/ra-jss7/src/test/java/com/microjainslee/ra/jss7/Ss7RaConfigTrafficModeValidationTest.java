/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * DESIGN §10.2/P4 traffic-mode policy: loadshare (default) and override are the
 * only accepted AS traffic modes; broadcast is forbidden on transit links;
 * override under active-active must produce a WARN.
 */
public class Ss7RaConfigTrafficModeValidationTest {

    @Test
    public void loadshareIsDefaultAndAccepted() {
        Ss7RaConfig cfg = new Ss7RaConfig();
        assertEquals(Ss7RaConfig.TRAFFIC_MODE_LOADSHARE, cfg.defaultTrafficMode());
        cfg.defaultTrafficMode("loadshare");
        assertEquals("loadshare", cfg.defaultTrafficMode());
        assertFalse(cfg.warnIfOverrideTrafficMode(StpTransitProfile.HaMode.ACTIVE_ACTIVE));
    }

    @Test
    public void loadshareIsCaseInsensitive() {
        Ss7RaConfig cfg = new Ss7RaConfig().defaultTrafficMode("LoadShare");
        assertEquals("loadshare", cfg.defaultTrafficMode());
    }

    @Test
    public void overrideAcceptedButWarnsUnderActiveActive() {
        Ss7RaConfig cfg = new Ss7RaConfig().defaultTrafficMode("override");
        assertEquals(Ss7RaConfig.TRAFFIC_MODE_OVERRIDE, cfg.defaultTrafficMode());
        // WARN expected: override is for active-standby only (DESIGN §10.2/P4)
        assertTrue(cfg.warnIfOverrideTrafficMode(StpTransitProfile.HaMode.ACTIVE_ACTIVE));
        // null haMode = ADR default ACTIVE_ACTIVE → also warns
        assertTrue(cfg.warnIfOverrideTrafficMode(null));
        // active-standby is the legitimate override fabric → no warning
        assertFalse(cfg.warnIfOverrideTrafficMode(StpTransitProfile.HaMode.ACTIVE_STANDBY));
    }

    @Test
    public void loadshareNeverWarns() {
        Ss7RaConfig cfg = new Ss7RaConfig();
        assertFalse(cfg.warnIfOverrideTrafficMode(StpTransitProfile.HaMode.ACTIVE_ACTIVE));
        assertFalse(cfg.warnIfOverrideTrafficMode(StpTransitProfile.HaMode.ACTIVE_STANDBY));
    }

    @Test
    public void broadcastRejected() {
        try {
            new Ss7RaConfig().defaultTrafficMode("broadcast");
            fail("expected IllegalArgumentException for broadcast");
        } catch (IllegalArgumentException expected) {
            assertEquals("broadcast forbidden on transit links — DESIGN §10.2/P4",
                    expected.getMessage());
        }
        // BROADCAST (upper case) must be rejected just the same
        try {
            new Ss7RaConfig().defaultTrafficMode("BROADCAST");
            fail("expected IllegalArgumentException for BROADCAST");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("broadcast forbidden"));
        }
    }

    @Test
    public void broadcastRejectionLeavesPreviousValue() {
        Ss7RaConfig cfg = new Ss7RaConfig();
        try {
            cfg.defaultTrafficMode("broadcast");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals(Ss7RaConfig.TRAFFIC_MODE_LOADSHARE, cfg.defaultTrafficMode());
    }

    @Test
    public void unknownValuesRejected() {
        for (String bad : new String[] { "roundrobin", "multicast", " loadshare-ish ", "", "  " }) {
            try {
                new Ss7RaConfig().defaultTrafficMode(bad);
                fail("expected IllegalArgumentException for '" + bad + "'");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("loadshare|override"));
            }
        }
        try {
            new Ss7RaConfig().defaultTrafficMode(null);
            fail("expected IllegalArgumentException for null");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}