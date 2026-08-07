/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.collab;

import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.ims.ImsSipHeaderNames;
import gov.nist.javax.sip.parser.StringMsgParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DefaultSipEventClassifierImsTest {

    @Test
    public void inviteExtractsWhitelistedImsHeaders() throws Exception {
        String raw = """
                INVITE sip:gw@127.0.0.1:5060 SIP/2.0\r
                Via: SIP/2.0/UDP 10.0.0.9:5060;branch=z9hG4bKims1\r
                Max-Forwards: 70\r
                To: <sip:gw@example.com>\r
                From: <sip:alice@example.com>;tag=t1\r
                Call-ID: ims-classify-1@10.0.0.9\r
                CSeq: 1 INVITE\r
                Contact: <sip:alice@10.0.0.9:5060>\r
                P-Asserted-Identity: <sip:alice@ims.example>\r
                P-Access-Network-Info: 3GPP-E-UTRAN;utran-cell-id-3gpp=001\r
                P-Charging-Vector: icid-value=xyz\r
                X-Ignored: nope\r
                Content-Length: 0\r
                \r
                """;
        var msg = new StringMsgParser().parseSIPMessage(raw.getBytes(StandardCharsets.US_ASCII), true, false, null);
        SipInviteEvent e = (SipInviteEvent) new DefaultSipEventClassifier().classify(msg, "ims-classify-1@10.0.0.9");

        assertEquals("<sip:alice@ims.example>", e.pAssertedIdentity());
        assertTrue(e.imsHeaders().containsKey(ImsSipHeaderNames.P_ACCESS_NETWORK_INFO));
        assertEquals("icid-value=xyz", e.pChargingVector());
        assertTrue(e.fromUri().startsWith("sip:"));
        assertTrue("fromUri must not be a full From header", !e.fromUri().contains("From:"));
        assertTrue("non-whitelist must not appear", !e.imsHeaders().containsKey("X-Ignored"));
    }
}
