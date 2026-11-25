package com.microjainslee.ra.diameter.transport;

import com.microjainslee.ra.diameter.DiameterRaConfig;
import org.junit.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A peer configured by DNS name whose address changes (a Kubernetes pod restarted with a new IP): the
 * server-role link only accepts the address it was created with, so the RA re-resolves the name while
 * the link is down and re-creates the link.
 */
public class CorsacPeerDnsRefreshTest {

    @Test
    public void serverLinkFollowsThePeerToItsNewAddress() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Map<String, InetAddress> dns = new ConcurrentHashMap<>();
        dns.put("pgwc.k8s.test", InetAddress.getByName("127.0.0.2"));   // the pod's old IP
        CorsacDiameterTransport.Resolver resolver = host -> {
            InetAddress a = dns.get(host);
            if (a != null) {
                return a;
            }
            if (host.endsWith(".test")) {
                throw new UnknownHostException(host);
            }
            return InetAddress.getByName(host);
        };

        DiameterRaConfig pcrfCfg = config("pcrf.k8s.test", port);
        pcrfCfg.setPeerRefreshMs(200);
        pcrfCfg.setPeers(List.of(new DiameterRaConfig.Peer("pgwc.k8s.test", 0, "server", "pgwc.k8s.test", "k8s.test",
                Set.of(DiameterRaConfig.APP_GX))));
        DiameterRaConfig pgwcCfg = config("pgwc.k8s.test", 0);
        pgwcCfg.setPeers(List.of(new DiameterRaConfig.Peer("127.0.0.1", port, "client", "pcrf.k8s.test", "k8s.test",
                Set.of(DiameterRaConfig.APP_GX))));

        CorsacDiameterTransport pcrf = new CorsacDiameterTransport(pcrfCfg, new Callbacks(), resolver);
        CorsacDiameterTransport pgwc = new CorsacDiameterTransport(pgwcCfg, new Callbacks());
        try {
            pcrf.start();
            pgwc.start();
            Thread.sleep(2_000);
            assertFalse("client from 127.0.0.1 is not the configured 127.0.0.2", pcrf.linkUp());

            dns.put("pgwc.k8s.test", InetAddress.getByName("127.0.0.1"));  // the pod came back elsewhere
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline && !(pcrf.linkUp() && pgwc.linkUp())) {
                Thread.sleep(100);
            }
            assertTrue("link OPEN after the name moved", pcrf.linkUp() && pgwc.linkUp());
        } finally {
            pgwc.stop();
            pcrf.stop();
        }
    }

    @Test
    public void onlyNamesAreReResolved() {
        assertTrue(CorsacDiameterTransport.byName(peer("epc-pgwc.epc.svc.cluster.local")));
        assertTrue(CorsacDiameterTransport.byName(peer("hss")));
        assertFalse(CorsacDiameterTransport.byName(peer("172.20.10.13")));
        assertFalse(CorsacDiameterTransport.byName(peer("fd00::13")));
        assertFalse(CorsacDiameterTransport.byName(peer("")));
    }

    private static DiameterRaConfig.Peer peer(String host) {
        return new DiameterRaConfig.Peer(host, 3868, "server", "x.test", "test", Set.of(DiameterRaConfig.APP_GX));
    }

    private static DiameterRaConfig config(String origin, int port) {
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost("127.0.0.1");
        cfg.setPort(port);
        cfg.setOriginHost(origin);
        cfg.setRealm("k8s.test");
        cfg.setWatchdogTimeoutMs(0);
        return cfg;
    }

    private static final class Callbacks implements DiameterTransportCallbacks {
        @Override public void onPeerConnected(String peerId) { }
        @Override public void onPeerDisconnected(String peerId) { }
        @Override public void onMessage(String peerId, org.jdiameter.api.Message msg, MessageReplyWriter w) { }
    }
}
