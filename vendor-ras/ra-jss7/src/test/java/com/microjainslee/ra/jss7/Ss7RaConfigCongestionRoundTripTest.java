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
 * DESIGN §10.2 (P1+P2) congestion-control config: safe defaults + fluent
 * setter/getter round-trip + restriction-level range validation.
 */
public class Ss7RaConfigCongestionRoundTripTest {

    @Test
    public void defaultsAreSafe() {
        Ss7RaConfig cfg = new Ss7RaConfig();
        assertFalse(cfg.congestionControlBlockingOutgoingSccpMessages());
        assertEquals(0, cfg.defaultRestrictionLevel());
        assertEquals(Ss7RaConfig.TRAFFIC_MODE_LOADSHARE, cfg.defaultTrafficMode());
    }

    @Test
    public void congestionFieldsRoundTrip() {
        Ss7RaConfig cfg = new Ss7RaConfig()
                .congestionControlBlockingOutgoingSccpMessages(true)
                .defaultRestrictionLevel(5)
                .defaultTrafficMode("override");
        assertTrue(cfg.congestionControlBlockingOutgoingSccpMessages());
        assertEquals(5, cfg.defaultRestrictionLevel());
        assertEquals(Ss7RaConfig.TRAFFIC_MODE_OVERRIDE, cfg.defaultTrafficMode());

        String s = cfg.toString();
        assertTrue(s.contains("congBlock=true"));
        assertTrue(s.contains("restrictionLevel=5"));
        assertTrue(s.contains("trafficMode=override"));
    }

    @Test
    public void restrictionLevelBounds() {
        for (int level : new int[] { 0, 1, 4, 8 }) {
            Ss7RaConfig cfg = new Ss7RaConfig().defaultRestrictionLevel(level);
            assertEquals(level, cfg.defaultRestrictionLevel());
        }
    }

    @Test
    public void restrictionLevelOutOfRangeRejected() {
        for (int bad : new int[] { -1, 9, Integer.MIN_VALUE, Integer.MAX_VALUE }) {
            try {
                new Ss7RaConfig().defaultRestrictionLevel(bad);
                fail("expected IllegalArgumentException for level " + bad);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("0..8"));
            }
        }
    }

    @Test
    public void invalidRestrictionLevelLeavesPreviousValue() {
        Ss7RaConfig cfg = new Ss7RaConfig().defaultRestrictionLevel(3);
        try {
            cfg.defaultRestrictionLevel(9);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        assertEquals(3, cfg.defaultRestrictionLevel());
    }
}