/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms.quarkus.bootstrap;

import com.example.ms.quarkus.events.MsServiceCallEvent;
import com.example.ms.quarkus.sbbs.MsAppBridgeSbb;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import com.microjainslee.quarkus.ms.MsHttpGatewaySbb;
import com.microjainslee.quarkus.ms.MsHttpIngressSupport;

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
import java.util.Map;

/**
 * Quarkus CDI host for the MS demo.
 *
 * <p>Boot order (adapter-quarkus owns {@link MicroSleeContainer} create/start):
 * <ol>
 *   <li>Ensure container STARTED</li>
 *   <li>{@link ClusterManager} + {@link MicrosleeMsSupport} (catalog + SPI handlers)</li>
 *   <li>{@link MsHttpIngressSupport} for conditional HTTP RA + gateway</li>
 *   <li>Optional {@link MsAppBridgeSbb} when gateway is on this node</li>
 * </ol>
 */
@ApplicationScoped
public class MsQuarkusBootstrap {

    private static final Logger LOG = LogManager.getLogger(MsQuarkusBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    MsRuntimeHolder runtimeHolder;

    @ConfigProperty(name = "http.ra.port", defaultValue = "8080")
    int httpRaPort;

    @ConfigProperty(name = "jainslee.ms.cluster-enabled", defaultValue = "false")
    boolean clusterEnabled;

    @ConfigProperty(name = "jainslee.ms.cluster-stack", defaultValue = "tcp")
    String clusterStack;

    @ConfigProperty(name = "jainslee.ms.cluster-initial-hosts", defaultValue = "localhost[7800]")
    String clusterInitialHosts;

    @ConfigProperty(name = "jainslee.ms.ingress-service", defaultValue = "http-ra")
    String ingressService;

    @ConfigProperty(name = "jainslee.ms.health-ra-on-leaf", defaultValue = "true")
    boolean healthRaOnLeaf;

    private ClusterManager clusterManager;
    private volatile MsHttpIngressSupport.IngressResult ingress;

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
                container, clusterManager, config);
        Map<String, List<String>> bindings = runtime.registry() != null
                ? runtime.registry().describe()
                : Map.of();
        runtimeHolder.set(runtime, bindings);
        LOG.info("MS n-n handler bindings: {}", bindings);

        MsHttpIngressSupport.IngressResult result = MsHttpIngressSupport.wire(
                container,
                config,
                ingressService,
                httpRaPort,
                healthRaOnLeaf,
                runtime,
                MsHttpGatewaySbb.class,
                rt -> new MsHttpGatewaySbb(
                        () -> runtimeHolder.isReady() ? runtimeHolder.get() : null,
                        MsHttpIngressSupport.newIspnChild(container)));
        this.ingress = result;

        if (result.gatewayWired()) {
            wireAppBridgeSbb();
        }

        LOG.info("Quarkus MS ready: mode={} nodeId={} fabricClusterEnabled={} http.ra.port={} "
                        + "gateway={} httpRa={} ingressService={} localServices={}",
                config.mode(),
                nodeId,
                clusterEnabled,
                result.httpPort(),
                result.gatewayWired(),
                result.httpRaWired(),
                ingressService,
                describeLocal(config));
    }

    void onStop(@Observes ShutdownEvent ev) {
        MsHttpIngressSupport.IngressResult ep = ingress;
        ingress = null;
        if (ep != null) {
            try {
                ep.deactivateHttpRa();
            } catch (RuntimeException re) {
                LOG.warn("HTTP RA deactivate failed: {}", re.toString());
            }
        }
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

    private void wireAppBridgeSbb() {
        int droppedApp = container.getSbbTypeRegistry()
                .unregisterByName(MsAppBridgeSbb.class.getSimpleName());
        if (droppedApp > 0) {
            LOG.info("Dropped {} stale app-bridge SBB pool(s) (live-reload)", droppedApp);
        }
        container.registerSbbType(MsAppBridgeSbb.class,
                () -> new MsAppBridgeSbb(runtimeHolder, MsHttpIngressSupport.newIspnChild(container)));
        container.mapEventToSbb(MsServiceCallEvent.class, "MsAppBridgeSbb");
        LOG.info("App bridge SBB registered (MsAppBridgeSbb → IspnMsClientSbb → ispn-queue-ra)");
    }

    /** Bound HTTP RA port (useful for tests with {@code http.ra.port=0}). */
    public int httpPort() {
        MsHttpIngressSupport.IngressResult ep = ingress;
        return ep == null ? httpRaPort : ep.httpPort();
    }

    private static String describeLocal(DeploymentConfig config) {
        if (config.mode() == DeploymentConfig.Mode.SINGLE) {
            return "http-ra,http-aux,http-sbb";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("http-ra", "http-aux", "http-sbb")) {
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
