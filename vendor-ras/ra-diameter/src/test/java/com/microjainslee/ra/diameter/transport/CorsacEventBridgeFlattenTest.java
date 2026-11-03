package com.microjainslee.ra.diameter.transport;

import org.jdiameter.api.AvpSet;
import org.jdiameter.client.api.IMessage;
import org.jdiameter.client.impl.parser.MessageParser;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** Defined AVPs (Experimental-Result, Server-Name, SIP-Auth-Data-Item) reach the event map. */
public class CorsacEventBridgeFlattenTest {

    @Test
    public void uaaAndMaaAvpsAreFlattened() {
        IMessage uaa = new MessageParser().createEmptyMessage(300, 16_777_216L);
        uaa.setRequest(false);
        AvpSet avps = uaa.getAvps();
        avps.addAvp(263, "cx-1", false);                               // Session-Id
        AvpSet exp = avps.addGroupedAvp(297, true, false);              // Experimental-Result
        exp.addAvp(266, 10415L, true);                                  //   Vendor-Id = 3GPP
        exp.addAvp(298, 2001L, true);                                   //   DIAMETER_FIRST_REGISTRATION
        avps.addAvp(602, "sip:scscf:5060", 10415L, true, false, false); // Server-Name
        AvpSet item = avps.addGroupedAvp(612, 10415L, true, false);     // SIP-Auth-Data-Item
        item.addAvp(608, "Digest-AKAv1-MD5", 10415L, true, false, false);
        item.addAvp(609, "0011aabb", 10415L, true, false, false);
        item.addAvp(625, new byte[] {(byte) 0xca, (byte) 0xfe, 0x00, 0x01}, 10415L, true, false); // CK

        Map<Integer, String> out = new LinkedHashMap<>();
        out.put(263, "kept");
        CorsacEventBridge.flatten(uaa.getAvps(), out);

        assertEquals("existing values are kept", "kept", out.get(263));
        assertEquals("2001", out.get(298));
        assertEquals("10415", out.get(266));
        assertEquals("sip:scscf:5060", out.get(602));
        assertEquals("Digest-AKAv1-MD5", out.get(608));
        assertEquals("0011aabb", out.get(609));
        assertEquals("binary as hex", "cafe0001", out.get(625));
    }

    @Test
    public void numbersAndTextRender() {
        assertEquals("2002", CorsacEventBridge.render(298, new byte[] {0, 0, 0x07, (byte) 0xd2}));
        assertEquals("4294967295", CorsacEventBridge.render(268, new byte[] {-1, -1, -1, -1}));
        assertEquals("ims.lab", CorsacEventBridge.render(296, "ims.lab".getBytes()));
        assertEquals("00ff", CorsacEventBridge.render(999, new byte[] {0, (byte) 0xff}));
    }
}
