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

import com.microjainslee.api.ActivityContextHandle;
import com.microjainslee.api.AlarmFacility;
import com.microjainslee.api.AlarmLevel;
import com.microjainslee.api.EventLookupFacility;
import com.microjainslee.api.EventTypeId;
import com.microjainslee.api.FireableEventType;
import com.microjainslee.api.NullActivityFactory;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.SleeEndpoint;
import com.microjainslee.api.TimerFacility;
import com.microjainslee.api.TraceFacility;
import com.microjainslee.api.TraceLevel;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ResourceAdaptorContextImplTest {

    static final class StubRa implements ResourceAdaptor {
        @Override public void setResourceAdaptorContext(com.microjainslee.api.ResourceAdaptorContext ctx) {}
        @Override public void raConfigure() {}
        @Override public void raActive() {}
        @Override public void raStopping() {}
        @Override public void raInactive() {}
        @Override public void raUnconfigure() {}
    }

    static final class CapturingTimer implements TimerFacility {
        final CopyOnWriteArrayList<String> log = new CopyOnWriteArrayList<>();
        @Override public long setTimer(long durationMs) { log.add("set:" + durationMs); return 42L; }
        @Override public void cancelTimer(long timerId) { log.add("cancel:" + timerId); }
    }

    static final class CapturingAlarm implements AlarmFacility {
        final CopyOnWriteArrayList<String> log = new CopyOnWriteArrayList<>();
        @Override public void raise(String t, String i, AlarmLevel l, String m) {
            log.add("raise:" + t + "|" + i + "|" + l + "|" + m);
        }
        @Override public void clear(String t, String i) { log.add("clear:" + t + "|" + i); }
    }

    static final class CapturingTrace implements TraceFacility {
        final CopyOnWriteArrayList<String> log = new CopyOnWriteArrayList<>();
        @Override public void trace(String message) { log.add("INFO:" + message); }
        @Override public void trace(TraceLevel level, String message) { log.add(level + ":" + message); }
        @Override public boolean isTraceEnabled(TraceLevel level) { return true; }
    }

    static final class CapturingNullActivityFactory implements NullActivityFactory {
        @Override public com.microjainslee.api.ActivityContextInterface createNullActivity(String name) {
            return new DefaultActivityContextInterface(name, name);
        }
    }

    static final class CapturingEventLookup implements EventLookupFacility {
        final CopyOnWriteArrayList<EventTypeId> asked = new CopyOnWriteArrayList<>();
        final SimpleEventLookupFacility delegate = new SimpleEventLookupFacility();
        @Override public FireableEventType getFireableEventType(EventTypeId id) {
            asked.add(id);
            return delegate.getFireableEventType(id);
        }
        @Override public Iterable<EventTypeId> getAllEventTypeIds() {
            return delegate.getAllEventTypeIds();
        }
    }

    @Test
    public void builderProducesFullContext() {
        CapturingTimer timer = new CapturingTimer();
        CapturingAlarm alarm = new CapturingAlarm();
        CapturingTrace trace = new CapturingTrace();
        CapturingNullActivityFactory naf = new CapturingNullActivityFactory();
        CapturingEventLookup elf = new CapturingEventLookup();
        SleeEndpoint endpoint = new SleeEndpoint() {
            @Override public void activityStarted(com.microjainslee.api.ActivityHandle handle) {}
            @Override public void activityEnded(com.microjainslee.api.ActivityHandle handle) {}
            @Override public void fireEvent(com.microjainslee.api.ActivityHandle handle, Object event,
                    com.microjainslee.api.Address address, FireableEventType eventType) {}
            @Override public void stopComplete() {}
        };

        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder("ra-x")
                .sleeEndpoint(endpoint)
                .timer(timer)
                .alarm(alarm)
                .trace(trace)
                .nullActivity(naf)
                .eventLookup(elf)
                .container("container-token")
                .build();

        assertEquals("ra-x", ctx.getRaEntityName());
        assertSame(endpoint, ctx.getSleeEndpoint());
        assertSame(timer, ctx.getTimer());
        assertSame(alarm, ctx.getAlarmFacility());
        assertSame(trace, ctx.getTraceFacility());
        assertSame(naf, ctx.getNullActivityFactory());
        assertSame(elf, ctx.getEventLookupFacility());
        assertEquals("container-token", ctx.getContainer());
        assertTrue(ctx.toString().contains("ra-x"));
    }

    @Test
    public void builderProvidesDefaultsForMissingFacilities() {
        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder("ra-defaults")
                .build();
        // Alarm / trace / nullActivity / eventLookup all get safe defaults
        assertEquals("ra-defaults", ctx.getRaEntityName());
        assertNotNull(ctx.getAlarmFacility());
        assertNotNull(ctx.getTraceFacility());
        assertNotNull(ctx.getNullActivityFactory());
        assertNotNull(ctx.getEventLookupFacility());
    }

    @Test
    public void getSleeEndpointPortDelegatesWhenEndpointIsPort() {
        com.microjainslee.api.SleeEndpointPort port = new com.microjainslee.api.SleeEndpointPort() {
            @Override public com.microjainslee.api.ActivityContextInterface startActivity(
                    ActivityContextHandle handle, Object activity) { return null; }
            @Override public void endActivity(ActivityContextHandle handle) {}
            @Override public void fireEvent(ActivityContextHandle handle, com.microjainslee.api.SleeEvent event) {}
        };
        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder("ra-port")
                .sleeEndpoint(port)
                .build();
        assertSame(port, ctx.getSleeEndpointPort());
    }

    @Test
    public void getSleeEndpointPortReturnsNullWhenEndpointIsNotPort() {
        SleeEndpoint nonPort = new SleeEndpoint() {
            @Override public void activityStarted(com.microjainslee.api.ActivityHandle handle) {}
            @Override public void activityEnded(com.microjainslee.api.ActivityHandle handle) {}
            @Override public void fireEvent(com.microjainslee.api.ActivityHandle handle, Object event,
                    com.microjainslee.api.Address address, FireableEventType eventType) {}
            @Override public void stopComplete() {}
        };
        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder("ra-spec")
                .sleeEndpoint(nonPort)
                .build();
        assertNull(ctx.getSleeEndpointPort());
        assertSame(nonPort, ctx.getSleeEndpoint());
    }

    @Test
    public void activityContextHandleMapping() {
        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder("ra-ach")
                .build();
        Object activity = new Object();
        ActivityContextHandle h1 = ctx.createActivityContextHandle(activity);
        ActivityContextHandle h2 = ctx.createActivityContextHandle(activity);
        assertSame(h1, h2);
        assertSame(h1, ctx.getActivityContextHandle(activity));
        assertNotNull(h1.getId());
    }

    @Test
    public void distinctActivitiesGetDistinctHandles() {
        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder("ra-distinct")
                .build();
        ActivityContextHandle a = ctx.createActivityContextHandle(new Object());
        ActivityContextHandle b = ctx.createActivityContextHandle(new Object());
        // Different handles for different activities
        assertTrue(!a.getId().equals(b.getId()));
    }

    @Test
    public void getFireableEventTypeDelegatesToLookup() {
        SimpleEventLookupFacility elf = new SimpleEventLookupFacility()
                .registerEventType(new EventTypeId("HelloEvt", "test", "1.0"), String.class);
        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder("ra-fire")
                .eventLookup(elf)
                .build();
        FireableEventType fet = ctx.getFireableEventType(new EventTypeId("HelloEvt", "test", "1.0"));
        assertNotNull(fet);
        assertEquals("HelloEvt", fet.getName());
    }

    @Test
    public void setResourceAdaptorRoundTrips() {
        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder("ra-rt").build();
        ResourceAdaptor ra = new StubRa();
        ctx.setResourceAdaptor(ra);
        assertSame(ra, ctx.getResourceAdaptor());
    }

    @Test
    public void nullNameRejected() {
        try {
            ResourceAdaptorContextImpl.builder(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
        try {
            ResourceAdaptorContextImpl.builder("");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }
}
