/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.springboot;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.telemetry.TelemetryDispatchObserver;
import com.microjainslee.telemetry.TelemetryPort;
import com.microjainslee.telemetry.TelemetryRaObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.SmartLifecycle;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MicroJainsleeLifecycle implements SmartLifecycle {

    private static final Logger LOG = LogManager.getLogger(MicroJainsleeLifecycle.class);

    private final MicroSleeContainer container;
    private final MicroJainsleeProperties props;
    private final List<RaEndpointPort> raEndpoints;
    private final List<RaCommandPort> raCommands;
    private final TelemetryPort telemetryPort;
    private volatile boolean running = false;

    /**
     * Backward-compatible constructor (no RA ports, no properties).
     * Used by tests and embedders that wire the lifecycle by hand.
     */
    public MicroJainsleeLifecycle(MicroSleeContainer container) {
        this(container, null, Collections.emptyList(), Collections.emptyList(), null);
    }

    /**
     * Backward-compatible constructor (no telemetry port).
     */
    public MicroJainsleeLifecycle(MicroSleeContainer container,
                                   MicroJainsleeProperties props,
                                   List<RaEndpointPort> raEndpoints,
                                   List<RaCommandPort> raCommands) {
        this(container, props, raEndpoints, raCommands, null);
    }

    /**
     * Full constructor accepting properties, RA port lists, and an optional
     * {@link TelemetryPort} bean. When present, dispatch and RA observers are
     * wired so {@code jainslee_* / jainslee_ra_*} counters stay live.
     */
    public MicroJainsleeLifecycle(MicroSleeContainer container,
                                   MicroJainsleeProperties props,
                                   List<RaEndpointPort> raEndpoints,
                                   List<RaCommandPort> raCommands,
                                   TelemetryPort telemetryPort) {
        this.container = container;
        this.props = props;
        this.raEndpoints = raEndpoints != null ? raEndpoints : Collections.emptyList();
        this.raCommands = raCommands != null ? raCommands : Collections.emptyList();
        this.telemetryPort = telemetryPort;
        LOG.debug("MicroJainsleeLifecycle created (phase={}, container={}, raEndpoints={}, raCommands={})",
                Integer.MIN_VALUE + 100,
                container != null ? container.getState() : "null",
                this.raEndpoints.size(), this.raCommands.size());
    }

    @Override public boolean isAutoStartup() { return true; }

    @Override
    public void start() {
        if (container == null) {
            LOG.warn("SmartLifecycle.start() called but no container wired; skipping");
            return;
        }
        LOG.info("SmartLifecycle.start() - starting MicroSleeContainer (current state={})", container.getState());
        container.start();
        running = true;
        wireTelemetryObservers();
        LOG.info("MicroSleeContainer started via SmartLifecycle (phase={})", getPhase());

        // GOAL 2 — register any RA endpoint+command pairs from the application context.
        registerResourceAdaptors();

        // GOAL 5 — wire event-to-SBB mappings from configuration properties.
        mapEventsToSbbs();
    }

    @Override
    public void stop() {
        if (container == null) {
            LOG.warn("SmartLifecycle.stop() called but no container wired; skipping");
            running = false;
            return;
        }
        LOG.info("SmartLifecycle.stop() - stopping MicroSleeContainer (current state={})", container.getState());
        try {
            container.stop();
            running = false;
            LOG.info("MicroSleeContainer stopped via SmartLifecycle");
        } catch (Throwable t) {
            LOG.error("MicroSleeContainer.stop() failed: {}", t.getMessage(), t);
            throw t;
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MIN_VALUE + 100; }

    private void wireTelemetryObservers() {
        if (telemetryPort == null) {
            LOG.debug("No TelemetryPort bean — skipping dispatch/RA observer wiring");
            return;
        }
        container.getEventRouter().setDispatchObserver(
                new TelemetryDispatchObserver(telemetryPort));
        container.setRaObserver(new TelemetryRaObserver(telemetryPort));
        LOG.info("Telemetry dispatch + RA observers wired from TelemetryPort bean");
    }

    // ──────────────────────────────────────────────────────────
    // GOAL 2 — register RA endpoint+command pairs
    // ──────────────────────────────────────────────────────────

    private void registerResourceAdaptors() {
        if (raEndpoints.isEmpty()) {
            LOG.debug("No RaEndpointPort beans found in context; skipping RA registration");
            return;
        }
        Map<String, RaCommandPort> commandsByName = indexCommandsByRaName(raCommands);
        List<RaCommandPort> unnamedCommands = new java.util.ArrayList<>();
        for (RaCommandPort cmd : raCommands) {
            if (cmd == null) {
                continue;
            }
            String n = resolveCommandRaName(cmd);
            if (n == null || n.isBlank()) {
                unnamedCommands.add(cmd);
            }
        }
        for (RaEndpointPort endpoint : raEndpoints) {
            String name = endpoint.getRaName();
            if (name == null || name.isBlank()) {
                LOG.warn("Skipping RaEndpointPort with blank getRaName(): {}",
                        endpoint.getClass().getName());
                continue;
            }
            RaCommandPort command = commandsByName.remove(name);
            if (command == null && endpoint instanceof RaCommandPort dual) {
                // Same bean implements both ports (common WRAPPER pattern).
                command = dual;
            }
            if (command == null && unnamedCommands.size() == 1 && raEndpoints.size() == 1) {
                // Single-RA lab / legacy: one anonymous command port pairs with the only endpoint.
                command = unnamedCommands.remove(0);
                LOG.warn("Paired endpoint '{}' with unnamed RaCommandPort {} (single-RA fallback)",
                        name, command.getClass().getName());
            }
            if (command == null) {
                LOG.warn("No RaCommandPort paired by getRaName()/@RaEntity for endpoint '{}' — skipping",
                        name);
                continue;
            }
            try {
                container.registerRa(endpoint, command);
                LOG.info("Registered RA via lifecycle: {} (command={})",
                        name, command.getClass().getSimpleName());
            } catch (RuntimeException re) {
                LOG.error("Failed to register RA [{}]: {}", name, re.getMessage(), re);
            }
        }
        if (!commandsByName.isEmpty()) {
            LOG.warn("Unpaired RaCommandPort(s) after name matching: {}", commandsByName.keySet());
        }
    }

    /**
     * Index command ports by RA name: dual {@link RaEndpointPort#getRaName()},
     * else reflective {@code @RaEntity("name")} (jakartaee or spring-local).
     * Never pair by list order.
     */
    static Map<String, RaCommandPort> indexCommandsByRaName(List<RaCommandPort> commands) {
        Map<String, RaCommandPort> byName = new java.util.LinkedHashMap<>();
        if (commands == null) {
            return byName;
        }
        for (RaCommandPort cmd : commands) {
            if (cmd == null) {
                continue;
            }
            String name = resolveCommandRaName(cmd);
            if (name == null || name.isBlank()) {
                LOG.warn("RaCommandPort {} has no getRaName()/@RaEntity — cannot pair by name",
                        cmd.getClass().getName());
                continue;
            }
            RaCommandPort prev = byName.putIfAbsent(name, cmd);
            if (prev != null && prev != cmd) {
                LOG.warn("Duplicate RaCommandPort name '{}': keeping {} discarding {}",
                        name, prev.getClass().getSimpleName(), cmd.getClass().getSimpleName());
            }
        }
        return byName;
    }

    static String resolveCommandRaName(RaCommandPort cmd) {
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
            } catch (ReflectiveOperationException e) {
                LOG.debug("RaEntity value() unreadable on {}: {}",
                        cmd.getClass().getName(), e.toString());
            }
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────
    // GOAL 5 — event-to-SBB mappings from configuration
    // ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void mapEventsToSbbs() {
        if (props == null || props.getEventToSbbMappings() == null || props.getEventToSbbMappings().isEmpty()) {
            LOG.debug("No event-to-sbb-mappings configured; skipping");
            return;
        }
        for (Map.Entry<String, String> entry : props.getEventToSbbMappings().entrySet()) {
            String eventClassName = entry.getKey();
            String sbbName = entry.getValue();
            if (eventClassName == null || eventClassName.trim().isEmpty()) {
                LOG.warn("Skipping event-to-sbb mapping with empty event class name (sbb={})", sbbName);
                continue;
            }
            if (sbbName == null || sbbName.trim().isEmpty()) {
                LOG.warn("Skipping event-to-sbb mapping with empty SBB name (event={})", eventClassName);
                continue;
            }
            try {
                Class<?> rawClass = Class.forName(eventClassName, true,
                        Thread.currentThread().getContextClassLoader());
                if (!SleeEvent.class.isAssignableFrom(rawClass)) {
                    LOG.warn("Class {} is not a SleeEvent — skipping mapping to SBB {}", eventClassName, sbbName);
                    continue;
                }
                Class<? extends SleeEvent> eventType = (Class<? extends SleeEvent>) rawClass;
                container.mapEventToSbb(eventType, sbbName);
            } catch (ClassNotFoundException cnfe) {
                LOG.warn("Event class not found on classpath: {} (sbb={}) — skipping mapping",
                        eventClassName, sbbName);
            }
        }
    }
}
