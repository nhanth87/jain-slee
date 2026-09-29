/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.jss7.cluster;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.microjainslee.cluster.ClusterUnicast;
import com.microjainslee.cluster.InProcessCluster;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.NumberingPlan;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TcapForeignPdu;
import org.restcomm.protocols.ss7.tcap.api.TcapInboundDialogRouter;

/**
 * ADR 0007 D11 — DTID-based PDU forwarding across <b>real</b> cluster members
 * (JGroups on loopback). Node C is the witness-sized third member: it also
 * proves the overlap guard against a different node.
 */
public class Ss7InboundDialogRouterTest {

    private static final String RA = "jss7";

    private final ParameterFactory factory = new ParameterFactoryImpl();
    private Path pingDir;
    private InProcessCluster cluster;
    private FakeTcap tcapA;
    private FakeTcap tcapB;
    private Ss7InboundDialogRouter routerA;
    private Ss7InboundDialogRouter routerB;
    private TcapFailoverMetrics metricsB;

    @Before
    public void setUp() throws Exception {
        pingDir = Files.createTempDirectory("d11-ping");
        cluster = new InProcessCluster(pingDir, "d11-" + System.nanoTime(), "ss7-a", "ss7-b", "witness-c");
        tcapA = new FakeTcap();
        tcapB = new FakeTcap();
        metricsB = new TcapFailoverMetrics();
        routerA = new Ss7InboundDialogRouter(RA, cluster.node(0), new ClusterUnicast(cluster.node(0)),
                tcapA::provider, () -> factory, new TcapFailoverMetrics());
        routerB = new Ss7InboundDialogRouter(RA, cluster.node(1), new ClusterUnicast(cluster.node(1)),
                tcapB::provider, () -> factory, metricsB);
        routerA.start(1, 1_000);
        routerB.start(1_001, 2_000);
    }

    @After
    public void tearDown() {
        cluster.close();
    }

    @Test
    public void startHooksTheTcapProvider() {
        assertSame(routerA, tcapA.router.get());
        assertSame(routerB, tcapB.router.get());
    }

    @Test
    public void ownerIsResolvedFromTheDtid() throws Exception {
        awaitRangeReplicated(routerB, 500);
        assertEquals("ss7-a", routerB.ownerOf(500));
        assertEquals("ss7-b", routerB.ownerOf(1_500));
        assertNull(routerB.ownerOf(5_000));
    }

    @Test
    public void pduForRemoteDialogReachesOwnerIntact() throws Exception {
        awaitRangeReplicated(routerB, 500);
        TcapForeignPdu sent = pdu();

        assertTrue(routerB.routeForeign(500, sent));

        TcapForeignPdu got = tcapA.injected.poll(10, TimeUnit.SECONDS);
        assertNotNull("owner must receive the PDU", got);
        assertArrayEquals(sent.data(), got.data());
        assertEquals("called party must survive the hop", sent.calledParty(), got.calledParty());
        assertEquals("calling party (future remote address) must survive the hop",
                sent.callingParty(), got.callingParty());
        assertEquals(sent.callingParty().getGlobalTitle().getDigits(), got.callingParty().getGlobalTitle().getDigits());
        assertEquals(sent.sls(), got.sls());
        assertEquals(sent.networkId(), got.networkId());
        assertEquals(sent.incomingOpc(), got.incomingOpc());
        assertEquals(sent.preferredAspName(), got.preferredAspName());
        assertTrue("the receiving node must not process it", tcapB.injected.isEmpty());
        assertEquals(1, metricsB.foreignForwardedCount());
    }

    @Test
    public void ownOrUnknownDtidIsNotRouted() {
        assertFalse("own range: default local handling", routerB.routeForeign(1_500, pdu()));
        assertFalse("no range: default local handling", routerB.routeForeign(5_000, pdu()));
        assertEquals(0, metricsB.foreignForwardedCount());
    }

    @Test
    public void deadOwnerFallsThroughToTakeover() throws Exception {
        awaitRangeReplicated(routerB, 500);
        cluster.kill(0);
        awaitAbsent("ss7-a");

        assertFalse("owner gone: the resolver must be allowed to take over", routerB.routeForeign(500, pdu()));
        assertEquals(1, metricsB.foreignOwnerAbsentCount());
        assertEquals("the dead node's range must keep naming it, not vanish", "ss7-a", routerB.ownerOf(500));
    }

    @Test
    public void ownerWithoutReceiverIsReprocessedLocally() throws Exception {
        awaitRangeReplicated(routerB, 500);
        routerA.stop();                           // node alive, RA stopped

        assertTrue(routerB.routeForeign(500, pdu()));

        TcapForeignPdu fallback = tcapB.injected.poll(10, TimeUnit.SECONDS);
        assertNotNull("a definitive failure must re-process locally (→ resolver)", fallback);
        assertEquals(1, metricsB.foreignSendFailCount());
    }

    @Test
    public void overlappingRangeOnAnotherNodeRefusesToStart() throws Exception {
        awaitRangeReplicated(routerB, 500);
        Ss7InboundDialogRouter overlapping = new Ss7InboundDialogRouter(RA, cluster.node(2),
                new ClusterUnicast(cluster.node(2)), new FakeTcap()::provider, () -> factory, null);
        try {
            overlapping.start(900, 1_100);
            fail("an overlapping OTID range must not start");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("overlaps"));
        }
    }

    @Test
    public void portableAddressRoundTripsEveryGtKind() {
        SccpAddress[] addresses = {
                factory.createSccpAddress(RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, null, 2001, 8),
                factory.createSccpAddress(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                        factory.createGlobalTitle("25191", NatureOfAddress.INTERNATIONAL), 0, 6),
                factory.createSccpAddress(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                        factory.createGlobalTitle("25192", 0), 0, 6),
                factory.createSccpAddress(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                        factory.createGlobalTitle("25193", 0, NumberingPlan.ISDN_TELEPHONY,
                                factory.createEncodingScheme((byte) 1)), 0, 6),
                gt4("251911000001"),
        };
        for (SccpAddress a : addresses) {
            SccpAddress back = SccpAddressCodec.toSccp(SccpAddressCodec.toPortable(a), factory);
            assertEquals(a, back);
            assertEquals(a.getAddressIndicator().getRoutingIndicator(),
                    back.getAddressIndicator().getRoutingIndicator());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private SccpAddress gt4(String digits) {
        return factory.createSccpAddress(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                factory.createGlobalTitle(digits, 0, NumberingPlan.ISDN_TELEPHONY,
                        factory.createEncodingScheme((byte) 1), NatureOfAddress.INTERNATIONAL), 0, 8);
    }

    private TcapForeignPdu pdu() {
        return new TcapForeignPdu(new byte[] { 0x65, 0x03, 0x49, 0x01, 0x2a }, gt4("251911000001"),
                gt4("251922000009"), 7, 0, 2002, "asp-b");
    }

    private static void awaitRangeReplicated(Ss7InboundDialogRouter router, long otid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (router.ownerOf(otid) == null && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    private void awaitAbsent(String nodeId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (cluster.node(1).isNodePresent(nodeId) && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertFalse(nodeId + " must leave the view", cluster.node(1).isNodePresent(nodeId));
    }

    /** Records what jSS7 would see: the router hook and injected foreign PDUs. */
    private static final class FakeTcap {
        final AtomicReference<TcapInboundDialogRouter> router = new AtomicReference<>();
        final LinkedBlockingQueue<TcapForeignPdu> injected = new LinkedBlockingQueue<>();
        private final TCAPProvider proxy = (TCAPProvider) Proxy.newProxyInstance(
                TCAPProvider.class.getClassLoader(), new Class<?>[] { TCAPProvider.class },
                (p, method, args) -> switch (method.getName()) {
                    case "setInboundDialogRouter" -> {
                        router.set((TcapInboundDialogRouter) args[0]);
                        yield null;
                    }
                    case "getInboundDialogRouter" -> router.get();
                    case "processForeignPdu" -> {
                        injected.add((TcapForeignPdu) args[0]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    case "toString" -> "FakeTcap";
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        TCAPProvider provider() {
            return proxy;
        }
    }
}
