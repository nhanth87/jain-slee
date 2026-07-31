/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core;

import com.microjainslee.ms.api.ServiceReadinessView;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.exception.ServiceStartupException;
import com.microjainslee.ms.api.exception.ServiceStartupTimeoutException;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.core.dag.ServiceDependencyGraph;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Starts/stops local services in DAG order, waiting for hard dependencies.
 */
public final class MicrosleeServiceOrchestrator {

    private static final Logger LOG = LogManager.getLogger(MicrosleeServiceOrchestrator.class);

    private final DeploymentConfig config;
    private final ServiceDependencyGraph graph;
    private final ServiceLifecycleHooks hooks;
    private final SleeServiceClientFactory clientFactory;
    private final ServiceReadinessView remoteReadiness;
    private final Map<String, ServiceState> localStates = new ConcurrentHashMap<>();
    private final List<String> startedLocal = new ArrayList<>();

    public MicrosleeServiceOrchestrator(
            DeploymentConfig config,
            ServiceDependencyGraph graph,
            ServiceLifecycleHooks hooks,
            SleeServiceClientFactory clientFactory,
            ServiceReadinessView remoteReadiness) {
        this.config = Objects.requireNonNull(config);
        this.graph = Objects.requireNonNull(graph);
        this.hooks = Objects.requireNonNull(hooks);
        this.clientFactory = Objects.requireNonNull(clientFactory);
        this.remoteReadiness = remoteReadiness != null
                ? remoteReadiness
                : name -> localStates.getOrDefault(name, ServiceState.STOPPED);
        for (String name : graph.nodes().keySet()) {
            localStates.put(name, ServiceState.STOPPED);
        }
    }

    public Map<String, ServiceState> localStates() {
        return Map.copyOf(localStates);
    }

    public void startAll() {
        List<String> order = graph.resolveStartOrder();
        LOG.info("Microservice start order: {}", order);

        for (String name : order) {
            SleeServiceDescriptor desc = graph.get(name);
            waitForDependencies(desc);

            if (!config.isLocal(name)) {
                LOG.info("Skipping remote service '{}' on node {}", name, config.myNodeId());
                continue;
            }

            localStates.put(name, ServiceState.STARTING);
            hooks.publishState(name, ServiceState.STARTING);
            try {
                SleeServiceHandler handler = hooks.activate(desc);
                clientFactory.registerLocalHandler(name, handler);
                localStates.put(name, ServiceState.READY);
                hooks.publishState(name, ServiceState.READY);
                startedLocal.add(name);
                LOG.info("Service '{}' READY", name);
            } catch (Exception e) {
                localStates.put(name, ServiceState.STOPPED);
                hooks.publishState(name, ServiceState.STOPPED);
                throw new ServiceStartupException("Failed to start service '" + name + "'", e);
            }
        }
    }

    public void stopAll() {
        List<String> order = new ArrayList<>(startedLocal);
        // reverse of start among locals; fall back to graph stop order filtered
        if (order.isEmpty()) {
            for (String name : graph.resolveStopOrder()) {
                if (config.isLocal(name)) {
                    order.add(name);
                }
            }
        } else {
            java.util.Collections.reverse(order);
        }

        for (String name : order) {
            SleeServiceDescriptor desc = graph.get(name);
            try {
                hooks.deactivate(desc);
            } catch (Exception e) {
                LOG.warn("Error stopping service '{}': {}", name, e.toString());
            } finally {
                clientFactory.unregisterLocalHandler(name);
                localStates.put(name, ServiceState.STOPPED);
                hooks.publishState(name, ServiceState.STOPPED);
            }
        }
        startedLocal.clear();
    }

    private void waitForDependencies(SleeServiceDescriptor desc) {
        for (String dep : desc.dependsOn()) {
            long timeout = desc.startupTimeoutMs();
            long deadline = System.currentTimeMillis() + timeout;
            while (!isReady(dep)) {
                if (System.currentTimeMillis() > deadline) {
                    throw new ServiceStartupTimeoutException(
                            desc.name() + " timed out waiting for " + dep
                                    + " (timeoutMs=" + timeout + ")");
                }
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ServiceStartupException(
                            desc.name() + " interrupted waiting for " + dep, ie);
                }
            }
        }
    }

    private boolean isReady(String serviceName) {
        if (config.isLocal(serviceName)) {
            return localStates.get(serviceName) == ServiceState.READY;
        }
        ServiceState remote = remoteReadiness.stateOf(serviceName);
        return remote == ServiceState.READY;
    }
}
