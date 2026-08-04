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

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
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
import com.microjainslee.ms.ispn.IspnQueueServer;
import com.microjainslee.ms.ispn.IspnRemoteClientFactory;
import com.microjainslee.ms.ispn.IspnServiceLifecycleHooks;
import com.microjainslee.ms.ispn.IspnTransportManager;
import com.microjainslee.ms.ispn.ServiceStateRecord;
import com.microjainslee.ms.ispn.SleeQueueEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void publishStateAndQueryRecord() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        container.getRaCommandPort(IspnQueueRaEndpoint.RA_NAME)
                .sendCommand(new IspnQueueCommand.PublishServiceState(
                        "ping", ServiceState.DEGRADED, done));
        done.get(5, TimeUnit.SECONDS);

        CompletableFuture<ServiceStateRecord> rec = new CompletableFuture<>();
        container.getRaCommandPort(IspnQueueRaEndpoint.RA_NAME)
                .sendCommand(new IspnQueueCommand.QueryServiceStateRecord("ping", rec));
        ServiceStateRecord record = rec.get(5, TimeUnit.SECONDS);
        assertNotNull(record);
        assertEquals(ServiceState.DEGRADED, record.state());
        assertEquals("ra-ispn-test", record.nodeId());
    }

    @Test
    void ensureCachesAndNodeId() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        container.getRaCommandPort(IspnQueueRaEndpoint.RA_NAME)
                .sendCommand(new IspnQueueCommand.EnsureServiceCaches(List.of("ping", "extra"), done));
        done.get(5, TimeUnit.SECONDS);
        assertTrue(transport.clusterManager().getCacheManager()
                .cacheExists(IspnTransportManager.inboxCacheName("extra")));

        CompletableFuture<String> node = new CompletableFuture<>();
        container.getRaCommandPort(IspnQueueRaEndpoint.RA_NAME)
                .sendCommand(new IspnQueueCommand.QueryNodeId(node));
        assertEquals("ra-ispn-test", node.get(5, TimeUnit.SECONDS));
    }

    @Test
    void replyRemoteRequestWritesReplyCache() throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        container.getRaCommandPort(IspnQueueRaEndpoint.RA_NAME)
                .sendCommand(new IspnQueueCommand.ReplyRemoteRequest(
                        "corr-1",
                        SleeResponse.ok("ok".getBytes(StandardCharsets.UTF_8)),
                        done));
        done.get(5, TimeUnit.SECONDS);
        SleeQueueEntry entry = transport.replyCache().get("corr-1");
        assertNotNull(entry);
        assertEquals(SleeQueueEntry.EntryType.RESPONSE, entry.type());
    }

    @Test
    void eventInboundDeliveryCompletesViaEventFuture() throws Exception {
        AtomicReference<MsRemoteRequestEvent> fired = new AtomicReference<>();
        RaBootstrapPort mockBoot = new RaBootstrapPort() {
            @Override
            public ActivityHandle createActivityHandle(String id) {
                return () -> id;
            }

            @Override
            public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                if (event instanceof MsRemoteRequestEvent ms) {
                    fired.set(ms);
                    ms.response().complete(
                            SleeResponse.ok("from-event".getBytes(StandardCharsets.UTF_8)));
                }
            }
        };

        IspnQueueResourceAdaptor adaptor = new IspnQueueResourceAdaptor(
                bootstrap, transport, DeploymentConfig.singleNode(), InboundMode.EVENT);
        adaptor.activate(mockBoot);

        IspnQueueServer server = new IspnQueueServer(
                "ping-event", transport, adaptor.eventDelivery("ping-event"));
        server.start();
        try {
            SleeQueueEntry req = SleeQueueEntry.ofRequest(
                    new SleeRequest("echo", new byte[0]), "caller", false);
            transport.inboxCache("ping-event").put(req.correlationId(), req);

            SleeQueueEntry reply = null;
            for (int i = 0; i < 50; i++) {
                reply = transport.replyCache().get(req.correlationId());
                if (reply != null) {
                    break;
                }
                Thread.sleep(50);
            }
            assertNotNull(fired.get());
            assertNotNull(reply);
            assertEquals("from-event", new String(reply.toSleeResponse().payload(), StandardCharsets.UTF_8));
        } finally {
            server.stop();
            adaptor.deactivate();
        }
    }

    @SleeService(name = "ping", transport = TransportType.INFINISPAN_QUEUE)
    static final class PingService {
    }
}
