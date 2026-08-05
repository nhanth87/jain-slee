/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus;

import com.microjainslee.api.AlarmPort;
import com.microjainslee.api.NamingPort;
import com.microjainslee.api.ProfileTablePort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.TimerPort;
import com.microjainslee.api.TracePort;
import com.microjainslee.api.UsagePort;
import com.microjainslee.core.EventRouter;
import com.microjainslee.core.InMemoryNamingPort;
import com.microjainslee.core.MicroSleeContainer;
import io.quarkus.arc.Arc;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.RuntimeValue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CDI producers that re-expose the build-time-constructed {@link MicroSleeContainer} and its
 * key facilities as injectable beans.
 *
 * <p>The container itself is stashed in {@link MicroJainsleeHolder} by the recorder, and
 * each producer pulls it out lazily. If the holder is empty (e.g. when running unit tests
 * that don't go through the Quarkus build), we fall back to a default container built from
 * {@link com.microjainslee.core.MicroSleeConfiguration#defaults()}.</p>
 *
 * <p>Each produced bean is {@link ApplicationScoped} and registered with {@link DefaultBean}
 * so that user code can override any of them by supplying an alternative producer.</p>
 */
public class MicroJainsleeProducer {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(MicroJainsleeProducer.class);

    private MicroSleeContainer container() {
        RuntimeValue<MicroSleeContainer> rv = MicroJainsleeHolder.get();
        if (rv != null) {
            return rv.getValue();
        }
        LOG.warnf("MicroJainsleeHolder empty — falling back to default MicroSleeContainer (unit-test path?)");
        return new MicroSleeContainer();
    }

    /** Exposes the singleton micro-container. */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public MicroSleeContainer microSleeContainer() {
        return container();
    }

    /** Exposes the LMAX-Disruptor-backed {@link EventRouter} from the singleton container. */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public EventRouter eventRouter() {
        return container().getEventRouter();
    }

    /** Exposes the JAIN-SLEE timer facility from the singleton container. */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public TimerPort timerPort() {
        return container().getTimerPort();
    }

    /** Exposes the in-memory activity-context naming facility from the singleton container. */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public com.microjainslee.core.MicroSleeContainer.AcnfBackend activityContextNamingFacility() {
        return container().getActivityContextNamingFacility();
    }

    /** Exposes the global naming facility (§14). */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public NamingPort namingPort() {
        return new InMemoryNamingPort();
    }

    /** Exposes the alarm facility (§15). */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public AlarmPort alarmPort() {
        return new AlarmPortQuarkusAdapter();
    }

    /** Exposes the profile table port (§10) — in-memory until JPA backend is added. */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public ProfileTablePort profileTablePort() {
        return new ProfileTablePortQuarkusAdapter();
    }

    /** Exposes the usage facility (§12) with optional Micrometer integration. */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public UsagePort usagePort() {
        return new UsageFacilityQuarkusAdapter(resolveMeterRegistry());
    }

    /**
     * Factory for per-SBB tracers (§16).
     */
    @Produces
    @ApplicationScoped
    @DefaultBean
    public TracePort defaultTracePort() {
        return new TraceFacilityQuarkusAdapter("micro-jainslee");
    }

    // ──────────────────────────────────────────────────────────
    // GOAL 2 — CDI-driven RA registration (3-port contract)
    // ──────────────────────────────────────────────────────────

    /**
     * Discover CDI {@link RaEndpointPort}/{@link RaCommandPort} beans and register
     * them on the container. Uses {@code @Observes StartupEvent} (void {@code @Produces}
     * is illegal in CDI).
     */
    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev,
                 MicroSleeContainer container,
                 @Any Instance<RaEndpointPort> endpoints,
                 @Any Instance<RaCommandPort> commands) {
        registerResourceAdaptors(container, endpoints, commands);
    }

    private void registerResourceAdaptors(
            MicroSleeContainer container,
            Instance<RaEndpointPort> endpoints,
            Instance<RaCommandPort> commands) {

        List<RaEndpointPort> epList = new ArrayList<RaEndpointPort>();
        for (RaEndpointPort ep : endpoints) {
            epList.add(ep);
        }
        if (epList.isEmpty()) {
            LOG.debugf("No RaEndpointPort beans discovered; skipping CDI RA registration");
            return;
        }

        Map<String, RaCommandPort> commandsByName = new LinkedHashMap<>();
        for (RaCommandPort cmd : commands) {
            String name = resolveCommandRaName(cmd);
            if (name == null || name.isBlank()) {
                LOG.warnf("RaCommandPort %s has no getRaName()/@RaEntity — cannot pair by name",
                        cmd.getClass().getName());
                continue;
            }
            RaCommandPort prev = commandsByName.putIfAbsent(name, cmd);
            if (prev != null && prev != cmd) {
                LOG.warnf("Duplicate RaCommandPort name '%s': keeping %s discarding %s",
                        name, prev.getClass().getSimpleName(), cmd.getClass().getSimpleName());
            }
        }

        int registered = 0;
        for (RaEndpointPort endpoint : epList) {
            String name = endpoint.getRaName();
            if (name == null || name.isBlank()) {
                LOG.warnf("Skipping RaEndpointPort with blank getRaName(): %s",
                        endpoint.getClass().getName());
                continue;
            }
            RaCommandPort command = commandsByName.remove(name);
            if (command == null && endpoint instanceof RaCommandPort dual) {
                command = dual;
            }
            if (command == null) {
                LOG.warnf("No RaCommandPort paired by getRaName()/@RaEntity for endpoint '%s' — skipping",
                        name);
                continue;
            }
            try {
                container.registerRa(endpoint, command);
                registered++;
                LOG.infof("Registered RA via CDI: %s (endpoint=%s, command=%s)",
                        name,
                        endpoint.getClass().getSimpleName(),
                        command.getClass().getSimpleName());
            } catch (RuntimeException re) {
                LOG.errorf(re, "Failed to register RA [%s]: %s", name, re.getMessage());
            }
        }
        if (!commandsByName.isEmpty()) {
            LOG.warnf("Unpaired RaCommandPort(s) after name matching: %s", commandsByName.keySet());
        }
        LOG.infof("CDI RA registration complete: %s pair(s) registered", registered);
    }

    private static String resolveCommandRaName(RaCommandPort cmd) {
        if (cmd instanceof RaEndpointPort ep) {
            String n = ep.getRaName();
            if (n != null && !n.isBlank()) {
                return n;
            }
        }
        for (java.lang.annotation.Annotation a : cmd.getClass().getAnnotations()) {
            if (!"RaEntity".equals(a.annotationType().getSimpleName())) {
                continue;
            }
            try {
                Object v = a.annotationType().getMethod("value").invoke(a);
                if (v instanceof String s && !s.isBlank()) {
                    return s;
                }
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        return null;
    }

    private static Object resolveMeterRegistry() {
        try {
            Class<?> registryClass = Class.forName("io.micrometer.core.instrument.MeterRegistry");
            if (!Arc.container().isRunning()) {
                return null;
            }
            return Arc.container().select(registryClass).stream().findFirst().orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}