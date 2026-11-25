package com.microjainslee.ra.diameter.transport;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import org.junit.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Cx UAR → UAA across two real Corsac stacks: the answer's AVPs reach the requesting SBB. */
public class CorsacRequestAnswerTest {

    private static final long CX = DiameterRaConfig.APP_CX;
    private static final long VENDOR_3GPP = 10_415L;

    @Test
    public void uarReachesTheHssAndTheUaaComesBackReadable() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Events hssEvents = new Events();
        Events cscfEvents = new Events();

        DiameterRaConfig hssCfg = config("hss.ra.test", port);
        hssCfg.setPeers(List.of(new DiameterRaConfig.Peer("127.0.0.1", 0, "server", "icscf.ra.test", "ra.test", Set.of(CX))));
        DiameterRaConfig cscfCfg = config("icscf.ra.test", 0);
        cscfCfg.setPeers(List.of(new DiameterRaConfig.Peer("127.0.0.1", port, "client", "hss.ra.test", "ra.test", Set.of(CX))));

        CorsacDiameterTransport hss = new CorsacDiameterTransport(hssCfg, new Callbacks());
        CorsacDiameterTransport cscf = new CorsacDiameterTransport(cscfCfg, new Callbacks());
        hss.setBootstrap(hssEvents);
        cscf.setBootstrap(cscfEvents);
        try {
            hss.start();
            cscf.start();
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && !(cscf.linkUp() && hss.linkUp())) {
                Thread.sleep(100);
            }
            assertTrue("link OPEN both sides", cscf.linkUp() && hss.linkUp());

            cscf.send(new SendDiameterRequest("cx-it-1", CX, 300, "hss.ra.test", "ra.test",
                    Map.of(1, "alice@ra.test")));
            DiameterRequestEvent uar = (DiameterRequestEvent) hssEvents.take();
            assertNotNull("UAR delivered to the HSS SLEE", uar);
            assertEquals(300, uar.commandCode());
            assertEquals("alice@ra.test", uar.avps().get(1));

            hss.send(new SendDiameterAnswer("cx-it-1", CX, 300, 2001L, uar.hopByHopId(), uar.endToEndId(),
                    Map.of(), List.of(DiameterAvp.utf8(602, VENDOR_3GPP, "sip:scscf.ra.test:5060", true))));
            DiameterAnswerEvent uaa = (DiameterAnswerEvent) cscfEvents.take();
            assertNotNull("UAA delivered to the I-CSCF SLEE", uaa);
            assertEquals(2001L, uaa.resultCode());
            assertEquals("Server-Name readable from the answer", "sip:scscf.ra.test:5060", uaa.avps().get(602));

            // TS 29.229 shape: SUBSEQUENT_REGISTRATION as Experimental-Result only, no Result-Code
            // (Corsac used to NPE on such an answer and drop it).
            cscf.send(new SendDiameterRequest("cx-it-2", CX, 300, "hss.ra.test", "ra.test",
                    Map.of(1, "alice@ra.test")));
            DiameterRequestEvent uar2 = (DiameterRequestEvent) hssEvents.take();
            hss.send(new SendDiameterAnswer("cx-it-2", CX, 300, 0L, uar2.hopByHopId(), uar2.endToEndId(),
                    Map.of(), List.of(
                            DiameterAvp.grouped(297, 0L, true, List.of(
                                    DiameterAvp.u32(266, 0L, VENDOR_3GPP, true),
                                    DiameterAvp.u32(298, 0L, 2002L, true))),
                            DiameterAvp.utf8(602, VENDOR_3GPP, "sip:scscf.ra.test:5060", true))));
            DiameterAnswerEvent uaa2 = (DiameterAnswerEvent) cscfEvents.take();
            assertNotNull("Experimental-Result-only UAA delivered", uaa2);
            assertEquals("outcome = Experimental-Result-Code", 2002L, uaa2.resultCode());
            assertEquals("2002", uaa2.avps().get(298));
            assertNull("no Result-Code AVP on the wire", uaa2.avps().get(268));
        } finally {
            cscf.stop();
            hss.stop();
        }
    }

    /**
     * Each Corsac inbound message waits on a worker's local queue until that worker's blocking poll of the
     * shared queue returns: with the mobius default (100 ms) a request/answer round trip on loopback took
     * ≈ 100 ms (≈ 50 ms per direction — seen as Gx CCR→CCA ≈ 52 ms in the PCRF on the EPC wire).
     */
    @Test
    public void requestAnswerRoundTripIsNotHeldByTheWorkerPoll() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Events hssEvents = new Events();
        Events cscfEvents = new Events();
        DiameterRaConfig hssCfg = config("hss.rtt.test", port);
        hssCfg.setPeers(List.of(new DiameterRaConfig.Peer("127.0.0.1", 0, "server", "icscf.rtt.test", "ra.test", Set.of(CX))));
        DiameterRaConfig cscfCfg = config("icscf.rtt.test", 0);
        cscfCfg.setPeers(List.of(new DiameterRaConfig.Peer("127.0.0.1", port, "client", "hss.rtt.test", "ra.test", Set.of(CX))));
        CorsacDiameterTransport hss = new CorsacDiameterTransport(hssCfg, new Callbacks());
        CorsacDiameterTransport cscf = new CorsacDiameterTransport(cscfCfg, new Callbacks());
        hss.setBootstrap(hssEvents);
        cscf.setBootstrap(cscfEvents);
        try {
            hss.start();
            cscf.start();
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && !(cscf.linkUp() && hss.linkUp())) {
                Thread.sleep(100);
            }
            assertTrue("link OPEN both sides", cscf.linkUp() && hss.linkUp());
            long[] rtt = new long[30];
            for (int i = 0; i < rtt.length; i++) {
                long t0 = System.nanoTime();
                cscf.send(new SendDiameterRequest("rtt-" + i, CX, 300, "hss.rtt.test", "ra.test",
                        Map.of(1, "alice@ra.test")));
                DiameterRequestEvent uar = (DiameterRequestEvent) hssEvents.take();
                hss.send(new SendDiameterAnswer("rtt-" + i, CX, 300, 2001L, uar.hopByHopId(), uar.endToEndId(),
                        Map.of(), List.of()));
                assertNotNull((DiameterAnswerEvent) cscfEvents.take());
                rtt[i] = (System.nanoTime() - t0) / 1_000_000L;
            }
            long[] warm = java.util.Arrays.copyOfRange(rtt, 10, rtt.length);
            java.util.Arrays.sort(warm);
            long p50 = warm[warm.length / 2];
            assertTrue("loopback request/answer p50 " + p50 + " ms (worker poll would make it ~100)", p50 < 20);
        } finally {
            cscf.stop();
            hss.stop();
        }
    }

    private static DiameterRaConfig config(String origin, int port) {
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost("127.0.0.1");
        cfg.setPort(port);
        cfg.setOriginHost(origin);
        cfg.setRealm("ra.test");
        cfg.setWatchdogTimeoutMs(0);
        return cfg;
    }

    private static final class Events implements RaBootstrapPort {
        private final BlockingQueue<SleeEvent> q = new LinkedBlockingQueue<>();

        @Override
        public ActivityHandle createActivityHandle(String id) {
            return () -> id;
        }

        @Override
        public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
            q.add(event);
        }

        SleeEvent take() throws InterruptedException {
            return q.poll(10, TimeUnit.SECONDS);
        }
    }

    private static final class Callbacks implements DiameterTransportCallbacks {
        @Override public void onPeerConnected(String peerId) { }
        @Override public void onPeerDisconnected(String peerId) { }
        @Override public void onMessage(String peerId, org.jdiameter.api.Message msg, MessageReplyWriter w) { }
    }
}
