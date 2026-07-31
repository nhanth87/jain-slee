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
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnRemoteClientFactory;
import com.microjainslee.ms.ispn.IspnServiceLifecycleHooks;
import com.microjainslee.ms.ispn.IspnTransportManager;

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
     * Booted microservice runtime: bootstrap + ISPN transport + resolved config.
     */
    public record MsRuntime(
            MicrosleeBootstrap bootstrap,
            IspnTransportManager transport,
            DeploymentConfig config) {
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

    public static MsRuntime start(
            MicroSleeContainer container,
            ClusterManager clusterManager,
            DeploymentConfig config,
            List<SleeServiceDescriptor> descriptors,
            Function<SleeServiceDescriptor, SleeServiceHandler> handlerFactory)
            throws Exception {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(clusterManager, "clusterManager");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(handlerFactory, "handlerFactory");

        IspnTransportManager transport = new IspnTransportManager(clusterManager);

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
                descriptors,
                hooks,
                new IspnRemoteClientFactory(transport),
                transport);
        bootstrap.start();
        return new MsRuntime(bootstrap, transport, config);
    }
}
