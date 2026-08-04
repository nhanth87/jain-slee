/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.jss7.admin;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Ss7LinkStatusSnapshotTest {

    @Test
    public void synthesizeDetailListeningVsRouteUp() {
        String peerDown = Ss7LinkStatusSnapshot.synthesizeSs7Detail(
                false, false, true, true, false, false, "ss7=applied");
        assertTrue(peerDown.contains("listening"));
        assertTrue(peerDown.contains("m3ua-not-ready"));
        assertFalse(peerDown.contains("route=up"));

        String routeUp = Ss7LinkStatusSnapshot.synthesizeSs7Detail(
                false, true, true, true, true, true, "ss7=applied");
        assertTrue(routeUp.contains("route=up"));

        String stopped = Ss7LinkStatusSnapshot.synthesizeSs7Detail(
                true, true, true, true, true, true, "ss7=stopped");
        assertEquals("ss7=stopped", stopped);
    }

    @Test
    public void resolveAssociationLocalFallsBackToServer() {
        assertEquals("127.0.0.1:2905",
                Ss7LinkStatusSnapshot.resolveAssociationLocal(null, 0, "127.0.0.1:2905"));
        assertEquals("127.0.0.1:2905",
                Ss7LinkStatusSnapshot.resolveAssociationLocal("null", 0, "127.0.0.1:2905"));
        assertEquals("10.0.0.1:2905",
                Ss7LinkStatusSnapshot.resolveAssociationLocal("10.0.0.1", 2905, "127.0.0.1:2905"));
    }

    @Test
    public void formatHostPortRejectsNullish() {
        assertEquals("—", Ss7LinkStatusSnapshot.formatHostPort(null, 0));
        assertEquals("—", Ss7LinkStatusSnapshot.formatHostPort("null", 2905));
        assertEquals("127.0.0.1:2905", Ss7LinkStatusSnapshot.formatHostPort("127.0.0.1", 2905));
    }

    @Test
    public void captureUnboundHasEmptyArraysAndFalseRoute() {
        Map<String, Object> m = Ss7LinkStatusSnapshot.capture(null, null);
        assertFalse((Boolean) m.get("active"));
        assertFalse((Boolean) m.get("routeReady"));
        assertFalse((Boolean) m.get("bound"));
        assertTrue(((java.util.List<?>) m.get("servers")).isEmpty());
        assertTrue(((java.util.List<?>) m.get("associations")).isEmpty());
        assertTrue(((java.util.List<?>) m.get("asps")).isEmpty());
        assertTrue(((java.util.List<?>) m.get("applicationServers")).isEmpty());
        assertEquals("ss7=n/a", m.get("detail"));
    }
}
