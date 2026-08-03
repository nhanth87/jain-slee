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

import com.microjainslee.ms.api.RemoteClientFactory;
import com.microjainslee.ms.api.ServiceReadinessView;
import com.microjainslee.ms.api.SleeServiceClient;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.core.dag.ServiceDependencyGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Facade that wires config + DAG + client factory + orchestrator.
 * Adapters call this after the SLEE container is up.
 */
public final class MicrosleeBootstrap {

    private final DeploymentConfig config;
    private final ServiceDependencyGraph graph;
    private final SleeServiceClientFactory clientFactory;
    private final MicrosleeServiceOrchestrator orchestrator;

    private MicrosleeBootstrap(
            DeploymentConfig config,
            ServiceDependencyGraph graph,
            SleeServiceClientFactory clientFactory,
            MicrosleeServiceOrchestrator orchestrator) {
        this.config = config;
        this.graph = graph;
        this.clientFactory = clientFactory;
        this.orchestrator = orchestrator;
    }

    /**
     * Auto-wired variant: handlers come from a discovered
     * {@link SleeServiceHandlerRegistry} (providers + self-handling service
     * classes) instead of app-supplied hooks.
     */
    public static MicrosleeBootstrap create(
            DeploymentConfig config,
            List<SleeServiceDescriptor> descriptors,
            RemoteClientFactory remoteClientFactory,
            ServiceReadinessView remoteReadiness) {
        return create(config, descriptors,
                SleeServiceHandlerRegistry.discover(descriptors),
                remoteClientFactory, remoteReadiness);
    }

    /** Auto-wired variant with a caller-prepared (n-n) handler registry. */
    public static MicrosleeBootstrap create(
            DeploymentConfig config,
            List<SleeServiceDescriptor> descriptors,
            SleeServiceHandlerRegistry registry,
            RemoteClientFactory remoteClientFactory,
            ServiceReadinessView remoteReadiness) {
        Objects.requireNonNull(registry, "registry");
        ServiceLifecycleHooks hooks = new ServiceLifecycleHooks() {
            @Override
            public SleeServiceHandler activate(SleeServiceDescriptor descriptor) {
                return registry.resolve(descriptor);
            }

            @Override
            public void deactivate(SleeServiceDescriptor descriptor) {
                // handlers resolved from the registry have no lifecycle
            }
        };
        return create(config, descriptors, hooks, remoteClientFactory, remoteReadiness);
    }

    public static MicrosleeBootstrap create(
            DeploymentConfig config,
            List<SleeServiceDescriptor> descriptors,
            ServiceLifecycleHooks hooks,
            RemoteClientFactory remoteClientFactory,
            ServiceReadinessView remoteReadiness) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(hooks, "hooks");
        List<SleeServiceDescriptor> list = new ArrayList<>(descriptors);
        ServiceDependencyGraph graph = new ServiceDependencyGraph(list);
        Map<String, SleeServiceDescriptor> byName = new LinkedHashMap<>();
        for (SleeServiceDescriptor d : list) {
            byName.put(d.name(), d);
        }
        SleeServiceClientFactory factory =
                new SleeServiceClientFactory(config, byName, remoteClientFactory);
        MicrosleeServiceOrchestrator orch =
                new MicrosleeServiceOrchestrator(config, graph, hooks, factory, remoteReadiness);
        return new MicrosleeBootstrap(config, graph, factory, orch);
    }

    public DeploymentConfig config() {
        return config;
    }

    public ServiceDependencyGraph graph() {
        return graph;
    }

    public SleeServiceClientFactory clientFactory() {
        return clientFactory;
    }

    public MicrosleeServiceOrchestrator orchestrator() {
        return orchestrator;
    }

    public void start() {
        orchestrator.startAll();
    }

    public void stop() {
        orchestrator.stopAll();
    }

    public <T> SleeServiceClient<T> client(String serviceName) {
        return clientFactory.client(serviceName);
    }
}
