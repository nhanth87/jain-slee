/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus.ms;

import com.microjainslee.api.Sbb;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.quarkus.MicrosleeMsSupport;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.function.Function;

/**
 * Conditional HTTP ingress wiring for MS deployments.
 *
 * <ul>
 *   <li>SINGLE or {@code config.isLocal(ingressServiceName)} → HTTP RA + gateway SBB
 *       + {@code mapEventToSbb(HttpWebRequestEvent, gateway)}</li>
 *   <li>else if {@code healthRaOnLeaf} and any local service → HTTP RA only
 *       (built-in {@code GET /health})</li>
 *   <li>else → no HTTP</li>
 * </ul>
 *
 * <p>Pure Java helper (no Quarkus imports) living under the adapter-quarkus
 * package for embedder convenience.
 */
public final class MsHttpIngressSupport {

    private static final Logger LOG = LogManager.getLogger(MsHttpIngressSupport.class);

    private MsHttpIngressSupport() {
    }

    /**
     * Outcome of {@link #wire}: whether gateway / RA were registered and the
     * live HTTP endpoint (if any) for port discovery and shutdown.
     */
    public record IngressResult(
            boolean gatewayWired,
            boolean httpRaWired,
            HttpServerRaEndpoint httpEndpoint,
            int requestedPort) {

        /** Bound listen port when RA is up; otherwise the requested port. */
        public int httpPort() {
            return httpEndpoint == null ? requestedPort : httpEndpoint.port();
        }

        public void deactivateHttpRa() {
            HttpServerRaEndpoint ep = httpEndpoint;
            if (ep != null) {
                ep.deactivate();
            }
        }
    }

    /**
     * Wire with the default {@link MsHttpGatewaySbb}.
     */
    public static IngressResult wire(
            MicroSleeContainer container,
            DeploymentConfig config,
            String ingressServiceName,
            int httpPort,
            boolean healthRaOnLeaf,
            MicrosleeMsSupport.MsRuntime msRuntime) {
        return wire(container, config, ingressServiceName, httpPort, healthRaOnLeaf,
                msRuntime, MsHttpGatewaySbb.class, MsHttpGatewaySbb::new);
    }

    /**
     * Full wire: decide gateway vs health-only RA vs nothing, then register.
     *
     * @param gatewaySbbFactory creates a gateway SBB instance from the booted
     *                          {@link MicrosleeMsSupport.MsRuntime}; ignored when
     *                          the gateway is not wired
     */
    public static IngressResult wire(
            MicroSleeContainer container,
            DeploymentConfig config,
            String ingressServiceName,
            int httpPort,
            boolean healthRaOnLeaf,
            MicrosleeMsSupport.MsRuntime msRuntime,
            Class<? extends Sbb> gatewaySbbClass,
            Function<MicrosleeMsSupport.MsRuntime, ? extends Sbb> gatewaySbbFactory) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(ingressServiceName, "ingressServiceName");
        Objects.requireNonNull(msRuntime, "msRuntime");
        Objects.requireNonNull(gatewaySbbClass, "gatewaySbbClass");
        Objects.requireNonNull(gatewaySbbFactory, "gatewaySbbFactory");

        boolean wireGateway = config.mode() == DeploymentConfig.Mode.SINGLE
                || config.isLocal(ingressServiceName);
        boolean wireHttpRa = wireGateway
                || (healthRaOnLeaf && anyLocalService(config));

        boolean gatewayWired = false;
        HttpServerRaEndpoint endpoint = null;

        if (wireGateway) {
            wireGateway(container, msRuntime, gatewaySbbClass, gatewaySbbFactory);
            gatewayWired = true;
        }
        if (wireHttpRa) {
            endpoint = wireHttpRa(container, httpPort);
        }

        LOG.info("MS HTTP ingress: gateway={} httpRa={} port={} ingressService={} healthRaOnLeaf={}",
                gatewayWired, endpoint != null, httpPort, ingressServiceName, healthRaOnLeaf);
        return new IngressResult(gatewayWired, endpoint != null, endpoint, httpPort);
    }

    /**
     * Always register {@code ra-http-server} on {@code port} (no gateway).
     */
    public static HttpServerRaEndpoint wireHttpRa(MicroSleeContainer container, int port) {
        Objects.requireNonNull(container, "container");
        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setPort(port);
        ra.setHost("0.0.0.0");

        HttpServerRaEndpoint endpoint = new HttpServerRaEndpoint(ra);
        endpoint.setPort(port);
        container.registerRa(endpoint, endpoint);
        LOG.info("ra-http-server registered on port {}", port);
        return endpoint;
    }

    /**
     * Register gateway SBB + map {@link HttpWebRequestEvent} (no HTTP RA).
     */
    public static void wireGateway(
            MicroSleeContainer container,
            MicrosleeMsSupport.MsRuntime msRuntime,
            Class<? extends Sbb> gatewaySbbClass,
            Function<MicrosleeMsSupport.MsRuntime, ? extends Sbb> gatewaySbbFactory) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(msRuntime, "msRuntime");
        Objects.requireNonNull(gatewaySbbClass, "gatewaySbbClass");
        Objects.requireNonNull(gatewaySbbFactory, "gatewaySbbFactory");

        String sbbName = gatewaySbbClass.getSimpleName();
        int dropped = container.getSbbTypeRegistry().unregisterByName(sbbName);
        if (dropped > 0) {
            LOG.info("Dropped {} stale gateway SBB pool(s) (live-reload)", dropped);
        }

        container.registerSbbType(gatewaySbbClass, () -> gatewaySbbFactory.apply(msRuntime));
        container.createIesDispatcher();
        container.mapEventToSbb(HttpWebRequestEvent.class, sbbName);
        LOG.info("MS HTTP gateway SBB registered: {}", sbbName);
    }

    /**
     * Convenience: register default {@link MsHttpGatewaySbb}.
     */
    public static void wireGateway(
            MicroSleeContainer container,
            MicrosleeMsSupport.MsRuntime msRuntime) {
        wireGateway(container, msRuntime, MsHttpGatewaySbb.class, MsHttpGatewaySbb::new);
    }

    static boolean anyLocalService(DeploymentConfig config) {
        if (config.mode() == DeploymentConfig.Mode.SINGLE) {
            return true;
        }
        for (String name : config.services().keySet()) {
            if (config.isLocal(name)) {
                return true;
            }
        }
        return false;
    }
}
