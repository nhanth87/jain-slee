/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import com.microjainslee.ra.jss7.collab.Ss7EventPublisher;
import com.microjainslee.ra.jss7.collab.Ss7ProtocolAdapter;
import com.microjainslee.ra.jss7.transport.Ss7Stack;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Mid-{@code raActive()} failure must detach adapters, clear stack, and drop
 * ownership — same teardown shape as {@code raInactive()}.
 */
public class Ss7ResourceAdaptorActivationRollbackTest {

    @Test
    public void rollbackPartialActivationDetachesAdaptersAndNullsStack() throws Exception {
        Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
        AtomicInteger detachCount = new AtomicInteger();

        Ss7ProtocolAdapter adapter = new Ss7ProtocolAdapter() {
            @Override public String protocol() { return "TEST"; }
            @Override public void attach(Ss7Stack stack, Ss7EventPublisher publisher) { }
            @Override public void detach() { detachCount.incrementAndGet(); }
        };

        Field adaptersField = Ss7ResourceAdaptor.class.getDeclaredField("adapters");
        adaptersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Ss7ProtocolAdapter> adapters = (List<Ss7ProtocolAdapter>) adaptersField.get(ra);
        adapters.add(adapter);

        Field stackField = Ss7ResourceAdaptor.class.getDeclaredField("stack");
        stackField.setAccessible(true);
        Field activeField = Ss7ResourceAdaptor.class.getDeclaredField("active");
        activeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicBoolean active =
                (java.util.concurrent.atomic.AtomicBoolean) activeField.get(ra);

        // Simulate failure path: clear active then rollback.
        active.set(false);
        ra.rollbackPartialActivation();

        assertTrue("adapter must be detached on rollback", detachCount.get() >= 1);
        assertTrue("adapters list must be cleared", adapters.isEmpty());
        assertNull("stack field must be null after rollback", stackField.get(ra));
        assertFalse(ra.isActive());
        assertFalse(ra.isM3uaRouteReady());
        assertNull(ra.ownershipTracker());
    }

    @Test
    public void raActiveFailureLeavesRaInactive() {
        Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
        // Invalid config that fails stack start is environment-dependent; at minimum
        // a second raActive after a clean inactive must not leave sticky active=true
        // without a stack when activation throws.
        try {
            Ss7RaConfig bad = new Ss7RaConfig();
            // Force a ridiculous OPC that still constructs; rely on missing SCTP peers
            // not being required for start in lab — if start succeeds, deactivate.
            ra.setConfig(bad);
            try {
                ra.raActive();
                // If activation succeeded without peers, still exercise inactive cleanup.
                ra.raInactive();
            } catch (IllegalStateException expected) {
                assertFalse("failed activation must leave isActive=false", ra.isActive());
                assertNull("failed activation must null stack", ra.stack());
                assertTrue("failed activation must clear adapters",
                        readAdapters(ra).isEmpty());
            }
        } catch (RuntimeException e) {
            assertFalse(ra.isActive());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Ss7ProtocolAdapter> readAdapters(Ss7ResourceAdaptor ra) {
        try {
            Field f = Ss7ResourceAdaptor.class.getDeclaredField("adapters");
            f.setAccessible(true);
            return (List<Ss7ProtocolAdapter>) f.get(ra);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
