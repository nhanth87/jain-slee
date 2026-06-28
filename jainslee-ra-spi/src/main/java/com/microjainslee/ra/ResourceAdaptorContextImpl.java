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
import com.microjainslee.api.AlarmFacility;
import com.microjainslee.api.EventLookupFacility;
import com.microjainslee.api.FiredUnrecognizedEventException;
import com.microjainslee.api.FireableEventType;
import com.microjainslee.api.NullActivityFactory;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;
import com.microjainslee.api.SleeEndpoint;
import com.microjainslee.api.TimerFacility;
import com.microjainslee.api.TraceFacility;
import com.microjainslee.api.UnrecognizedActivityException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Perfect Core S5 — full-facilities {@link ResourceAdaptorContext}
 * wired to an RA at activation time.
 */
public class ResourceAdaptorContextImpl implements ResourceAdaptorContext {

    private static final Logger LOG = LogManager.getLogger(ResourceAdaptorContextImpl.class);

    private final String raEntityName;
    private final SleeEndpoint sleeEndpoint;
    private final com.microjainslee.api.SleeEndpointPort sleeEndpointPort;
    private final TimerFacility timerFacility;
    private final AlarmFacility alarmFacility;
    private final TraceFacility traceFacility;
    private final NullActivityFactory nullActivityFactory;
    private final EventLookupFacility eventLookupFacility;
    private final Object container;
    private final ConcurrentHashMap<Object, ActivityContextHandle> handlesByActivity =
            new ConcurrentHashMap<>();
    private final AtomicLong handleSequence = new AtomicLong();
    private volatile ResourceAdaptor resourceAdaptor;

    public ResourceAdaptorContextImpl(String raEntityName,
                                      SleeEndpoint sleeEndpoint,
                                      TimerFacility timerFacility,
                                      AlarmFacility alarmFacility,
                                      TraceFacility traceFacility,
                                      NullActivityFactory nullActivityFactory,
                                      EventLookupFacility eventLookupFacility,
                                      Object container) {
        this(raEntityName, sleeEndpoint, null, timerFacility, alarmFacility,
                traceFacility, nullActivityFactory, eventLookupFacility, container);
    }

    public ResourceAdaptorContextImpl(String raEntityName,
                                      SleeEndpoint sleeEndpoint,
                                      com.microjainslee.api.SleeEndpointPort sleeEndpointPort,
                                      TimerFacility timerFacility,
                                      AlarmFacility alarmFacility,
                                      TraceFacility traceFacility,
                                      NullActivityFactory nullActivityFactory,
                                      EventLookupFacility eventLookupFacility,
                                      Object container) {
        if (raEntityName == null || raEntityName.isEmpty()) {
            throw new IllegalArgumentException("raEntityName is required");
        }
        this.raEntityName = raEntityName;
        this.sleeEndpoint = sleeEndpoint;
        this.sleeEndpointPort = sleeEndpointPort;
        this.timerFacility = timerFacility;
        this.alarmFacility = alarmFacility;
        this.traceFacility = traceFacility;
        this.nullActivityFactory = nullActivityFactory;
        this.eventLookupFacility = eventLookupFacility;
        this.container = container;

        LOG.debug("ResourceAdaptorContext initialized for RA entity [{}]", raEntityName);
    }

    @Override
    public void setResourceAdaptor(ResourceAdaptor ra) {
        this.resourceAdaptor = ra;
    }

    public ResourceAdaptor getResourceAdaptor() {
        return resourceAdaptor;
    }

    @Override
    public ActivityContextHandle createActivityContextHandle(Object activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity is required");
        }
        ActivityContextHandle existing = handlesByActivity.get(activity);
        if (existing != null) {
            return existing;
        }
        ActivityContextHandle handle =
                new com.microjainslee.api.SimpleActivityContextHandle(
                        raEntityName + ":ach:" + handleSequence.incrementAndGet());
        ActivityContextHandle prior = handlesByActivity.putIfAbsent(activity, handle);
        return prior != null ? prior : handle;
    }

    @Override
    public ActivityContextHandle getActivityContextHandle(Object activity) {
        if (activity == null) return null;
        return handlesByActivity.get(activity);
    }

    public SleeEndpoint getSleeEndpoint() {
        return sleeEndpoint;
    }

    @Override
    public com.microjainslee.api.SleeEndpointPort getSleeEndpointPort() {
        if (sleeEndpointPort != null) return sleeEndpointPort;
        if (sleeEndpoint instanceof com.microjainslee.api.SleeEndpointPort port) {
            return port;
        }
        return null;
    }

    public TimerFacility getTimer() {
        if (timerFacility == null) {
            throw new IllegalStateException(
                    "TimerFacility is not wired for RA [" + raEntityName + "]");
        }
        return timerFacility;
    }

    public AlarmFacility getAlarmFacility() {
        if (alarmFacility == null) {
            throw new IllegalStateException(
                    "AlarmFacility is not wired for RA [" + raEntityName + "]");
        }
        return alarmFacility;
    }

    public TraceFacility getTraceFacility() {
        if (traceFacility == null) {
            throw new IllegalStateException(
                    "TraceFacility is not wired for RA [" + raEntityName + "]");
        }
        return traceFacility;
    }

    public NullActivityFactory getNullActivityFactory() {
        if (nullActivityFactory == null) {
            throw new IllegalStateException(
                    "NullActivityFactory is not wired for RA [" + raEntityName + "]");
        }
        return nullActivityFactory;
    }

    public EventLookupFacility getEventLookupFacility() {
        if (eventLookupFacility == null) {
            throw new IllegalStateException(
                    "EventLookupFacility is not wired for RA [" + raEntityName + "]");
        }
        return eventLookupFacility;
    }

    public FireableEventType getFireableEventType(com.microjainslee.api.EventTypeId id) {
        if (eventLookupFacility == null) return null;
        return eventLookupFacility.getFireableEventType(id);
    }

    @Override
    public Object getContainer() {
        return container;
    }

    public String getRaEntityName() {
        return raEntityName;
    }

    @Override
    public String toString() {
        return "RaContext[" + raEntityName + "]";
    }

    public static Builder builder(String raEntityName) {
        return new Builder(raEntityName);
    }

    public static final class Builder {
        private final String raEntityName;
        private SleeEndpoint sleeEndpoint;
        private com.microjainslee.api.SleeEndpointPort sleeEndpointPort;
        private TimerFacility timerFacility;
        private AlarmFacility alarmFacility;
        private TraceFacility traceFacility;
        private NullActivityFactory nullActivityFactory;
        private EventLookupFacility eventLookupFacility;
        private Object container;

        Builder(String name) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name is required");
            }
            this.raEntityName = name;
        }

        public Builder sleeEndpoint(SleeEndpoint ep) { this.sleeEndpoint = ep; return this; }

        /**
         * Overload that accepts a slim {@link com.microjainslee.api.SleeEndpointPort}
         * — the embedded-protocol surface — and stores it under the
         * broader {@link SleeEndpoint} field. The port satisfies the
         * spec surface by the same name even though Java does not
         * consider them related.
         */
        public Builder sleeEndpoint(com.microjainslee.api.SleeEndpointPort port) {
            this.sleeEndpoint = new SleeEndpoint() {
                @Override public void activityStarted(com.microjainslee.api.ActivityHandle handle)
                        throws ActivityAlreadyExistsException {
                    if (port instanceof SleeEndpointImpl impl) {
                        impl.activityStarted(handle);
                    }
                }
                @Override public void activityEnded(com.microjainslee.api.ActivityHandle handle) {
                    port.endActivity(asHandle(handle));
                }
                @Override public void fireEvent(com.microjainslee.api.ActivityHandle handle,
                        Object event, com.microjainslee.api.Address address,
                        com.microjainslee.api.FireableEventType eventType)
                        throws UnrecognizedActivityException, FiredUnrecognizedEventException,
                               IllegalStateException {
                    if (event instanceof com.microjainslee.api.SleeEvent se) {
                        port.fireEvent(asHandle(handle), se);
                    }
                }
                @Override public void stopComplete() { /* port has no stop hook */ }
            };
            this.sleeEndpointPort = port;
            return this;
        }
        public Builder timer(TimerFacility tf) { this.timerFacility = tf; return this; }
        public Builder alarm(AlarmFacility af) { this.alarmFacility = af; return this; }
        public Builder trace(TraceFacility tf) { this.traceFacility = tf; return this; }
        public Builder nullActivity(NullActivityFactory naf) { this.nullActivityFactory = naf; return this; }
        public Builder eventLookup(EventLookupFacility elf) { this.eventLookupFacility = elf; return this; }
        public Builder container(Object c) { this.container = c; return this; }

        private static com.microjainslee.api.ActivityContextHandle asHandle(
                com.microjainslee.api.ActivityHandle h) {
            if (h == null) return null;
            return new com.microjainslee.api.ActivityContextHandle() {
                @Override public String getId() { return h.getId(); }
            };
        }

        public ResourceAdaptorContextImpl build() {
            if (alarmFacility == null)         alarmFacility = NoopAlarmFacility.INSTANCE;
            if (traceFacility == null)         traceFacility = LogbackTraceFacility.INSTANCE;
            if (nullActivityFactory == null)   nullActivityFactory = SimpleNullActivityFactory.INSTANCE;
            if (eventLookupFacility == null)   eventLookupFacility = SimpleEventLookupFacility.INSTANCE;

            return new ResourceAdaptorContextImpl(
                    raEntityName,
                    sleeEndpoint,
                    sleeEndpointPort,
                    timerFacility,
                    alarmFacility,
                    traceFacility,
                    nullActivityFactory,
                    eventLookupFacility,
                    container);
        }
    }
}
