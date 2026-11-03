package com.microjainslee.ra.diameter.transport;

import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import org.junit.Test;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Several Diameter peers per RA: HSS ← MME (S6a) + CSCFs (Cx), PCRF ← PGW-C (Gx) + P-CSCF (Rx). */
public class CorsacMultiPeerTest {

    private static final DiameterRaConfig.Peer MME = new DiameterRaConfig.Peer(
            "mme", 3868, "client", "mme.epc.lab", "epc.lab", Set.of(DiameterRaConfig.APP_S6A));
    private static final DiameterRaConfig.Peer ICSCF = new DiameterRaConfig.Peer(
            "icscf", 3868, "client", "icscf.ims.lab", "ims.lab", Set.of(DiameterRaConfig.APP_CX));
    private static final DiameterRaConfig.Peer SCSCF = new DiameterRaConfig.Peer(
            "scscf", 3868, "client", "scscf.ims.lab", "ims.lab", Set.of(DiameterRaConfig.APP_CX));
    private static final List<CorsacDiameterTransport.Route> ROUTES = List.of(
            new CorsacDiameterTransport.Route("diameter-ra", MME),
            new CorsacDiameterTransport.Route("diameter-ra-2", ICSCF),
            new CorsacDiameterTransport.Route("diameter-ra-3", SCSCF));

    @Test
    public void parsesPeerSpecs() {
        List<DiameterRaConfig.Peer> peers = DiameterRaConfig.parsePeers(
                "mme:3868:client:mme.epc.lab:epc.lab:s6a; icscf:3869:server:icscf.ims.lab:ims.lab:cx+16777236;pcrf:3868");
        assertEquals(3, peers.size());
        assertEquals(MME, peers.get(0));
        assertEquals(3869, peers.get(1).port());
        assertTrue(peers.get(1).server());
        assertEquals(Set.of(DiameterRaConfig.APP_CX, DiameterRaConfig.APP_RX), peers.get(1).applicationIds());
        assertEquals("client", peers.get(2).role());
        assertNull(peers.get(2).destinationHost());
        assertTrue(DiameterRaConfig.parsePeers(" ").isEmpty());
    }

    @Test
    public void legacySinglePeerStillWorks() {
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setPeerHost("hss");
        cfg.setPeerPort(3870);
        cfg.setPeerRole("client");
        List<DiameterRaConfig.Peer> peers = cfg.effectivePeers();
        assertEquals(1, peers.size());
        assertEquals("hss", peers.get(0).host());
        assertEquals(3870, peers.get(0).port());

        DiameterRaConfig server = new DiameterRaConfig();
        server.setPeerHost("epc-hss");
        server.setPeerPort(0);
        server.setPeerRole("server");
        assertEquals("server peer port 0 = any source port", 0, server.effectivePeers().get(0).port());
    }

    @Test
    public void answerLeavesOnTheLinkItsRequestArrivedOn() {
        Map<Long, String> answers = new HashMap<>();
        answers.put(CorsacDiameterTransport.answerKey(7L, 9L), "diameter-ra-3");
        SendDiameterAnswer maa = new SendDiameterAnswer("s1", DiameterRaConfig.APP_CX, 303, 2001L, 7L, 9L, Map.of());
        assertEquals("diameter-ra-3", CorsacDiameterTransport.route(maa, ROUTES, answers, id -> true));
        assertTrue("route is consumed", answers.isEmpty());
    }

    @Test
    public void requestGoesByDestinationHostThenApplicationThenRealm() {
        Map<Long, String> none = new HashMap<>();
        SendDiameterRequest toScscf = new SendDiameterRequest("s", DiameterRaConfig.APP_CX, 305,
                "scscf.ims.lab", "ims.lab", Map.of());
        assertEquals("diameter-ra-3", CorsacDiameterTransport.route(toScscf, ROUTES, none, id -> true));

        SendDiameterRequest idr = new SendDiameterRequest("s", DiameterRaConfig.APP_S6A, 319, null, "epc.lab", Map.of());
        assertEquals("diameter-ra", CorsacDiameterTransport.route(idr, ROUTES, none, id -> true));

        // Cx without host: prefer an OPEN Cx peer
        SendDiameterRequest rtr = new SendDiameterRequest("s", DiameterRaConfig.APP_CX, 304, null, "ims.lab", Map.of());
        assertEquals("diameter-ra-3", CorsacDiameterTransport.route(rtr, ROUTES, none, "diameter-ra-3"::equals));

        SendDiameterRequest unknownApp = new SendDiameterRequest("s", 99L, 1, null, "ims.lab", Map.of());
        assertEquals("diameter-ra-2", CorsacDiameterTransport.route(unknownApp, ROUTES, none, id -> true));

        SendDiameterRequest nothing = new SendDiameterRequest("s", 99L, 1, null, null, Map.of());
        assertEquals("first open link", "diameter-ra-2",
                CorsacDiameterTransport.route(nothing, ROUTES, none, "diameter-ra-2"::equals));
        assertEquals("first link when none is open", "diameter-ra",
                CorsacDiameterTransport.route(nothing, ROUTES, none, id -> false));
    }

    @Test
    public void atMostOneServerPeer() throws Exception {
        DiameterRaConfig cfg = baseConfig("hss.multi.test", freePort());
        cfg.setPeers(List.of(
                new DiameterRaConfig.Peer("127.0.0.1", 40001, "server", null, null, Set.of()),
                new DiameterRaConfig.Peer("127.0.0.1", 40002, "server", null, null, Set.of())));
        CorsacDiameterTransport t = new CorsacDiameterTransport(cfg, new Callbacks());
        try {
            t.start();
            fail("two server-role peers on one port must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(String.valueOf(expected.getCause()).contains("at most one server-role"));
        } finally {
            t.stop();
        }
    }

    /** One RA (HSS role) dials two peers; both links reach CER/CEA on their own. */
    @Test
    public void oneRaOpensTwoPeerLinks() throws Exception {
        int mmePort = freePort();
        int cscfPort = freePort();
        CorsacDiameterTransport mme = server("mme.multi.test", mmePort, "hss.multi.test");
        CorsacDiameterTransport cscf = server("icscf.multi.test", cscfPort, "hss.multi.test");

        DiameterRaConfig hssCfg = baseConfig("hss.multi.test", 0);
        hssCfg.setPeers(List.of(
                new DiameterRaConfig.Peer("127.0.0.1", mmePort, "client", "mme.multi.test", "multi.test",
                        Set.of(DiameterRaConfig.APP_S6A)),
                new DiameterRaConfig.Peer("127.0.0.1", cscfPort, "client", "icscf.multi.test", "multi.test",
                        Set.of(DiameterRaConfig.APP_CX))));
        CorsacDiameterTransport hss = new CorsacDiameterTransport(hssCfg, new Callbacks());
        try {
            mme.start();
            cscf.start();
            hss.start();
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline
                    && !(hss.linkUp("diameter-ra") && hss.linkUp("diameter-ra-2"))) {
                Thread.sleep(200);
            }
            assertTrue("S6a link to the MME: " + hss.peerStates(), hss.linkUp("diameter-ra"));
            assertTrue("Cx link to the I-CSCF: " + hss.peerStates(), hss.linkUp("diameter-ra-2"));
            assertEquals(2, hss.peerStates().size());
        } finally {
            hss.stop();
            cscf.stop();
            mme.stop();
        }
    }

    private static CorsacDiameterTransport server(String origin, int port, String peerOrigin) {
        DiameterRaConfig cfg = baseConfig(origin, port);
        cfg.setPeers(List.of(new DiameterRaConfig.Peer("127.0.0.1", 0, "server", peerOrigin, "multi.test", Set.of())));
        return new CorsacDiameterTransport(cfg, new Callbacks());
    }

    private static DiameterRaConfig baseConfig(String origin, int port) {
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost("127.0.0.1");
        cfg.setPort(port);
        cfg.setOriginHost(origin);
        cfg.setRealm("multi.test");
        cfg.setWatchdogTimeoutMs(0);
        return cfg;
    }

    private static int freePort() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static final class Callbacks implements DiameterTransportCallbacks {
        @Override public void onPeerConnected(String peerId) { }
        @Override public void onPeerDisconnected(String peerId) { }
        @Override public void onMessage(String peerId, org.jdiameter.api.Message msg, MessageReplyWriter w) { }
    }
}
