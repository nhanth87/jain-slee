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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RolledBackContext;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link SimpleRolledBackContext} — covers the corrected
 * JAIN-SLEE 1.1 §6.10.1.1 shape (getEvent / getActivityContextInterface /
 * isRemoveRolledBack).
 */
public class RolledBackContextImplTest {

    @Test
    public void forCascadingRemoveHasRemoveFlag() {
        SimpleRolledBackContext ctx = SimpleRolledBackContext.forCascadingRemove();
        assertNotNull(ctx);
        assertTrue(ctx.isRemoveRolledBack());
        assertNull(ctx.getEvent());
        assertNull(ctx.getActivityContextInterface());
    }

    @Test
    public void preservesEventAndAciReferences() {
        Object event = new Object();
        ActivityContextInterface aci = new com.microjainslee.api.ActivityContextInterface() {
            @Override public String getActivityContextName() { return "test-aci"; }
            @Override public void attach(com.microjainslee.api.SbbLocalObject s) { }
            @Override public void detach(com.microjainslee.api.SbbLocalObject s) { }
        };
        SimpleRolledBackContext ctx = new SimpleRolledBackContext(event, aci, false);
        assertSame(event, ctx.getEvent());
        assertSame(aci, ctx.getActivityContextInterface());
        assertFalse(ctx.isRemoveRolledBack());
    }

    @Test
    public void implementsSpecInterface() {
        SimpleRolledBackContext ctx = new SimpleRolledBackContext(null, null, false);
        assertTrue(ctx instanceof RolledBackContext);
    }
}