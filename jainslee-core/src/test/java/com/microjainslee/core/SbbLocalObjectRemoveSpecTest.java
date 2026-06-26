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
import com.microjainslee.api.TransactionRolledbackLocalException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Spec-aligned {@code setSbbPriority} / {@code getSbbPriority} tests on
 * {@link SimpleSbbLocalObject} — verifies the
 * {@link TransactionRolledbackLocalException} is thrown on an invalid
 * (removed) entity.
 */
public class SbbLocalObjectRemoveSpecTest {

    private static SimpleSbbLocalObject localObject(String id) {
        return new SimpleSbbLocalObject(new SbbID(id), new Sbb() { });
    }

    @Test
    public void setSbbPriorityOnValidEntity() throws Exception {
        SimpleSbbLocalObject lo = localObject("ok");
        lo.setSbbPriority((byte) 42);
        assertEquals((byte) 42, lo.getSbbPriority());
    }

    @Test
    public void setSbbPriorityOnRemovedEntityThrows() {
        SimpleSbbLocalObject lo = localObject("doomed");
        lo.remove();
        try {
            lo.setSbbPriority((byte) 5);
            fail("Expected TransactionRolledbackLocalException");
        } catch (TransactionRolledbackLocalException expected) {
            assertTrue(expected.getMessage().contains("doomed"));
        }
    }

    @Test
    public void getSbbPriorityOnRemovedEntityThrows() {
        SimpleSbbLocalObject lo = localObject("doomed-2");
        lo.remove();
        try {
            lo.getSbbPriority();
            fail("Expected TransactionRolledbackLocalException");
        } catch (TransactionRolledbackLocalException expected) {
            // expected
        }
    }
}