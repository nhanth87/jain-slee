/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.collab;

import com.microjainslee.ra.sipservlet.SipRaConfig;
import com.microjainslee.ra.sipservlet.command.SendBye;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.transport.SipTransport;
import gov.nist.javax.sip.message.SIPResponse;
import gov.nist.javax.sip.parser.StringMsgParser;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Cross-node lab HA: export portable meta → empty registry restore → SendResponse/SendBye.
 */
public class DialogRegistryRestoreOutboundTest {

    private static final String CALL_ID = "ha-call-1@10.0.0.9";
    private static final InetSocketAddress UA = new InetSocketAddress("10.0.0.9", 5060);
    private static final InetSocketAddress FS = new InetSocketAddress("10.0.0.2", 5060);

    private static final class CapturingTransport implements SipTransport {
        final List<String> sent = new ArrayList<>();
        final List<InetSocketAddress> targets = new ArrayList<>();

        @Override public void start() { }
        @Override public void stop() { }
        @Override public String protocol() { return "UDP"; }

        @Override
        public boolean send(byte[] data, InetSocketAddress target) {
            sent.add(new String(data, StandardCharsets.UTF_8));
            targets.add(target);
            return true;
        }
    }

    private DialogRegistry source;
    private CapturingTransport transport;
    private NettySipOutboundSender sender;

    @Before
    public void setUp() throws Exception {
        source = new DialogRegistry();
        transport = new CapturingTransport();
        Map<String, SipTransport> transports = new HashMap<>();
        transports.put("UDP", transport);
        SipRaConfig config = new SipRaConfig();
        config.setHost("127.0.0.1");
        config.setUdpPort(5060);
        sender = new NettySipOutboundSender(config, source, transports);
    }

    @Test
    public void restorePortableThenSendResponseTargetsUaPeer() throws Exception {
        var invite = new StringMsgParser().parseSIPMessage(inviteBytes(), true, false, null);
        source.recordInbound(CALL_ID, () -> CALL_ID, invite, UA, "UDP");
        source.recordRemotePeer(CALL_ID, FS, "UDP");

        DialogRegistry.PortableDialogMeta meta = source.exportPortable(CALL_ID);
        assertNotNull(meta);
        assertTrue(meta.hasWireEssentials());

        DialogRegistry failover = new DialogRegistry();
        failover.restorePortable(meta, () -> CALL_ID);
        assertNotNull(failover.find(CALL_ID).lastRequest());
        assertEquals(UA, failover.find(CALL_ID).peer());

        Map<String, SipTransport> transports = new HashMap<>();
        transports.put("UDP", transport);
        SipRaConfig config = new SipRaConfig();
        config.setHost("127.0.0.1");
        config.setUdpPort(5060);
        NettySipOutboundSender out = new NettySipOutboundSender(config, failover, transports);
        out.send(new SendResponse(CALL_ID, 200, "OK"));

        assertEquals(1, transport.sent.size());
        assertTrue(transport.sent.get(0).startsWith("SIP/2.0 200"));
        assertEquals(UA, transport.targets.get(0));
    }

    @Test
    public void restoreWithFarResponseThenSendByeTowardTrunk() throws Exception {
        var invite = new StringMsgParser().parseSIPMessage(inviteBytes(), true, false, null);
        source.recordInbound(CALL_ID, () -> CALL_ID, invite, UA, "UDP");
        source.recordRemotePeer(CALL_ID, FS, "UDP");
        SIPResponse far200 = (SIPResponse) new StringMsgParser().parseSIPMessage(
                far200Bytes(), true, false, null);
        source.recordInbound(CALL_ID, () -> CALL_ID, far200, FS, "UDP");

        DialogRegistry.PortableDialogMeta meta = source.exportPortable(CALL_ID);
        assertTrue(meta.hasFarWireEssentials());
        assertNotNull(meta.farFromTag());

        DialogRegistry failover = new DialogRegistry();
        failover.restorePortable(meta, () -> CALL_ID);
        assertNotNull(failover.find(CALL_ID).lastResponse());
        assertEquals(FS, failover.find(CALL_ID).remotePeer());

        Map<String, SipTransport> transports = new HashMap<>();
        transports.put("UDP", transport);
        SipRaConfig config = new SipRaConfig();
        config.setHost("127.0.0.1");
        config.setUdpPort(5060);
        NettySipOutboundSender out = new NettySipOutboundSender(config, failover, transports);
        out.send(new SendBye(CALL_ID));

        assertEquals(1, transport.sent.size());
        assertTrue(transport.sent.get(0).startsWith("BYE "));
        assertEquals(FS, transport.targets.get(0));
    }

    private static byte[] inviteBytes() {
        return ("INVITE sip:gw@127.0.0.1 SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 10.0.0.9:5060;branch=z9hG4bK1\r\n"
                + "From: <sip:alice@example.com>;tag=ua-from\r\n"
                + "To: <sip:gw@example.com>\r\n"
                + "Call-ID: " + CALL_ID + "\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Contact: <sip:alice@10.0.0.9:5060>\r\n"
                + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] far200Bytes() {
        return ("SIP/2.0 200 OK\r\n"
                + "Via: SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-out\r\n"
                + "From: <sip:alice@example.com>;tag=far-uac\r\n"
                + "To: <sip:1000@10.0.0.2>;tag=fs-to\r\n"
                + "Call-ID: " + CALL_ID + "\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Contact: <sip:1000@10.0.0.2:5060>\r\n"
                + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}
