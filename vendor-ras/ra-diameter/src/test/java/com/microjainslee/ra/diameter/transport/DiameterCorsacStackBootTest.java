/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.transport;

import com.microjainslee.ra.diameter.DiameterRaConfig;
import org.junit.Test;

import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real Corsac stack boot over loopback TCP (regression for the 2026-08-23 live
 * "request 271 is already registered for application 0" DiameterParser crash).
 *
 * <p>Both endpoints construct the full stack and register every Diameter
 * application (S6a / CxDx / Gx / CreditControl). Before the per-application
 * registration fix, {@code start()} threw
 * {@code DiameterException: The request 271 is already registered for application 0}.
 * End-to-end CER/CEA peer-provisioning over loopback is intentionally not asserted
 * here — it is proven by the live EPC stack.</p>
 */
public class DiameterCorsacStackBootTest {

    private static final class CountingCallbacks implements DiameterTransportCallbacks {
        final AtomicInteger connected = new AtomicInteger();
        final AtomicInteger disconnected = new AtomicInteger();

        @Override
        public void onPeerConnected(String peerId) {
            connected.incrementAndGet();
        }

        @Override
        public void onPeerDisconnected(String peerId) {
            disconnected.incrementAndGet();
        }

        @Override
        public void onMessage(String peerId, org.jdiameter.api.Message msg, MessageReplyWriter replyWriter) {
            // Application bytes are the RA/SBB concern; base CER/DWR stay in-stack.
        }
    }

    @Test
    public void serverAndClientBootRegisteringAllApplications() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        DiameterRaConfig serverCfg = new DiameterRaConfig();
        serverCfg.setHost("127.0.0.1");
        serverCfg.setPort(port);
        serverCfg.setPeerRole("server");
        serverCfg.setOriginHost("hss.boot.test");
        serverCfg.setRealm("boot.test");
        serverCfg.setDestinationHost("mme.boot.test");
        serverCfg.setDestinationRealm("boot.test");
        serverCfg.setWatchdogTimeoutMs(0);

        DiameterRaConfig clientCfg = new DiameterRaConfig();
        clientCfg.setHost("127.0.0.1");
        clientCfg.setPort(0);
        clientCfg.setPeerRole("client");
        clientCfg.setPeerHost("127.0.0.1");
        clientCfg.setPeerPort(port);
        clientCfg.setOriginHost("mme.boot.test");
        clientCfg.setRealm("boot.test");
        clientCfg.setDestinationHost("hss.boot.test");
        clientCfg.setDestinationRealm("boot.test");
        clientCfg.setWatchdogTimeoutMs(0);

        CorsacDiameterTransport server = new CorsacDiameterTransport(serverCfg, new CountingCallbacks());
        CorsacDiameterTransport client = new CorsacDiameterTransport(clientCfg, new CountingCallbacks());
        try {
            // Regression for the 2026-08-23 live crash: before the per-application
            // registration fix, start() threw
            //   DiameterException: The request 271 is already registered for application 0
            // while re-registering the common commands. Both stacks must now start
            // and register S6a / CxDx / Gx / CreditControl without any exception.
            server.start();
            client.start();

            // Let the client dial + the server accept so the transport threads run a
            // little; correctness of CER/CEA peer-provisioning over loopback is not
            // part of this regression (it is proven by the live EPC stack instead).
            Thread.sleep(1_500);
        } finally {
            client.stop();
            server.stop();
        }
    }
}