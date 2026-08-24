/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.api;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * ADR 0004 P0-1 — the api-side {@link ProfileAccessorInvoker} is a delegating
 * facade over {@link ProfileAccessorBridge}; the split-package stub that threw
 * {@link UnsupportedOperationException} is gone.
 *
 * <p>This module's test classpath contains ONLY jainslee-api (no core), so the
 * no-bridge path is exercised for real: calls must fail with an
 * {@link IllegalStateException} whose message names the bridge service —
 * not UOE, not a silent null.
 */
public class ProfileAccessorInvokerFacadeTest {

    private static final class RecordingBridge implements ProfileAccessorBridge {
        Object lastRead;
        Object toReturn = "stored-value";
        Method lastGetter;
        Method lastSetter;
        Object lastWrite;

        @Override
        public Object getValue(Profile profile, Method getter) {
            this.lastGetter = getter;
            return toReturn;
        }

        @Override
        public void setValue(Profile profile, Method setter, Object value) {
            this.lastSetter = setter;
            this.lastWrite = value;
        }
    }

    private static Method anyGetter() throws NoSuchMethodException {
        return SampleProfile.class.getDeclaredMethod("getCallsign");
    }

    private static Method anySetter() throws NoSuchMethodException {
        return SampleProfile.class.getDeclaredMethod("setCallsign", String.class);
    }

    /** Minimal profile double — identity irrelevant, only accessors matter. */
    private abstract static class SampleProfile extends ProfileAbstractCmp {
        public abstract String getCallsign();
        public abstract void setCallsign(String value);
        public abstract boolean isFlag();
    }

    @After
    public void resetBridge() {
        ProfileAccessorInvoker.uninstall();
    }

    @Test
    public void fieldNameForPreservesJavaBeansSemantics() throws Exception {
        assertEquals("callsign", ProfileAccessorInvoker.fieldNameFor(anyGetter()));
        assertEquals("callsign", ProfileAccessorInvoker.fieldNameFor(anySetter()));
        Method isFlag = SampleProfile.class.getDeclaredMethod("isFlag");
        assertEquals("flag", ProfileAccessorInvoker.fieldNameFor(isFlag));
        assertThrows(IllegalArgumentException.class,
                () -> ProfileAccessorInvoker.fieldNameFor(null));
        // Not a JavaBeans accessor at all.
        Method bogus = String.class.getDeclaredMethod("charAt", int.class);
        assertThrows(IllegalArgumentException.class,
                () -> ProfileAccessorInvoker.fieldNameFor(bogus));
    }

    @Test
    public void installRequiresBridge() {
        assertThrows(IllegalArgumentException.class, () -> ProfileAccessorInvoker.install(null));
    }

    @Test
    public void installedRoundTripsThroughExplicitBridge() throws Exception {
        RecordingBridge bridge = new RecordingBridge();
        ProfileAccessorInvoker.install(bridge);
        assertSame(bridge, ProfileAccessorInvoker.installed());

        Method getter = anyGetter();
        assertEquals("stored-value", ProfileAccessorInvoker.getValue(null, getter));
        assertSame(getter, bridge.lastGetter);

        ProfileAccessorInvoker.setValue(null, anySetter(), "nv");
        assertEquals("nv", bridge.lastWrite);
    }

    /**
     * No explicit install here and no META-INF/services provider on the
     * api-only classpath → resolution must fail loudly and actionably.
     * (Against the pre-ADR stub this exact call threw UnsupportedOperationException.)
     */
    @Test
    public void missingBridgeFailsFastWithActionableMessage() throws Exception {
        // Ensure no explicit install leaks in from another test ordering.
        assertNull(ProfileAccessorInvoker.installed());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ProfileAccessorInvoker.getValue(null, anyGetter()));
        assertTrue("message should name the service file: " + ex.getMessage(),
                ex.getMessage().contains("com.microjainslee.api.ProfileAccessorBridge"));
        assertTrue("message should name the fix: " + ex.getMessage(),
                ex.getMessage().contains("jainslee-core"));
        // NOTE: the pre-ADR stub threw UnsupportedOperationException here —
        // this test asserting IllegalStateException IS the red/green boundary.
    }
}
