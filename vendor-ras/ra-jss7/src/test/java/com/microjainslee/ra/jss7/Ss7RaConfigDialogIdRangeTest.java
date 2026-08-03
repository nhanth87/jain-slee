/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class Ss7RaConfigDialogIdRangeTest {

    @Test
    public void defaultsAreZeroZero() {
        Ss7RaConfig cfg = new Ss7RaConfig();
        assertEquals(0L, cfg.dialogIdRangeStart());
        assertEquals(0L, cfg.dialogIdRangeEnd());
        cfg.validateDialogIdRange();
    }

    @Test
    public void validPartitionAccepted() {
        Ss7RaConfig cfg = new Ss7RaConfig()
                .dialogIdRangeStart(1_000_000L)
                .dialogIdRangeEnd(2_000_000L);
        cfg.validateDialogIdRange();
        assertEquals(1_000_000L, cfg.dialogIdRangeStart());
    }

    @Test
    public void invalidRangeRejected() {
        try {
            new Ss7RaConfig().dialogIdRangeStart(10).dialogIdRangeEnd(5).validateDialogIdRange();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
