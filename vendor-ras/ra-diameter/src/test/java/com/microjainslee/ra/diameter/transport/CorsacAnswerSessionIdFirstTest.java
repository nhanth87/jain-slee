/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.transport;

import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import org.jdiameter.api.Avp;
import org.jdiameter.api.Message;
import org.jdiameter.client.api.IMessage;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Seen against the open5gs MME (freeDiameter): "the FIXED_HEAD AVP appears first in (2) position,
 * the rule requires (1) for 'Session-Id'" — every S6a answer was rejected as a bad message.
 * RFC 6733 §8.8: Session-Id leads the answer, before Result-Code.
 */
public class CorsacAnswerSessionIdFirstTest {

    @Test
    public void sessionIdIsTheFirstAvpOfAnAnswer() {
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setOriginHost("epc-hss-1.epc.lab");
        cfg.setRealm("epc.lab");
        CorsacDiameterTransport t = new CorsacDiameterTransport(cfg, new DiameterTransportCallbacks() {
            @Override public void onPeerConnected(String peerId) { }
            @Override public void onPeerDisconnected(String peerId) { }
            @Override public void onMessage(String peerId, Message msg, MessageReplyWriter w) { }
        });
        IMessage msg = t.toJdiameter(new SendDiameterAnswer("mme.epc.lab;1;app_s6a", 16_777_251L, 318, 2001L,
                1L, 2L, Map.of(), List.of(DiameterAvp.utf8(1, "001010000000001"))));
        Avp first = msg.getAvps().iterator().next();
        assertEquals(Avp.SESSION_ID, first.getCode());
        // RFC 6733 §6.2: Origin-Host / Origin-Realm are mandatory in every answer (freeDiameter:
        // "number of occurences (0) is < the rule min (1) for 'Origin-Host'").
        assertEquals("epc-hss-1.epc.lab", utf8(msg.getAvps().getAvp(Avp.ORIGIN_HOST)));
        assertEquals("epc.lab", utf8(msg.getAvps().getAvp(Avp.ORIGIN_REALM)));
    }

    private static String utf8(Avp a) {
        try {
            return a.getUTF8String();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
