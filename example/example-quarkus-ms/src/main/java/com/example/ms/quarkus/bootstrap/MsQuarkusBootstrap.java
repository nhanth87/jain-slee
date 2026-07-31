/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.bootstrap;

import com.example.ms.quarkus.handlers.ServiceHandlers;
import com.example.ms.quarkus.services.AppService;
import com.example.ms.quarkus.services.SignalingService;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Starts {@link MicroSleeContainer}, {@link ClusterManager}, then the
 * microservice orchestrator (signaling + app) via {@link MicrosleeMsSupport}.
 */
@ApplicationScoped
public class MsQuarkusBootstrap {

    private static final Logger LOG = LogManager.getLogger(MsQuarkusBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    MsRuntimeHolder runtimeHolder;

    @ConfigProperty(name = "jainslee.ms.cluster-enabled", defaultValue = "false")
    boolean clusterEnabled;

    @ConfigProperty(name = "jainslee.ms.cluster-stack", defaultValue = "tcp")
    String clusterStack;

    @ConfigProperty(name = "jainslee.ms.cluster-initial-hosts", defaultValue = "localhost[7800]")
    String clusterInitialHosts;

    private ClusterManager clusterManager;

    void onStart(@Observes @Priority(1000) StartupEvent ev) throws Exception {
        DeploymentConfig config = DeploymentConfig.load();
        String nodeId = config.myNodeId() != null ? config.myNodeId() : "quarkus-ms";

        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }

        MicroSleeConfiguration sleeCfg = MicroSleeConfiguration.builder()
                .clusterEnabled(clusterEnabled)
                .clusterStack(clusterStack)
                .clusterInitialHosts(clusterInitialHosts)
                .nodeId(nodeId)
                .build();

        clusterManager = new ClusterManager(sleeCfg, nodeId);
        clusterManager.start();

        MicrosleeMsSupport.MsRuntime runtime = MicrosleeMsSupport.start(
                container,
                clusterManager,
                config,
                List.of(
                        SleeServiceDescriptor.fromAnnotation(SignalingService.class),
                        SleeServiceDescriptor.fromAnnotation(AppService.class)),
                ServiceHandlers::forDescriptor);

        runtimeHolder.set(runtime);

        LOG.info("Quarkus MS ready: mode={} nodeId={} clusterEnabled={} localServices={}",
                config.mode(),
                nodeId,
                clusterEnabled,
                config.mode() == DeploymentConfig.Mode.SINGLE
                        ? "signaling,app"
                        : describeLocal(config));
    }

    void onStop(@Observes ShutdownEvent ev) {
        try {
            if (runtimeHolder.isReady()) {
                runtimeHolder.get().bootstrap().stop();
            }
        } catch (Exception e) {
            LOG.warn("MS bootstrap stop failed: {}", e.toString());
        }
        if (clusterManager != null) {
            try {
                clusterManager.stop();
            } catch (Exception e) {
                LOG.warn("ClusterManager stop failed: {}", e.toString());
            }
        }
    }

    private static String describeLocal(DeploymentConfig config) {
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("signaling", "app")) {
            if (config.isLocal(name)) {
                if (!sb.isEmpty()) {
                    sb.append(',');
                }
                sb.append(name);
            }
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }
}
