/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import com.microjainslee.ra.diameter.transport.DiameterTransportCallbacks.MessageReplyWriter;

import org.jdiameter.api.Avp;
import org.jdiameter.api.Message;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.impl.parser.MessageParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * RA peer-ready without opening a real TCP port (tcpEnabled=false).
 */
public class DiameterResourceAdaptorPeerReadyTest {

    private static final String PEER = "test-peer-1";

    private final MessageParser parser = new MessageParser();
    private DiameterResourceAdaptor ra;
    private RecordingBootstrap bootstrap;

    @Before
    public void setUp() {
        ra = new DiameterResourceAdaptor();
        bootstrap = new RecordingBootstrap();
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setTcpEnabled(false);
        cfg.setWatchdogTimeoutMs(0);
        cfg.setOriginHost("ota.lab");
        cfg.setRealm("lab.local");
        ra.setConfig(cfg);
        ra.setBootstrapPort(bootstrap);
        ra.raConfigure();
        ra.raActive();
    }

    @After
    public void tearDown() {
        ra.raInactive();
    }

    @Test
    public void activeWithoutPeerIsNotReady() {
        assertTrue(ra.isActive());
        assertFalse(ra.isPeerConnected());
        assertFalse(ra.isPeerReady());
        assertEquals("diameter:no-peer", ra.peerDetail());
    }

    @Test
    public void tcpOnlyIsConnectedNotReady() {
        ra.onPeerConnected(PEER);
        assertTrue(ra.isPeerConnected());
        assertFalse(ra.isPeerReady());
    }

    @Test
    public void cerAutoCeaMakesPeerReadyAndDoesNotFireSbb() {
        ra.onPeerConnected(PEER);
        AtomicReference<Message> answered = new AtomicReference<>();
        MessageReplyWriter writer = answered::set;

        IMessage cer = parser.createEmptyMessage(Message.CAPABILITIES_EXCHANGE_REQUEST, 0L);
        cer.setRequest(true);
        cer.getAvps().addAvp(Avp.ORIGIN_HOST, "peer.lab", true, false, true);
        cer.getAvps().addAvp(Avp.ORIGIN_REALM, "lab.local", true, false, true);

        ra.ingestForTest(PEER, cer, writer);

        assertTrue(ra.isPeerReady());
        assertNotNull(answered.get());
        assertFalse(answered.get().isRequest());
        assertEquals(Message.CAPABILITIES_EXCHANGE_ANSWER, answered.get().getCommandCode());
        assertTrue(bootstrap.fired.isEmpty());
    }

    @Test
    public void applicationMessageFiresAfterPeerReady() {
        ra.onPeerConnected(PEER);
        ra.peerTracker().markCapabilitiesExchanged(PEER);
        assertTrue(ra.isPeerReady());

        IMessage req = parser.createEmptyMessage(265 /* AAR */, 16777216L /* Cx */);
        req.setRequest(true);
        req.getAvps().addAvp(Avp.SESSION_ID, "sess-1", true, false, true);
        req.getAvps().addAvp(Avp.ORIGIN_HOST, "peer.lab", true, false, true);
        req.getAvps().addAvp(Avp.ORIGIN_REALM, "lab.local", true, false, true);

        ra.ingestForTest(PEER, req, a -> { });

        assertEquals(1, bootstrap.fired.size());
        assertTrue(bootstrap.fired.get(0) instanceof DiameterRequestEvent);
    }

    @Test
    public void peerDisconnectClearsReady() {
        ra.onPeerConnected(PEER);
        ra.peerTracker().markCapabilitiesExchanged(PEER);
        assertTrue(ra.isPeerReady());
        ra.onPeerDisconnected(PEER);
        assertFalse(ra.isPeerConnected());
        assertFalse(ra.isPeerReady());
    }

    private static final class RecordingBootstrap implements RaBootstrapPort {
        final List<SleeEvent> fired = new ArrayList<>();

        @Override
        public ActivityHandle createActivityHandle(String id) {
            return () -> id;
        }

        @Override
        public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
            fired.add(event);
        }

        @Override
        public void endActivity(ActivityHandle handle) { }
    }
}
