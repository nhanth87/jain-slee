package com.microjainslee.ra.gtpv2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * W3-a — IE 78 (PCO) codec per TS 24.008 §10.5.6.11: message-level round trip
 * through the full Gtpv2Codec, container parse, and Bearer-Context nesting.
 */
class Gtpv2PcoTest {

    @Test
    void pcoRoundTripsThroughFullMessageCodec() throws Exception {
        InetAddress pcscf = InetAddress.getByName("10.10.0.53");
        InetAddress dns = InetAddress.getByName("10.10.0.54");
        Gtpv2Message in = new Gtpv2Message(
                Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                0x11223344, 5, (byte) 0,
                List.of(
                        Gtpv2Ies.causeAccepted(),
                        Gtpv2Ies.pco(
                                Gtpv2Ies.ipv4Container(Gtpv2Ies.PCO_P_CSCF_IPV4, pcscf),
                                Gtpv2Ies.ipv4Container(Gtpv2Ies.PCO_DNS_SERVER_IPV4, dns))));

        byte[] wire = Gtpv2Codec.encode(in);
        Gtpv2Message out = Gtpv2Codec.decode(wire);

        List<Gtpv2Ies.PcoContainer> pco = Gtpv2Ies.pcoFrom(out);
        assertEquals(2, pco.size());
        assertEquals(pcscf, Gtpv2Ies.pcoIpv4(pco, Gtpv2Ies.PCO_P_CSCF_IPV4));
        assertEquals(dns, Gtpv2Ies.pcoIpv4(pco, Gtpv2Ies.PCO_DNS_SERVER_IPV4));
    }

    @Test
    void pcoValueLayoutMatchesTs24008() {
        byte[] v = Gtpv2Ies.encodePcoValue(List.of(
                new Gtpv2Ies.PcoContainer(Gtpv2Ies.PCO_P_CSCF_IPV4, new byte[]{10, 0, 0, 1})));
        assertEquals((byte) 0x80, v[0], "configuration protocol = PPP");
        assertEquals(7, v[1] & 0xff, "contents length = id(2)+len(1)+data(4)");
        assertEquals(0x00, v[2]);
        assertEquals(0x0C, v[3]);
        assertEquals(4, v[4] & 0xff);
        // decode is tolerant to unknown ids
        List<Gtpv2Ies.PcoContainer> back = Gtpv2Ies.decodePcoValue(v);
        assertEquals(1, back.size());
        assertEquals(Gtpv2Ies.PCO_P_CSCF_IPV4, back.getFirst().id());
    }

    @Test
    void requestedPcoFromCreateSessionRequestDecodes() {
        // UE asks for P-CSCF IPv4 + DNS IPv4 (empty data = request)
        Gtpv2Message req = new Gtpv2Message(
                Gtpv2MessageType.CREATE_SESSION_REQUEST,
                0xdeadbeef, 9, (byte) 0,
                List.of(Gtpv2Ies.pco(
                        new Gtpv2Ies.PcoContainer(Gtpv2Ies.PCO_P_CSCF_IPV4, new byte[0]),
                        new Gtpv2Ies.PcoContainer(Gtpv2Ies.PCO_DNS_SERVER_IPV4, new byte[0]))));
        List<Gtpv2Ies.PcoContainer> asked = Gtpv2Ies.pcoFrom(req);
        assertTrue(asked.stream()
                .anyMatch(c -> c.id() == Gtpv2Ies.PCO_P_CSCF_IPV4 && c.data().length == 0));
    }

    @Test
    void pcoNestsInsideBearerContextGroupedIe() throws Exception {
        InetAddress pcscf = InetAddress.getByName("172.31.255.10");
        GtpFteid s1u = new GtpFteid(0x77aa, InetAddress.getByName("172.18.0.30"), 1);
        byte[] grouped = Gtpv2Ies.encodeBearerContext(
                (byte) 5, s1u, Gtpv2Ies.pco(
                        Gtpv2Ies.ipv4Container(Gtpv2Ies.PCO_P_CSCF_IPV4, pcscf)));

        Gtpv2Message msg = new Gtpv2Message(
                Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                1, 1, (byte) 0,
                List.of(new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, grouped)));

        List<Gtpv2Ie> inner = Gtpv2Ies.decodeGrouped(msg.first(Gtpv2Ie.BEARER_CONTEXT).value());
        Gtpv2Ie nested = inner.stream()
                .filter(i -> i.type() == Gtpv2Ie.PCO).findFirst().orElse(null);
        assertNotNull(nested, "bearer-level PCO must survive grouped encode/decode");
        assertEquals(pcscf, Gtpv2Ies.pcoIpv4(Gtpv2Ies.decodePcoValue(nested.value()),
                Gtpv2Ies.PCO_P_CSCF_IPV4));
    }
}
