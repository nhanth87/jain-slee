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
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnRemoteClientFactory;
import com.microjainslee.ms.ispn.IspnServiceLifecycleHooks;
import com.microjainslee.ms.ispn.IspnTransportManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoServiceMainTest {

    @Test
    void singleModeEndToEnd() throws Exception {
        ClusterManager cm = new ClusterManager(MicroSleeConfiguration.defaults(), "demo");
        cm.start();
        try {
            IspnTransportManager transport = new IspnTransportManager(cm);
            List<SleeServiceDescriptor> descriptors = List.of(
                    SleeServiceDescriptor.fromAnnotation(SignalingService.class),
                    SleeServiceDescriptor.fromAnnotation(AppService.class));
            // Auto-binding: service classes implement SleeServiceHandler.
            IspnServiceLifecycleHooks hooks = new IspnServiceLifecycleHooks(
                    transport,
                    com.microjainslee.ms.core.SleeServiceHandlerRegistry
                            .discover(descriptors)::resolve);

            MicrosleeBootstrap boot = MicrosleeBootstrap.create(
                    DeploymentConfig.singleNode(),
                    descriptors,
                    hooks,
                    new IspnRemoteClientFactory(transport),
                    transport);
            boot.start();
            try {
                SleeResponse resp = boot.client("signaling")
                        .call(new SleeRequest("ping", new byte[0]));
                assertTrue(resp.success());
                assertEquals("signaling:ping", new String(resp.payload(), StandardCharsets.UTF_8));
            } finally {
                boot.stop();
            }
        } finally {
            cm.stop();
        }
    }
}
