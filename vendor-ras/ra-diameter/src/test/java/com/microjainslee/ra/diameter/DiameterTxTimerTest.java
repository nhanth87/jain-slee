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
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterTimeoutEvent;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpSet;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.impl.parser.MessageParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Gx CCR-I seen on the wire: the PCEF blocked its SBB thread on the CCA (an event that thread
 * had to deliver) and always ran into its own timeout. The RA now owns the Tx timer: an answer
 * stops it, silence becomes a {@link DiameterTimeoutEvent} on the Session-Id activity.
 */
public class DiameterTxTimerTest {

    private static final String PEER = "pcrf";
    private final MessageParser parser = new MessageParser();
    private final List<SleeEvent> fired = new CopyOnWriteArrayList<>();
    private DiameterResourceAdaptor ra;

    @Before
    public void setUp() {
        ra = new DiameterResourceAdaptor();
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setTcpEnabled(false);
        cfg.setWatchdogTimeoutMs(0);
        cfg.setRequestTimeoutMs(60);
        cfg.setOriginHost("pgwc.lab");
        cfg.setRealm("epc.lab");
        ra.setConfig(cfg);
        ra.setBootstrapPort(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return () -> id; }
            @Override public void fireEvent(SleeEvent e, ActivityHandle h, Address a) { fired.add(e); }
        });
        ra.setOutboundSender(cmd -> { });
        ra.raConfigure();
        ra.raActive();
        ra.onPeerConnected(PEER);
        ra.peerTracker().markCapabilitiesExchanged(PEER);
    }

    @After
    public void tearDown() {
        ra.raInactive();
    }

    private static SendDiameterRequest ccr(String sid) {
        return new SendDiameterRequest(sid, 16777238L, 272, "pcrf.lab", "epc.lab", Map.of());
    }

    @Test
    public void silenceBecomesATimeoutEvent() throws Exception {
        ra.sendOutbound(ccr("gx;1"));
        assertEquals(1, ra.awaitingAnswers());
        long deadline = System.currentTimeMillis() + 2_000;
        while (fired.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, fired.size());
        DiameterTimeoutEvent t = (DiameterTimeoutEvent) fired.get(0);
        assertEquals("gx;1", t.sessionId());
        assertEquals(272, t.commandCode());
        assertEquals(0, ra.awaitingAnswers());
    }

    /** The CCA stops the timer, and its typed AVPs keep their values (Unsigned32, Address octets). */
    @Test
    public void answerStopsTheTimerAndKeepsTypedValues() throws Exception {
        ra.sendOutbound(ccr("gx;2"));
        IMessage cca = parser.createEmptyMessage(272, 16777238L);
        cca.setRequest(false);
        cca.getAvps().addAvp(Avp.SESSION_ID, "gx;2", true, false, true);
        cca.getAvps().addAvp(Avp.ORIGIN_HOST, "pcrf.lab", true, false, true);
        cca.getAvps().addAvp(Avp.ORIGIN_REALM, "epc.lab", true, false, true);
        cca.getAvps().addAvp(Avp.RESULT_CODE, 2001L, true, false, true);
        cca.getAvps().addAvp(416, 1L, true, false, true);                 // CC-Request-Type INITIAL
        cca.getAvps().addAvp(8, new byte[] {10, 64, 0, 2}, true, false);   // Framed-IP-Address
        AvpSet qos = cca.getAvps().addGroupedAvp(1049, 10415L, true, false); // Default-EPS-Bearer-QoS
        qos.addAvp(1028, 9L, 10415L, true, false, true);                   // QCI 9
        ra.ingestForTest(PEER, cca, a -> { });
        Thread.sleep(150);
        assertEquals(0, ra.awaitingAnswers());
        assertEquals(1, fired.size());
        DiameterAnswerEvent ans = (DiameterAnswerEvent) fired.get(0);
        assertTrue(ans.avpsStructured().stream().anyMatch(a -> a.code() == 416 && a.asUnsigned32() == 1L));
        DiameterAvp ip = ans.avpsStructured().stream().filter(a -> a.code() == 8).findFirst().orElseThrow();
        assertArrayEquals(new byte[] {10, 64, 0, 2}, ip.asOctets());
        DiameterAvp dq = ans.avpsStructured().stream().filter(a -> a.code() == 1049).findFirst().orElseThrow();
        assertEquals(9L, dq.children().get(0).asUnsigned32());
        assertEquals("1", ans.avps().get(416));
    }
}
