/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.core.offheap;

import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class SegmentedAgronaOffHeapArenaTest {

    private SegmentedAgronaOffHeapArena arena;

    @After
    public void tearDown() {
        if (arena != null) {
            arena.close();
        }
        System.clearProperty(SegmentedAgronaOffHeapArena.PROP_SEGMENT_SLOTS);
    }

    @Test
    public void allocateAcrossSegmentsAndFree() {
        System.setProperty(SegmentedAgronaOffHeapArena.PROP_SEGMENT_SLOTS, "4");
        OffHeapLayout layout = OffHeapLayout.of(
                List.of(OffHeapLayout.FieldSpec.forJavaType("x", long.class, 64)),
                0);
        arena = new SegmentedAgronaOffHeapArena("test", layout, 10, 4);
        assertEquals(10, arena.maxSlots());

        long a0 = arena.allocate("e0");
        long a1 = arena.allocate("e1");
        assertNotEquals(0L, a0);
        assertNotEquals(0L, a1);
        assertEquals(2, arena.occupiedCount());

        arena.free("e0");
        assertEquals(1, arena.occupiedCount());
        assertEquals(0L, arena.resolve("e0"));
        assertEquals(a1, arena.resolve("e1"));
    }

    @Test
    public void needsSegmentationWhenOver2Gb() {
        assertTrue(SegmentedAgronaOffHeapArena.needsSegmentation(256, 20_000_000));
        assertFalse(SegmentedAgronaOffHeapArena.needsSegmentation(64, 1000));
    }
}
