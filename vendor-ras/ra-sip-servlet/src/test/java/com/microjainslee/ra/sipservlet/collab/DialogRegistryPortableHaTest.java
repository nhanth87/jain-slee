package com.microjainslee.ra.sipservlet.collab;

import com.microjainslee.api.ActivityHandle;
import gov.nist.javax.sip.parser.StringMsgParser;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Portable DialogRegistry meta export/restore for N–N failover priming. */
public class DialogRegistryPortableHaTest {

    private static final InetSocketAddress UA = new InetSocketAddress("10.0.0.9", 5060);
    private static final InetSocketAddress FS = new InetSocketAddress("10.0.0.2", 5060);

    @Test
    public void exportRestorePeersAndCseq() throws Exception {
        DialogRegistry reg = new DialogRegistry();
        ActivityHandle handle = () -> "h1";
        var invite = new StringMsgParser().parseSIPMessage(inviteBytes(), true, false, null);
        reg.recordInbound("c1", handle, invite, UA, "UDP");
        reg.recordRemotePeer("c1", FS, "UDP");

        DialogRegistry.PortableDialogMeta meta = reg.exportPortable("c1");
        assertNotNull(meta);
        assertEquals("10.0.0.9", meta.peerHost());
        assertEquals(5060, meta.peerPort());
        assertEquals("10.0.0.2", meta.remotePeerHost());
        assertTrue(meta.cseq() >= 1);

        Map<String, String> attrs = meta.toAttrs();
        DialogRegistry.PortableDialogMeta fromAttrs =
                DialogRegistry.PortableDialogMeta.fromAttrs("c1", attrs);
        assertNotNull(fromAttrs);

        DialogRegistry peerNode = new DialogRegistry();
        peerNode.restorePortable(fromAttrs, () -> "c1");
        assertTrue(peerNode.contains("c1"));
        assertEquals(UA, peerNode.find("c1").peer());
        assertEquals(FS, peerNode.find("c1").remotePeer());
        // No SIPRequest — honest HA limit
        assertNull(peerNode.find("c1").lastRequest());
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
}
