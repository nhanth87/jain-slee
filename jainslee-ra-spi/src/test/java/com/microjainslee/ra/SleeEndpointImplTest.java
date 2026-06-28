/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra;

import com.microjainslee.api.ActivityAlreadyExistsException;
import com.microjainslee.api.ActivityContextHandle;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityContextNamingFacility;
import com.microjainslee.api.EventTypeId;
import com.microjainslee.api.EventType;
import com.microjainslee.api.FiredUnrecognizedEventException;
import com.microjainslee.api.FireableEventType;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.UnrecognizedActivityException;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SleeEndpointImplTest {

    /** Stub RA that allows direct state-machine manipulation. */
    static final class StubRa implements ResourceAdaptor {
        @Override public void setResourceAdaptorContext(com.microjainslee.api.ResourceAdaptorContext ctx) {}
        @Override public void raConfigure() {}
        @Override public void raActive() {}
        @Override public void raStopping() {}
        @Override public void raInactive() {}
        @Override public void raUnconfigure() {}
    }

    /** Stub ACNF backed by a map. */
    static final class StubAcnf implements ActivityContextNamingFacility {
        final ConcurrentHashMap<String, ActivityContextInterface> map = new ConcurrentHashMap<>();
        @Override public void bind(String name, ActivityContextInterface aci) { map.put(name, aci); }
        @Override public ActivityContextInterface lookup(String name) { return map.get(name); }
        @Override public void unbind(String name) { map.remove(name); }
        @Override public java.util.Collection<ActivityContextInterface> getBoundContexts() { return map.values(); }
        @Override public java.util.Set<String> names() { return map.keySet(); }
        @Override public void clear() { map.clear(); }
    }

    /** Event router stub that records every routing call. */
    static final class CapturingRouter implements EventRouterPort {
        final CopyOnWriteArrayList<Object> routed = new CopyOnWriteArrayList<>();
        @Override public void routeEvent(Object event, ActivityContextInterface aci) {
            routed.add(event);
        }
    }

    /** Activity handle used in tests. */
    static final class TestHandle implements com.microjainslee.api.ActivityHandle {
        private final String id;
        TestHandle(String id) { this.id = id; }
        @Override public String getId() { return id; }
        @Override public String toString() { return "TestHandle[" + id + "]"; }
    }

    static final class TestSleeEvent implements SleeEvent {
        private final String name;
        TestSleeEvent(String name) { this.name = name; }
        @Override public String toString() { return "TestSleeEvent[" + name + "]"; }
    }

    /** Fireable event type backed by a string name. */
    static final class StubFireableEventType implements FireableEventType {
        private final String name;
        StubFireableEventType(String name) { this.name = name; }
        @Override public EventType getEventType() { return new StubEventType(name); }
        @Override public String getName() { return name; }
    }

    static final class StubEventType implements EventType {
        private final String name;
        StubEventType(String name) { this.name = name; }
        @Override public EventTypeId getId() { return new EventTypeId(name, "test", "1.0"); }
        @Override public String getName() { return name; }
        @Override public String getVendor() { return "test"; }
        @Override public String getVersion() { return "1.0"; }
        @Override public Class<?> getEventClass() { return TestSleeEvent.class; }
    }

    private CapturingRouter router;
    private StubAcnf acnf;
    private AcquireActivityContext aciFactory;
    private RaEntityStateMachine stateMachine;
    private SleeEndpointImpl endpoint;

    @Before
    public void setUp() {
        router = new CapturingRouter();
        acnf = new StubAcnf();
        aciFactory = name -> new DefaultActivityContextInterface(name + "-aci", name);
        stateMachine = new RaEntityStateMachine(new StubRa(), "test-ra");
        endpoint = new SleeEndpointImpl(router, aciFactory, acnf, stateMachine);
        stateMachine.activate();
    }

    @Test
    public void activityStartedCreatesAciAndBindsAcnf() throws Exception {
        TestHandle h = new TestHandle("h-1");
        endpoint.activityStarted(h);
        assertEquals(1, endpoint.activeHandleCount());
        assertTrue(endpoint.isHandleActive("h-1"));
        assertNotNull(endpoint.lookupAci("h-1"));
        assertNotNull(acnf.lookup("h-1-aci"));
    }

    @Test
    public void duplicateActivityStartedRejected() throws Exception {
        TestHandle h = new TestHandle("dup");
        endpoint.activityStarted(h);
        try {
            endpoint.activityStarted(h);
            fail("expected ActivityAlreadyExistsException");
        } catch (ActivityAlreadyExistsException expected) {
            // ok
        }
    }

    @Test
    public void activityEndedDetachesSbbAndFiresEndEvent() throws Exception {
        TestHandle h = new TestHandle("h-2");
        endpoint.activityStarted(h);
        endpoint.activityEnded(h);
        assertEquals(0, endpoint.activeHandleCount());
        assertNull(acnf.lookup("h-2-aci"));
        // Event router should have received an ActivityEndedEvent
        assertTrue(router.routed.stream().anyMatch(e -> e instanceof com.microjainslee.api.ActivityEndedEvent));
    }

    @Test
    public void activityEndedForUnknownHandleIsNoop() {
        endpoint.activityEnded(new TestHandle("never-started"));
        // No exception, no router traffic
        assertEquals(0, router.routed.size());
    }

    @Test
    public void fireEventValidatesActiveState() {
        // Build a fresh, NOT-activated endpoint
        RaEntityStateMachine inactiveSm = new RaEntityStateMachine(new StubRa(), "inactive");
        SleeEndpointImpl ep = new SleeEndpointImpl(router, aciFactory, acnf, inactiveSm);
        try {
            ep.fireEvent(new TestHandle("any"), new TestSleeEvent("x"), null,
                    new StubFireableEventType("evt"));
            fail("expected IllegalStateException");
        } catch (IllegalStateException | UnrecognizedActivityException
                | FiredUnrecognizedEventException expected) {
            // ok
        }
    }

    @Test
    public void fireEventRejectsUnknownHandle() throws Exception {
        try {
            endpoint.fireEvent(new TestHandle("never-started"), new TestSleeEvent("x"),
                    null, new StubFireableEventType("evt"));
            fail("expected UnrecognizedActivityException");
        } catch (UnrecognizedActivityException expected) {
            // ok
        }
    }

    @Test
    public void fireEventRejectsUndeclaredEventType() throws Exception {
        TestHandle h = new TestHandle("h-3");
        endpoint.activityStarted(h);
        endpoint.declareEventType("known");
        try {
            endpoint.fireEvent(h, new TestSleeEvent("x"), null,
                    new StubFireableEventType("undeclared"));
            fail("expected FiredUnrecognizedEventException");
        } catch (FiredUnrecognizedEventException expected) {
            // ok
        }
    }

    @Test
    public void fireEventAcceptsDeclaredEventType() throws Exception {
        TestHandle h = new TestHandle("h-4");
        endpoint.activityStarted(h);
        endpoint.declareEventType("ok-event");
        endpoint.fireEvent(h, new TestSleeEvent("hello"), null,
                new StubFireableEventType("ok-event"));
        assertEquals(1, router.routed.size());
        assertTrue(router.routed.get(0) instanceof TestSleeEvent);
    }

    @Test
    public void fireEventRejectsNullEvent() throws Exception {
        TestHandle h = new TestHandle("h-5");
        endpoint.activityStarted(h);
        try {
            endpoint.fireEvent(h, null, null, new StubFireableEventType("evt"));
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void declareEventTypeTracksNames() {
        endpoint.declareEventType("a");
        endpoint.declareEventType("b");
        endpoint.declareEventType("a"); // duplicate
        Set<String> names = new HashSet<>(endpoint.declaredEventTypeNames());
        assertEquals(2, names.size());
        assertTrue(names.contains("a"));
        assertTrue(names.contains("b"));
    }

    @Test
    public void multipleHandlesCoexist() throws Exception {
        endpoint.activityStarted(new TestHandle("x"));
        endpoint.activityStarted(new TestHandle("y"));
        endpoint.activityStarted(new TestHandle("z"));
        assertEquals(3, endpoint.activeHandleCount());
        assertTrue(endpoint.isHandleActive("x"));
        assertTrue(endpoint.isHandleActive("y"));
        assertTrue(endpoint.isHandleActive("z"));
    }

    @Test
    public void stopCompleteTransitionsStateMachine() {
        // Move to STOPPING then stopComplete
        stateMachine.deactivate();
        assertEquals(RaEntityStateMachine.State.STOPPING, stateMachine.getState());
        endpoint.stopComplete();
        assertEquals(RaEntityStateMachine.State.INACTIVE, stateMachine.getState());
    }

    @Test
    public void nullArgumentsRejected() {
        try {
            new SleeEndpointImpl(null, aciFactory, acnf, stateMachine);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
        try {
            new SleeEndpointImpl(router, null, acnf, stateMachine);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
        try {
            new SleeEndpointImpl(router, aciFactory, acnf, null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }
}
