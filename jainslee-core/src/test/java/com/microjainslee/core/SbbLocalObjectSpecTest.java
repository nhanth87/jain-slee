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

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for spec-aligned methods on {@link SimpleSbbLocalObject}:
 * {@code isIdentical}, {@code getSbbPriority}, {@code setSbbPriority}.
 */
public class SbbLocalObjectSpecTest {

    private static SbbLocalObject localObject(String id, int priority) {
        return new SimpleSbbLocalObject(new SbbID(id), new Sbb() { }, null, null, priority);
    }

    @Test
    public void getSbbPriorityMatchesSet() {
        SbbLocalObject lo = localObject("a", 5);
        lo.setPriority(5);
        assertEquals((byte) 5, lo.getSbbPriority());
    }

    @Test
    public void setSbbPriorityAcceptsFullByteRange() {
        SbbLocalObject lo = localObject("a", 0);
        lo.setPriority(-128);
        assertEquals((byte) -128, lo.getSbbPriority());
        lo.setPriority(127);
        assertEquals((byte) 127, lo.getSbbPriority());
    }

    @Test
    public void isIdenticalForSameEntity() {
        SbbLocalObject a = localObject("same", 0);
        SbbLocalObject b = localObject("same", 0);
        assertTrue(a.isIdentical(b));
        assertTrue(b.isIdentical(a));
    }

    @Test
    public void isIdenticalDifferentId() {
        SbbLocalObject a = localObject("alpha", 0);
        SbbLocalObject b = localObject("beta", 0);
        assertFalse(a.isIdentical(b));
    }

    @Test
    public void isIdenticalNullReturnsFalse() {
        SbbLocalObject a = localObject("a", 0);
        assertFalse(a.isIdentical(null));
    }

    @Test
    public void isIdenticalRemovedReturnsFalse() {
        SbbLocalObject a = new SimpleSbbLocalObject(new SbbID("gone"), new Sbb() { },
                null, null, 0);
        SbbLocalObject b = localObject("gone", 0);
        // mark 'a' as removed (without firing the listener)
        a.remove();
        assertTrue(a.isRemoved());
        assertFalse(a.isIdentical(b));
        assertFalse(b.isIdentical(a));
    }
}