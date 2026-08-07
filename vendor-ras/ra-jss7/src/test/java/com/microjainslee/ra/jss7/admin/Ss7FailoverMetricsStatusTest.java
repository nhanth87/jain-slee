/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */
package com.microjainslee.ra.jss7.admin;

import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import com.microjainslee.ra.jss7.cluster.TcapFailoverMetrics;
import org.junit.After;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ADR 0001 P2 — failover / sticky-miss / import-fail counters appear on status scrape.
 */
public class Ss7FailoverMetricsStatusTest {

    @After
    public void tearDown() {
        Ss7AdminBindings.clear();
        Ss7AdminBindings.clearHooks();
    }

    @Test
    public void statusSnapshotExposesFailoverCounters() {
        Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
        Ss7AdminBindings.bind(ra);
        ra.failoverMetrics().stickyMiss();
        ra.failoverMetrics().importFail();
        ra.failoverMetrics().stickyReject();

        Map<String, Object> snap = Ss7LinkStatusSnapshot.capture(ra, "ra-jss7");
        @SuppressWarnings("unchecked")
        Map<String, Long> metrics = (Map<String, Long>) snap.get("failoverMetrics");
        assertNotNull(metrics);
        assertEquals(Long.valueOf(1L), metrics.get(TcapFailoverMetrics.STICKY_MISS));
        assertEquals(Long.valueOf(1L), metrics.get(TcapFailoverMetrics.IMPORT_FAIL));
        assertEquals(Long.valueOf(1L), metrics.get(TcapFailoverMetrics.STICKY_REJECT));
        assertTrue(metrics.containsKey(TcapFailoverMetrics.EXPORT_OK));
        assertTrue(metrics.containsKey(TcapFailoverMetrics.CONTINUE_MISS));
    }

    @Test
    public void unboundStatusStillHasEmptyFailoverMetricsMap() {
        Map<String, Object> snap = Ss7LinkStatusSnapshot.capture(null, "ra-jss7");
        assertNotNull(snap.get("failoverMetrics"));
        assertTrue(((Map<?, ?>) snap.get("failoverMetrics")).isEmpty());
    }
}
