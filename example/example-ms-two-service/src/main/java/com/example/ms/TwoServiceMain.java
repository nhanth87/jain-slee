/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ms;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.SleeServiceCatalog;
import com.microjainslee.ms.core.SleeServiceHandlerRegistry;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnRemoteClientFactory;
import com.microjainslee.ms.ispn.IspnServiceLifecycleHooks;
import com.microjainslee.ms.ispn.IspnTransportManager;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Boots signaling + app. In {@code single} mode both run locally (Direct).
 * In {@code cluster} mode only services assigned to {@code JAINSLEE_NODE_ID}
 * activate; remote calls go through Infinispan queues.
 */
public final class TwoServiceMain {

    public static void main(String[] args) throws Exception {
        DeploymentConfig config = DeploymentConfig.load();
        ClusterManager clusterManager = new ClusterManager(
                MicroSleeConfiguration.defaults(), config.myNodeId());
        clusterManager.start();

        List<SleeServiceDescriptor> descriptors = loadDescriptors();

        IspnTransportManager transport = new IspnTransportManager(clusterManager);
        transport.ensureServiceCaches(
                descriptors.stream().map(SleeServiceDescriptor::name).toList());

        // Handlers auto-bind: the @SleeService classes implement
        // SleeServiceHandler, discovered by the jainslee-ms registry (n-n).
        SleeServiceHandlerRegistry registry = SleeServiceHandlerRegistry.discover(descriptors);
        IspnServiceLifecycleHooks hooks = new IspnServiceLifecycleHooks(transport, registry::resolve);

        MicrosleeBootstrap boot = MicrosleeBootstrap.create(
                config,
                descriptors,
                hooks,
                new IspnRemoteClientFactory(transport),
                transport);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            boot.stop();
            clusterManager.stop();
        }));

        boot.start();

        if (config.isLocal("app") || config.mode() == DeploymentConfig.Mode.SINGLE) {
            SleeResponse resp = boot.client("signaling")
                    .call(new SleeRequest("demo", "hello".getBytes(StandardCharsets.UTF_8)));
            System.out.println("signaling reply: " + new String(resp.payload(), StandardCharsets.UTF_8));
        } else {
            System.out.println("Node " + config.myNodeId()
                    + " hosting local services only; waiting (Ctrl+C to stop)");
            Thread.currentThread().join();
        }

        boot.stop();
        clusterManager.stop();
    }

    /**
     * Prefer classpath {@link SleeServiceCatalog}; fall back to annotation
     * scan of the known demo services if the catalog resource is absent.
     */
    static List<SleeServiceDescriptor> loadDescriptors() {
        List<SleeServiceDescriptor> fromCatalog = SleeServiceCatalog.load();
        if (!fromCatalog.isEmpty()) {
            return fromCatalog;
        }
        return List.of(
                SleeServiceDescriptor.fromAnnotation(SignalingService.class),
                SleeServiceDescriptor.fromAnnotation(AppService.class));
    }

    private TwoServiceMain() {
    }
}
