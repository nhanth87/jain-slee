/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TcapFailoverMetricsTest {

    @Test
    public void countersIncrementAndSnapshot() {
        TcapFailoverMetrics m = new TcapFailoverMetrics();
        m.exportOk();
        m.exportFail();
        m.importOk();
        m.importFail();
        m.continueMiss();
        m.stickyMiss();
        m.stickyReject();
        m.takeoverOk();

        assertEquals(1, m.exportOkCount());
        assertEquals(1, m.exportFailCount());
        assertEquals(1, m.importOkCount());
        assertEquals(1, m.importFailCount());
        assertEquals(1, m.continueMissCount());
        assertEquals(1, m.stickyMissCount());
        assertEquals(1, m.stickyRejectCount());
        assertEquals(1, m.takeoverOkCount());

        assertTrue(m.snapshot().containsKey(TcapFailoverMetrics.IMPORT_FAIL));
        assertEquals(Long.valueOf(1L), m.snapshot().get(TcapFailoverMetrics.CONTINUE_MISS));
    }

    @Test
    public void sinkReceivesNames() {
        TcapFailoverMetrics m = new TcapFailoverMetrics();
        String[] last = {null};
        m.bindSink(name -> last[0] = name);
        m.stickyMiss();
        assertEquals(TcapFailoverMetrics.STICKY_MISS, last[0]);
    }
}
