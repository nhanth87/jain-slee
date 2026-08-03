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
import com.example.ms.quarkus.sbbs.MsGatewaySbb;
import com.example.ms.quarkus.services.HttpRaService;
import com.example.ms.quarkus.services.HttpSbbService;
import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

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
 * Quarkus CDI host for the MS demo.
 *
 * <p>Boot order (adapter-quarkus owns {@link MicroSleeContainer} create/start):
 * <ol>
 *   <li>Ensure container STARTED</li>
 *   <li>{@link ClusterManager} + {@link MicrosleeMsSupport} ({@code @SleeService})</li>
 *   <li>Conditional SBB + HTTP RA wiring by local services</li>
 * </ol>
 *
 * <ul>
 *   <li>{@code http-sbb} local (or SINGLE): gateway + bridge SBBs + HTTP RA ingress</li>
 *   <li>{@code http-ra} only: HTTP RA for {@code /health} (no gateway SBB)</li>
 * </ul>
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

    private ClusterManager clusterManager;
    private volatile HttpServerRaEndpoint httpEndpoint;

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

        // Handlers auto-bind via the jainslee-ms registry: the @SleeService
        // classes implement SleeServiceHandler (n-n providers also supported).
        MicrosleeMsSupport.MsRuntime runtime = MicrosleeMsSupport.start(
                container,
                clusterManager,
                config,
                List.of(
                        SleeServiceDescriptor.fromAnnotation(HttpRaService.class),
                        SleeServiceDescriptor.fromAnnotation(HttpSbbService.class)));

        runtimeHolder.set(runtime);

        boolean wireGateway = config.mode() == DeploymentConfig.Mode.SINGLE
                || config.isLocal("http-sbb");
        boolean wireHttpRa = wireGateway || config.isLocal("http-ra");

        if (wireGateway) {
            wireGatewaySbbs();
        }
        if (wireHttpRa) {
            wireHttpRa();
        }

        LOG.info("Quarkus MS ready: mode={} nodeId={} fabricClusterEnabled={} http.ra.port={} "
                        + "gatewaySbbs={} httpRa={} localServices={}",
                config.mode(),
                nodeId,
                clusterEnabled,
                httpRaPort,
                wireGateway,
                wireHttpRa,
                describeLocal(config));
    }

    void onStop(@Observes ShutdownEvent ev) {
        HttpServerRaEndpoint ep = httpEndpoint;
        httpEndpoint = null;
        if (ep != null) {
            try {
                ep.deactivate();
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

    private void wireGatewaySbbs() {
        int droppedGw = container.getSbbTypeRegistry()
                .unregisterByName(MsGatewaySbb.class.getSimpleName());
        int droppedApp = container.getSbbTypeRegistry()
                .unregisterByName(MsAppBridgeSbb.class.getSimpleName());
        if (droppedGw + droppedApp > 0) {
            LOG.info("Dropped {} stale SBB pool(s) (live-reload)", droppedGw + droppedApp);
        }

        container.registerSbbType(MsGatewaySbb.class,
                () -> new MsGatewaySbb(runtimeHolder));
        container.registerSbbType(MsAppBridgeSbb.class,
                () -> new MsAppBridgeSbb(runtimeHolder));
        container.createIesDispatcher();
        container.mapEventToSbb(HttpWebRequestEvent.class, "MsGatewaySbb");
        container.mapEventToSbb(MsServiceCallEvent.class, "MsAppBridgeSbb");
        LOG.info("Gateway SBBs registered (MsGatewaySbb, MsAppBridgeSbb)");
    }

    private void wireHttpRa() {
        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setPort(httpRaPort);
        ra.setHost("0.0.0.0");

        httpEndpoint = new HttpServerRaEndpoint(ra);
        httpEndpoint.setPort(httpRaPort);
        container.registerRa(httpEndpoint, httpEndpoint);
        LOG.info("ra-http-server registered on port {}", httpRaPort);
    }

    /** Bound HTTP RA port (useful for tests with {@code http.ra.port=0}). */
    public int httpPort() {
        HttpServerRaEndpoint ep = httpEndpoint;
        return ep == null ? httpRaPort : ep.port();
    }

    private static String describeLocal(DeploymentConfig config) {
        if (config.mode() == DeploymentConfig.Mode.SINGLE) {
            return "http-ra,http-sbb";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : List.of("http-ra", "http-sbb")) {
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
