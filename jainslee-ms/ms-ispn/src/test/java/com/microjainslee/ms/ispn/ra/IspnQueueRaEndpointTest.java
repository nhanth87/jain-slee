/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn.ra;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ms.api.ServiceState;
import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.TransportType;
import com.microjainslee.ms.api.annotation.SleeService;
import com.microjainslee.ms.core.MicrosleeBootstrap;
import com.microjainslee.ms.core.config.DeploymentConfig;
import com.microjainslee.ms.ispn.IspnRemoteClientFactory;
import com.microjainslee.ms.ispn.IspnServiceLifecycleHooks;
import com.microjainslee.ms.ispn.IspnTransportManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IspnQueueRaEndpointTest {

    private MicroSleeContainer container;
    private ClusterManager clusterManager;
    private MicrosleeBootstrap bootstrap;
    private IspnTransportManager transport;
    private IspnQueueRaEndpoint ra;

    @BeforeEach
    void setUp() throws Exception {
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64)
                .preferVirtualThreads(false)
                .sbbPoolMin(2)
                .sbbPoolMax(16)
                .sbbPerVirtualThread(false)
                .build());
        container.start();

        clusterManager = new ClusterManager(MicroSleeConfiguration.defaults(), "ra-ispn-test");
        clusterManager.start();

        DeploymentConfig config = DeploymentConfig.singleNode();
        List<SleeServiceDescriptor> descriptors = List.of(
                SleeServiceDescriptor.fromAnnotation(PingService.class));
        transport = new IspnTransportManager(clusterManager);
        transport.ensureServiceCaches(List.of("ping"));

        SleeServiceHandler handler = req ->
                SleeResponse.ok(("pong:" + req.operation()).getBytes(StandardCharsets.UTF_8));

        IspnServiceLifecycleHooks hooks = new IspnServiceLifecycleHooks(
                transport,
                d -> handler,
                new com.microjainslee.ms.core.ServiceLifecycleHooks() {
                    @Override
                    public SleeServiceHandler activate(SleeServiceDescriptor descriptor) {
                        return handler;
                    }

                    @Override
                    public void deactivate(SleeServiceDescriptor descriptor) {
                    }
                });

        bootstrap = MicrosleeBootstrap.create(
                config,
                descriptors,
                hooks,
                new IspnRemoteClientFactory(transport),
                transport);
        bootstrap.start();

        ra = new IspnQueueRaEndpoint(bootstrap, transport, config);
        container.registerRa(ra, ra);
    }

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
        if (clusterManager != null) {
            clusterManager.stop();
        }
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void callServiceViaCommandPort() throws Exception {
        CompletableFuture<SleeResponse> reply = new CompletableFuture<>();
        container.getRaCommandPort(IspnQueueRaEndpoint.RA_NAME)
                .sendCommand(new IspnQueueCommand.CallService(
                        "ping", new SleeRequest("hello", new byte[0]), reply));
        SleeResponse resp = reply.get(5, TimeUnit.SECONDS);
        assertTrue(resp.success());
        assertEquals("pong:hello", new String(resp.payload(), StandardCharsets.UTF_8));
    }

    @Test
    void queryLocalStateIsReady() throws Exception {
        CompletableFuture<ServiceState> reply = new CompletableFuture<>();
        container.getRaCommandPort(IspnQueueRaEndpoint.RA_NAME)
                .sendCommand(new IspnQueueCommand.QueryServiceState("ping", reply));
        assertEquals(ServiceState.READY, reply.get(5, TimeUnit.SECONDS));
    }

    @Test
    void notifyCompletes() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        container.getRaCommandPort(IspnQueueRaEndpoint.RA_NAME)
                .sendCommand(new IspnQueueCommand.NotifyService(
                        "ping", new SleeRequest("event", new byte[0]), done));
        done.get(5, TimeUnit.SECONDS);
    }

    @SleeService(name = "ping", transport = TransportType.INFINISPAN_QUEUE)
    static final class PingService {
    }
}
