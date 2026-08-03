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

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.ServiceLifecycleHooks;
import com.microjainslee.ms.core.SleeServiceCatalog;
import com.microjainslee.ms.core.SleeServiceHandlerRegistry;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnRemoteClientFactory;
import com.microjainslee.ms.ispn.IspnServiceLifecycleHooks;
import com.microjainslee.ms.ispn.IspnTransportManager;
import com.microjainslee.ms.ispn.ra.IspnQueueRaEndpoint;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Optional Quarkus/embedder helper: wires {@link MicrosleeBootstrap} to an
 * existing {@link MicroSleeContainer} + {@link ClusterManager}.
 *
 * <p>Kept as a plain helper (not an eager CDI bean) so apps without ms-*
 * descriptors pay zero startup cost. Call from application {@code @Startup}
 * code after the container is running.
 */
public final class MicrosleeMsSupport {

    private MicrosleeMsSupport() {
    }

    /**
     * Booted microservice runtime: bootstrap + ISPN transport + resolved config
     * plus the handler registry and catalog descriptors used for this start.
     */
    public record MsRuntime(
            MicrosleeBootstrap bootstrap,
            IspnTransportManager transport,
            DeploymentConfig config,
            SleeServiceHandlerRegistry registry,
            List<SleeServiceDescriptor> descriptors) {
    }

    public static MicrosleeBootstrap boot(
            MicroSleeContainer container,
            ClusterManager clusterManager,
            List<SleeServiceDescriptor> descriptors,
            Function<SleeServiceDescriptor, SleeServiceHandler> handlerFactory)
            throws Exception {
        return start(container, clusterManager, DeploymentConfig.load(), descriptors, handlerFactory)
                .bootstrap();
    }

    /**
     * Catalog-driven start: load {@link SleeServiceCatalog}, discover handlers,
     * then follow the existing start path.
     */
    public static MsRuntime start(
            MicroSleeContainer container,
            ClusterManager clusterManager,
            DeploymentConfig config)
            throws Exception {
        List<SleeServiceDescriptor> descriptors = SleeServiceCatalog.load();
        SleeServiceHandlerRegistry registry = SleeServiceHandlerRegistry.discover(descriptors);
        return start(container, clusterManager, config, descriptors, registry);
    }

    /**
     * Auto-wired variant: handlers are discovered by
     * {@link SleeServiceHandlerRegistry#discover} (ServiceLoader providers +
     * self-handling {@code @SleeService} classes). No hand-written
     * name-to-handler glue in the application.
     */
    public static MsRuntime start(
            MicroSleeContainer container,
            ClusterManager clusterManager,
            DeploymentConfig config,
            List<SleeServiceDescriptor> descriptors)
            throws Exception {
        return start(container, clusterManager, config, descriptors,
                SleeServiceHandlerRegistry.discover(descriptors));
    }

    /**
     * Auto-wired variant with a caller-prepared n-n registry (programmatic
     * bindings on top of discovery).
     */
    public static MsRuntime start(
            MicroSleeContainer container,
            ClusterManager clusterManager,
            DeploymentConfig config,
            List<SleeServiceDescriptor> descriptors,
            SleeServiceHandlerRegistry registry)
            throws Exception {
        Objects.requireNonNull(registry, "registry");
        return doStart(container, clusterManager, config, descriptors, registry::resolve, registry);
    }

    public static MsRuntime start(
            MicroSleeContainer container,
            ClusterManager clusterManager,
            DeploymentConfig config,
            List<SleeServiceDescriptor> descriptors,
            Function<SleeServiceDescriptor, SleeServiceHandler> handlerFactory)
            throws Exception {
        return doStart(container, clusterManager, config, descriptors, handlerFactory, null);
    }

    private static MsRuntime doStart(
            MicroSleeContainer container,
            ClusterManager clusterManager,
            DeploymentConfig config,
            List<SleeServiceDescriptor> descriptors,
            Function<SleeServiceDescriptor, SleeServiceHandler> handlerFactory,
            SleeServiceHandlerRegistry registry)
            throws Exception {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(clusterManager, "clusterManager");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(handlerFactory, "handlerFactory");

        List<SleeServiceDescriptor> frozen = List.copyOf(descriptors);

        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        // Pre-define slee.queue.<every-service> on this node so peer clustered
        // listener add/remove (shutdown) does not hit ISPN000436.
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (SleeServiceDescriptor d : frozen) {
            names.add(d.name());
        }
        for (String svc : config.services().keySet()) {
            names.add(svc);
        }
        transport.ensureServiceCaches(names);

        ServiceLifecycleHooks raHooks = new ServiceLifecycleHooks() {
            @Override
            public SleeServiceHandler activate(SleeServiceDescriptor descriptor) {
                return handlerFactory.apply(descriptor);
            }

            @Override
            public void deactivate(SleeServiceDescriptor descriptor) {
                // no-op default — apps may wrap for RA deactivate
            }
        };

        IspnServiceLifecycleHooks hooks = new IspnServiceLifecycleHooks(
                transport, handlerFactory, raHooks);

        MicrosleeBootstrap bootstrap = MicrosleeBootstrap.create(
                config,
                frozen,
                hooks,
                new IspnRemoteClientFactory(transport),
                transport);
        bootstrap.start();

        // ADR 0002: SBB outbound MS traffic goes through ispn-queue-ra, not
        // MicrosleeBootstrap.client() directly.
        IspnQueueRaEndpoint ispnRa = new IspnQueueRaEndpoint(bootstrap, transport, config);
        container.registerRa(ispnRa, ispnRa);

        return new MsRuntime(bootstrap, transport, config, registry, frozen);
    }
}
