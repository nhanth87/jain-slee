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

import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.annotation.SleeService;
import com.microjainslee.ms.core.config.DeploymentConfig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicrosleeBootstrapSingleModeTest {

    @SleeService(name = "signaling")
    static final class SignalingMarker {}

    @SleeService(name = "app", dependsOn = {"signaling"})
    static final class AppMarker {}

    @Test
    void directCallAfterDagStart() {
        Map<String, AtomicInteger> starts = new ConcurrentHashMap<>();
        ServiceLifecycleHooks hooks = new ServiceLifecycleHooks() {
            @Override
            public SleeServiceHandler activate(SleeServiceDescriptor descriptor) {
                starts.computeIfAbsent(descriptor.name(), n -> new AtomicInteger()).incrementAndGet();
                return req -> SleeResponse.ok(("echo:" + descriptor.name() + ":" + req.operation())
                        .getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public void deactivate(SleeServiceDescriptor descriptor) {
                // no-op
            }
        };

        MicrosleeBootstrap boot = MicrosleeBootstrap.create(
                DeploymentConfig.singleNode(),
                List.of(
                        SleeServiceDescriptor.fromAnnotation(SignalingMarker.class),
                        SleeServiceDescriptor.fromAnnotation(AppMarker.class)),
                hooks,
                null,
                null);

        boot.start();
        try {
            assertEquals(1, starts.get("signaling").get());
            assertEquals(1, starts.get("app").get());
            assertEquals(ServiceState.READY, boot.orchestrator().localStates().get("signaling"));

            var client = boot.client("signaling");
            SleeResponse resp = client.call(new SleeRequest("ping", new byte[0]));
            assertTrue(resp.success());
            assertArrayEquals("echo:signaling:ping".getBytes(StandardCharsets.UTF_8), resp.payload());
        } finally {
            boot.stop();
        }
    }
}
