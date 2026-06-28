/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.ies;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link InitialEventSelectResult} — builder + shortcuts.
 */
public class InitialEventSelectResultTest {

    @Test
    public void builder_producesDefaults() {
        InitialEventSelectResult r = InitialEventSelectResult.builder().build();
        assertNull("default convergence is stateless", r.getConvergenceName());
        assertTrue("default is initialEvent=true", r.isInitialEvent());
    }

    @Test
    public void builder_setsConvergenceAndInitial() {
        InitialEventSelectResult r = InitialEventSelectResult.builder()
                .convergenceName("447911123456:d5")
                .initialEvent(false)
                .build();
        assertEquals("447911123456:d5", r.getConvergenceName());
        assertFalse(r.isInitialEvent());
    }

    @Test
    public void statelessShortcut_isInitialNoConvergence() {
        InitialEventSelectResult r = InitialEventSelectResult.stateless();
        assertNotNull(r);
        assertNull(r.getConvergenceName());
        assertTrue(r.isInitialEvent());
    }

    @Test
    public void forSessionShortcut_passesArgs() {
        InitialEventSelectResult begin =
                InitialEventSelectResult.forSession("k1", true);
        assertEquals("k1", begin.getConvergenceName());
        assertTrue(begin.isInitialEvent());

        InitialEventSelectResult cont =
                InitialEventSelectResult.forSession("k2", false);
        assertEquals("k2", cont.getConvergenceName());
        assertFalse(cont.isInitialEvent());
    }

    @Test
    public void toString_includesBothFields() {
        String s = InitialEventSelectResult.builder()
                .convergenceName("x")
                .initialEvent(true)
                .build().toString();
        assertTrue("toString should mention convergence", s.contains("x"));
        assertTrue("toString should mention initial", s.contains("true"));
    }
}
