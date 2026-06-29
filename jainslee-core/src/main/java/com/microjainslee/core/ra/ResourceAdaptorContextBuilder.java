/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.ra;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityContextNamingFacility;
import com.microjainslee.api.AlarmFacility;
import com.microjainslee.api.EventLookupFacility;
import com.microjainslee.api.NullActivityFactory;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.SleeEndpoint;
import com.microjainslee.api.TimerFacility;
import com.microjainslee.api.TraceFacility;
import com.microjainslee.core.EventRouter;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleAlarmFacility;
import com.microjainslee.core.SleeTimerSchedulerBridge;
import com.microjainslee.core.ordering.DedupWindow;
import com.microjainslee.ra.AcquireActivityContext;
import com.microjainslee.ra.EventRouterPort;
import com.microjainslee.ra.NoopAlarmFacility;
import com.microjainslee.ra.RaEntityStateMachine;
import com.microjainslee.ra.ResourceAdaptorContextImpl;
import com.microjainslee.ra.SimpleEventLookupFacility;
import com.microjainslee.ra.SimpleNullActivityFactory;
import com.microjainslee.ra.SleeEndpointImpl;
import com.microjainslee.ra.LogbackTraceFacility;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Perfect Core S5 — kernel-side factory that wires an
 * {@link com.microjainslee.ra.ResourceAdaptorContextImpl} with the
 * live container's facilities (EventRouter, ACNF, TimerBridge, AlarmFacility,
 * TraceFacility, NullActivityFactory, EventLookupFacility).
 *
 * <p>Two ways to use it:
 * <ul>
 *   <li>{@link #build(MicroSleeContainer, ResourceAdaptor, String)} —
 *       canonical path used by {@code MicroSleeContainer.registerResourceAdaptor}.
 *       Pulls every facility out of the live container and wires them
 *       through the SPI.</li>
 *   <li>{@link #builder(String)} — direct builder for tests or
 *       isolated RA embedding.</li>
 * </ul>
 */
public final class ResourceAdaptorContextBuilder {

    private static final Logger LOG = LogManager.getLogger(ResourceAdaptorContextBuilder.class);

    private ResourceAdaptorContextBuilder() {}

    /**
     * Canonical entry point — build a context bound to the live
     * container. Creates the {@link SleeEndpointImpl},
     * {@link RaEntityStateMachine}, and the
     * {@link ResourceAdaptorContextImpl} in one shot, and returns the
     * context (with the state-machine + endpoint stashed in the
     * {@link Built} record so the caller can drive the lifecycle).
     *
     * <p>Sprint S8.5 — automatically pulls the shared
     * {@link com.microjainslee.core.ordering.DedupWindow} from the
     * container (when one is wired) and binds it to the freshly-built
     * endpoint via {@code withDedupCheck(...)} + {@code withDedupHitCount(...)}.
     * Callers that want an explicit window can use the longer
     * {@link #build(MicroSleeContainer, ResourceAdaptor, String, DedupWindow)}
     * overload.</p>
     */
    public static Built build(MicroSleeContainer container,
                              ResourceAdaptor ra,
                              String raEntityName) {
        // Default path: defer to the container's wired dedup window.
        // start() creates a 60-second window by default; callers that
        // need a different one can install it with
        // container.setDedupWindow(...) before calling registerResourceAdaptor.
        return build(container, ra, raEntityName, container == null ? null : container.getDedupWindow());
    }

    /**
     * Sprint S8.5 overload — pass a {@link DedupWindow} explicitly.
     * Pass {@code null} (or simply omit it via the 3-arg overload) to
     * disable dedup for this build. The window is bound to the
     * endpoint's {@link SleeEndpointImpl#withDedupCheck(DedupCheck)}
     * and {@link SleeEndpointImpl#withDedupHitCount(java.util.function.ToLongBiFunction)}
     * so all three SleeEndpoint methods honour it (state-machine
     * reentry, validation order, and {@code getDedupHitCount()}).
     */
    public static Built build(MicroSleeContainer container,
                              ResourceAdaptor ra,
                              String raEntityName,
                              DedupWindow dedupWindow) {
        if (container == null) throw new IllegalArgumentException("container is required");
        if (ra == null) throw new IllegalArgumentException("ra is required");
        if (raEntityName == null || raEntityName.isEmpty()) {
            throw new IllegalArgumentException("raEntityName is required");
        }
        EventRouter eventRouter = container.getEventRouter();
        ActivityContextNamingFacility acnf = container.getActivityContextNamingFacility();

        // EventRouterPort bridges the SPI to the kernel without
        // jainslee-core being a dependency of jainslee-ra-spi.
        EventRouterPort routerPort = (Object event, ActivityContextInterface aci) -> {
            if (event instanceof com.microjainslee.api.SleeEvent se) {
                container.routeEvent(se, aci);
            } else {
                // Wrap plain Java objects in a SleeEvent so the
                // container's routeEvent contract is honoured.
                container.routeEvent(new com.microjainslee.api.SleeEvent() {
                    @Override public String toString() { return String.valueOf(event); }
                }, aci);
            }
        };

        // ACNF-backed ACI factory
        AcquireActivityContext aciFactory = (String name) -> container.createActivityContext(name);

        // State machine — drives the lifecycle.
        RaEntityStateMachine stateMachine = new RaEntityStateMachine(ra, raEntityName);

        // SleeEndpoint with full validation
        SleeEndpointImpl endpoint = new SleeEndpointImpl(
                routerPort, aciFactory, acnf, stateMachine);

        // Sprint S8.5 — wire the (optional) dedup window. When a window
        // is supplied, capture it in a `final` local so the lambdas
        // below bind to the exact reference the caller passed in. Both
        // checks are no-ops when the window is null (preserves the
        // legacy hot path).
        final DedupWindow wiredWindow = dedupWindow;
        if (wiredWindow != null) {
            endpoint.withDedupCheck(
                    (convergence, seqNum, nowMs) -> wiredWindow.isDuplicate(convergence, seqNum, nowMs));
            endpoint.withDedupHitCount(
                    (convergence, seqNum) -> wiredWindow.getDedupHitCount());
            LOG.debug("DedupWindow wired into endpoint for entity [{}] (windowMs={})",
                    raEntityName, wiredWindow.getWindowMs());
        }

        // Facilities from the container
        AlarmFacility alarm = container.getAlarmFacility();
        SleeTimerSchedulerBridge bridge = container.getTimerBridge();
        TimerFacility timer = wrapTimerBridge(bridge);
        TraceFacility trace = LogbackTraceFacility.INSTANCE;
        NullActivityFactory nullAct = name -> container.createActivityContext(name);
        EventLookupFacility eventLookup = SimpleEventLookupFacility.INSTANCE;

        ResourceAdaptorContextImpl ctx = ResourceAdaptorContextImpl.builder(raEntityName)
                .sleeEndpoint(endpoint)
                .timer(timer)
                .alarm(alarm)
                .trace(trace)
                .nullActivity(nullAct)
                .eventLookup(eventLookup)
                .container(container)
                .build();

        // Back-reference: the context knows the RA so AbstractResourceAdaptor
        // can publish events through the kernel-aware path.
        ctx.setResourceAdaptor(ra);

        LOG.info("Built RA context for entity [{}] (dedup={})", raEntityName,
                wiredWindow == null ? "DISABLED" : "ENABLED");
        return new Built(ctx, stateMachine, endpoint);
    }

    /** Direct builder — used by tests or isolated RA embedding. */
    public static ResourceAdaptorContextImpl.Builder builder(String raEntityName) {
        return ResourceAdaptorContextImpl.builder(raEntityName);
    }

    /**
     * Adapter that wraps the kernel's {@link SleeTimerSchedulerBridge}
     * behind the {@link TimerFacility} interface used by the SPI.
     */
    private static TimerFacility wrapTimerBridge(SleeTimerSchedulerBridge bridge) {
        return new TimerFacility() {
            @Override
            public long setTimer(long durationMs) {
                // Bridge doesn't have a free-standing setTimer API
                // without an SBB local object — return 0 (no timer) when
                // called without one. RAs that need timers bound to an
                // SBB should use SbbContext.getTimerFacility() instead.
                LOG.debug("TimerFacility.setTimer({}) called on RA-level timer bridge", durationMs);
                return 0L;
            }
            @Override
            public void cancelTimer(long timerId) {
                if (timerId != 0L) {
                    bridge.cancel(timerId);
                }
            }
        };
    }

    /**
     * Aggregate returned by {@link #build(MicroSleeContainer, ResourceAdaptor, String)}.
     * Holds the freshly-built context plus the lifecycle objects
     * (state machine + endpoint) the caller can manipulate.
     */
    public record Built(ResourceAdaptorContextImpl context,
                        RaEntityStateMachine stateMachine,
                        SleeEndpointImpl endpoint) {
        /** Convenience: caller-driven activation — invokes stateMachine.activate(). */
        public void activate() {
            stateMachine.activate();
        }
        /** Convenience: caller-driven deactivation — invokes stateMachine.deactivate(). */
        public void deactivate() {
            stateMachine.deactivate();
        }
    }

    /** Shared empty ACNF map for tests that need a stub ACNF. */
    public static final class StubAcnf implements ActivityContextNamingFacility {
        private final ConcurrentHashMap<String, ActivityContextInterface> map =
                new ConcurrentHashMap<>();
        @Override public void bind(String name, ActivityContextInterface aci) { map.put(name, aci); }
        @Override public ActivityContextInterface lookup(String name) { return map.get(name); }
        @Override public void unbind(String name) { map.remove(name); }
        @Override public java.util.Collection<ActivityContextInterface> getBoundContexts() { return map.values(); }
        @Override public java.util.Set<String> names() { return map.keySet(); }
        @Override public void clear() { map.clear(); }
    }

    /** Stub {@link AlarmFacility} that records calls for assertions in tests. */
    public static final class StubAlarm implements AlarmFacility {
        public final java.util.List<String> raised = new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void raise(String t, String i, com.microjainslee.api.AlarmLevel l, String m) {
            raised.add(t + "|" + i + "|" + l + "|" + m);
        }
        @Override public void clear(String t, String i) {
            raised.add("CLEAR|" + t + "|" + i);
        }
    }

    /** Ensure the {@link SimpleAlarmFacility} import is used (keeps the kernel-facility contract obvious). */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_ALARM = SimpleAlarmFacility.class;
}
