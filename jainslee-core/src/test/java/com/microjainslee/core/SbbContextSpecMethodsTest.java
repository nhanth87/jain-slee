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
import com.microjainslee.api.SbbContext;
import com.microjainslee.api.SbbID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the spec-aligned {@link SbbContext} methods added in the
 * audit pass — {@code setRollbackOnly / getRollbackOnly}, {@code getSbb},
 * {@code getEventMask}, and the throws clause on {@code getSbbLocalObject}.
 */
public class SbbContextSpecMethodsTest {

    private static SimpleSbbContext freshContext() {
        return new SimpleSbbContext(
                new com.microjainslee.api.ServiceID("svc", "com.microjainslee", "1.0"),
                null, new SbbID("TestSbb"), null, null);
    }

    @Test
    public void rollbackFlagStartsFalse() {
        SimpleSbbContext ctx = freshContext();
        assertFalse(ctx.getRollbackOnly());
    }

    @Test
    public void setRollbackOnlyFlipsFlag() {
        SimpleSbbContext ctx = freshContext();
        ctx.setRollbackOnly();
        assertTrue(ctx.getRollbackOnly());
    }

    @Test
    public void getSbbReturnsProvidedSbbID() {
        SimpleSbbContext ctx = freshContext();
        SbbID id = ctx.getSbb();
        assertNotNull(id);
        assertEquals("TestSbb", id.getId());
    }

    @Test
    public void getEventMaskDefaultsToEmpty() {
        SimpleSbbContext ctx = freshContext();
        assertNotNull(ctx.getEventMask());
        assertTrue(ctx.getEventMask().isEmpty());
    }

    @Test
    public void getSbbLocalObjectOnRemovedEntityThrows() {
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(new SbbID("removable"), new Sbb() { });
        lo.remove();
        SimpleSbbContext ctx = new SimpleSbbContext(
                new com.microjainslee.api.ServiceID("svc", "com.microjainslee", "1.0"),
                lo, new SbbID("TestSbb"), null, null);
        try {
            ctx.getSbbLocalObject();
            org.junit.Assert.fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("no longer valid"));
        }
    }
}