package com.microjainslee.ra.diameter.transport;

import com.microjainslee.ra.diameter.DiameterRaConfig;
import org.junit.Assume;
import org.junit.Test;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A wildcard host must not pin the Diameter link to loopback (container-to-container S6a / Cx). */
public class CorsacLocalBindAddressTest {

    @Test
    public void explicitHostIsKept() throws Exception {
        assertEquals(InetAddress.getByName("127.0.0.1"),
                CorsacDiameterTransport.localBindAddress("127.0.0.1", true, InetAddress.getLoopbackAddress()));
    }

    @Test
    public void wildcardServerListensOnEveryInterface() throws Exception {
        assertTrue(CorsacDiameterTransport.localBindAddress("0.0.0.0", true, InetAddress.getLoopbackAddress())
                .isAnyLocalAddress());
        assertTrue(CorsacDiameterTransport.localBindAddress(null, true, InetAddress.getLoopbackAddress())
                .isAnyLocalAddress());
    }

    @Test
    public void wildcardClientUsesTheRouteSourceTowardsThePeer() throws Exception {
        assertTrue(CorsacDiameterTransport.localBindAddress("0.0.0.0", false, InetAddress.getLoopbackAddress())
                .isLoopbackAddress());
        InetAddress external = firstNonLoopbackIpv4();
        Assume.assumeTrue("needs a non-loopback IPv4 interface", external != null);
        InetAddress source = CorsacDiameterTransport.localBindAddress("0.0.0.0", false, external);
        assertFalse("a client towards a non-loopback peer must not bind 127.0.0.1", source.isLoopbackAddress());
        assertEquals(external, source);
    }

    @Test
    public void wildcardServerAcceptsOnANonLoopbackAddress() throws Exception {
        InetAddress external = firstNonLoopbackIpv4();
        Assume.assumeTrue("needs a non-loopback IPv4 interface", external != null);
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost("0.0.0.0");
        cfg.setPort(port);
        cfg.setPeerRole("server");
        cfg.setPeerHost(external.getHostAddress());
        cfg.setPeerPort(3868);
        cfg.setOriginHost("hss.bind.test");
        cfg.setRealm("bind.test");
        cfg.setWatchdogTimeoutMs(0);
        CorsacDiameterTransport server = new CorsacDiameterTransport(cfg, new DiameterTransportCallbacks() {
            @Override public void onPeerConnected(String peerId) { }
            @Override public void onPeerDisconnected(String peerId) { }
            @Override public void onMessage(String peerId, org.jdiameter.api.Message msg, MessageReplyWriter w) { }
        });
        try {
            server.start();
            boolean accepted = false;
            for (int i = 0; i < 30 && !accepted; i++) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(external, port), 500);
                    accepted = true;
                } catch (java.io.IOException notYet) {
                    Thread.sleep(100);
                }
            }
            assertTrue("server must accept on " + external.getHostAddress() + ":" + port, accepted);
        } finally {
            server.stop();
        }
    }

    private static InetAddress firstNonLoopbackIpv4() throws Exception {
        for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nif.isUp() || nif.isLoopback()) {
                continue;
            }
            for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLinkLocalAddress()) {
                    return a;
                }
            }
        }
        return null;
    }
}
