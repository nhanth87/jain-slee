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

import com.microjainslee.api.AlarmLevel;
import com.microjainslee.api.TraceLevel;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class FacilityPortsTest {

    @Test
    public void simpleTracePortSupportsLevels() {
        SimpleTracePort tracer = new SimpleTracePort("test-tracer");
        tracer.trace("info message");
        tracer.trace(TraceLevel.FINE, "fine message");
        tracer.trace(TraceLevel.FINER, "finer message");
    }

    @Test
    public void simpleUsagePortTracksCountersAndSamples() {
        SimpleUsagePort usage = new SimpleUsagePort();
        usage.incrementCounter("calls");
        usage.incrementCounter("calls");
        usage.recordSample("latencyMs", 42);

        assertEquals(2L, usage.getCounter("calls"));
        assertEquals(42L, usage.getLastSample("latencyMs"));
    }

    @Test
    public void simpleAlarmPortTracksActiveAlarms() {
        SimpleAlarmPort alarms = new SimpleAlarmPort();
        alarms.raiseAlarm("link-down", AlarmLevel.MAJOR, "SIG link unavailable");
        assertEquals(AlarmLevel.MAJOR, alarms.getActiveLevel("link-down"));

        alarms.clearAlarm("link-down");
        assertNull(alarms.getActiveLevel("link-down"));
    }

    @Test
    public void inMemoryProfileTableFindsByPrimaryKey() {
        InMemoryProfileTablePort profiles = new InMemoryProfileTablePort();
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("msisdn", "251911000000");
        profiles.put("subscriber", "251911000000", row);

        Map<String, Object> found = profiles.findByPrimaryKey("subscriber", "251911000000");
        assertEquals("251911000000", found.get("msisdn"));
        assertNull(profiles.findByPrimaryKey("subscriber", "missing"));
    }

    @Test
    public void inMemoryNamingPortBindsAndLooksUp() {
        InMemoryNamingPort naming = new InMemoryNamingPort();
        naming.bind("ussd/session", "session-1");
        assertEquals("session-1", naming.lookup("ussd/session"));

        naming.unbind("ussd/session");
        assertNull(naming.lookup("ussd/session"));
    }
}
