/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.collab;

import com.microjainslee.api.ActivityHandle;
import gov.nist.javax.sip.parser.StringMsgParser;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/** Grill fix: inbound responses must not steal the UA reply peer. */
public class DialogRegistryPeerTest {

    private static final InetSocketAddress UA = new InetSocketAddress("10.0.0.9", 5060);
    private static final InetSocketAddress FS = new InetSocketAddress("10.0.0.2", 5060);

    @Test
    public void responseDoesNotOverwriteReplyPeer() throws Exception {
        DialogRegistry reg = new DialogRegistry();
        ActivityHandle handle = () -> "h1";
        var invite = new StringMsgParser().parseSIPMessage(inviteBytes(), true, false, null);
        reg.recordInbound("c1", handle, invite, UA, "UDP");
        assertEquals(UA, reg.find("c1").peer());

        var ok = new StringMsgParser().parseSIPMessage(okBytes(), true, false, null);
        reg.recordInbound("c1", handle, ok, FS, "UDP");
        assertEquals("reply peer must stay UA", UA, reg.find("c1").peer());
        assertNotNull(reg.find("c1").lastResponse());

        reg.recordRemotePeer("c1", FS, "UDP");
        assertEquals(FS, reg.find("c1").remotePeer());
        assertSame(UA, reg.find("c1").peer());
    }

    private static byte[] inviteBytes() {
        return ("INVITE sip:gw@127.0.0.1 SIP/2.0\r\n"
                + "Via: SIP/2.0/UDP 10.0.0.9:5060;branch=z9hG4bK1\r\n"
                + "From: <sip:alice@example.com>;tag=t1\r\n"
                + "To: <sip:gw@example.com>\r\n"
                + "Call-ID: c1\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Contact: <sip:alice@10.0.0.9:5060>\r\n"
                + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] okBytes() {
        return ("SIP/2.0 200 OK\r\n"
                + "Via: SIP/2.0/UDP 10.0.0.9:5060;branch=z9hG4bK1\r\n"
                + "From: <sip:alice@example.com>;tag=t1\r\n"
                + "To: <sip:gw@example.com>;tag=fs1\r\n"
                + "Call-ID: c1\r\n"
                + "CSeq: 1 INVITE\r\n"
                + "Contact: <sip:gw@10.0.0.2:5060>\r\n"
                + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
    }
}
