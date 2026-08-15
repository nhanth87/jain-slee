package com.microjainslee.ra.gtpv2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.gtpv2c.command.GtpEchoCommand;
import com.microjainslee.ra.gtpv2c.event.GtpEchoEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class Gtpv2CodecTest {

    @Test
    void roundTripEcho() {
        Gtpv2Message echo = Gtpv2Message.echoRequest(7, (byte) 3);
        Gtpv2Message back = Gtpv2Codec.decode(Gtpv2Codec.encode(echo));
        assertEquals(Gtpv2MessageType.ECHO_REQUEST, back.type());
        assertEquals(7, back.sequence());
        assertEquals(3, back.recovery());
        assertEquals(0x40, Gtpv2Codec.encode(echo)[0] & 0xff);
        assertEquals(0, back.teid());
    }

    @Test
    void decodeMmeShapedEchoWithoutTeid() {
        byte[] wire = {
                0x40, 0x01, 0x00, 0x09,
                0x00, 0x00, 0x07, 0x00,
                0x03, 0x00, 0x01, 0x00, 0x03
        };
        Gtpv2Message msg = Gtpv2Codec.decode(wire);
        assertEquals(Gtpv2MessageType.ECHO_REQUEST, msg.type());
        assertEquals(7, msg.sequence());
        assertEquals(3, msg.recovery());
        assertEquals(0, msg.teid());
    }

    @Test
    void createSessionKeepsTeidFlag() {
        Gtpv2Message csr = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 42, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.IMSI, 0, new byte[] {0x01})));
        byte[] wire = Gtpv2Codec.encode(csr);
        assertEquals(0x48, wire[0] & 0xff);
        assertEquals(Gtpv2MessageType.CREATE_SESSION_REQUEST, Gtpv2Codec.decode(wire).type());
    }

    @Test
    void unknownTypeIsNotThrown() {
        assertEquals(Gtpv2MessageType.UNKNOWN, Gtpv2MessageType.of(250));
        byte[] wire = {
                0x48, (byte) 250, 0x00, 0x08,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x01, 0x00
        };
        assertEquals(Gtpv2MessageType.UNKNOWN, Gtpv2Codec.decode(wire).type());
    }

    @Test
    void versionNotSupportedOnNonV2() {
        Gtpv2RaEndpoint ra = new Gtpv2RaEndpoint((byte) 1);
        List<byte[]> sent = new ArrayList<>();
        ra.setSink((bytes, peer) -> sent.add(bytes));
        ra.activate(recording(new ArrayList<>()));
        byte[] gtpv1 = {0x32, 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00};
        ra.receive(gtpv1, new InetSocketAddress("192.0.2.14", 2123));
        assertEquals(1, sent.size());
        Gtpv2Message rsp = Gtpv2Codec.decode(sent.get(0));
        assertEquals(Gtpv2MessageType.VERSION_NOT_SUPPORTED, rsp.type());
        assertEquals(0x40, sent.get(0)[0] & 0xff);
    }

    @Test
    void echoRequestWithoutTeidIsAnsweredInRa() {
        Gtpv2RaEndpoint ra = new Gtpv2RaEndpoint((byte) 5);
        List<byte[]> sent = new ArrayList<>();
        ra.setSink((bytes, peer) -> sent.add(bytes));
        ra.activate(recording(new ArrayList<>()));
        byte[] echoT0 = {
                0x40, 0x01, 0x00, 0x09,
                0x00, 0x00, 0x07, 0x00,
                0x03, 0x00, 0x01, 0x00, 0x03
        };
        ra.receive(echoT0, new InetSocketAddress("192.0.2.15", 2123));
        assertEquals(1, sent.size());
        Gtpv2Message rsp = Gtpv2Codec.decode(sent.get(0));
        assertEquals(Gtpv2MessageType.ECHO_RESPONSE, rsp.type());
        assertEquals(7, rsp.sequence());
        assertEquals(5, rsp.recovery());
        assertEquals(0x40, sent.get(0)[0] & 0xff);
    }

    @Test
    void listenIsNotLiveUntilEchoResponse() {
        Gtpv2RaEndpoint ra = new Gtpv2RaEndpoint((byte) 1);
        List<SleeEvent> events = new ArrayList<>();
        ra.activate(recording(events));
        InetSocketAddress peer = new InetSocketAddress("192.0.2.10", 2123);
        assertTrue(ra.listening());
        assertFalse(ra.peer(peer).live());
        ra.receive(Gtpv2Codec.encode(Gtpv2Message.echoResponse(1, (byte) 9)), peer);
        assertTrue(ra.peer(peer).live());
        assertTrue(events.stream().anyMatch(GtpEchoEvent.class::isInstance));
    }

    @Test
    void duplicateSequenceSuppressed() {
        Gtpv2RaEndpoint ra = new Gtpv2RaEndpoint((byte) 1);
        List<SleeEvent> events = new ArrayList<>();
        ra.activate(recording(events));
        InetSocketAddress peer = new InetSocketAddress("192.0.2.11", 2123);
        Gtpv2Message csr = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 42, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.IMSI, 0, new byte[] {0x01})));
        byte[] wire = Gtpv2Codec.encode(csr);
        ra.receive(wire, peer);
        ra.receive(wire, peer);
        long count = events.stream().filter(e -> e instanceof com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent).count();
        assertEquals(1, count);
    }

    @Test
    void echoCommandWritesWire() {
        Gtpv2RaEndpoint ra = new Gtpv2RaEndpoint((byte) 4);
        AtomicReference<byte[]> sent = new AtomicReference<>();
        ra.setSink((bytes, peer) -> sent.set(bytes));
        ra.activate(recording(new ArrayList<>()));
        ra.sendCommand(new GtpEchoCommand(new InetSocketAddress("192.0.2.12", 2123)));
        Gtpv2Message msg = Gtpv2Codec.decode(sent.get());
        assertEquals(Gtpv2MessageType.ECHO_REQUEST, msg.type());
        assertEquals(4, msg.recovery());
        assertEquals(0x40, sent.get()[0] & 0xff);
    }

    @Test
    void retransmitReplaysCachedResponseNotSbb() {
        Gtpv2RaEndpoint ra = new Gtpv2RaEndpoint((byte) 1);
        List<SleeEvent> events = new ArrayList<>();
        List<byte[]> sent = new ArrayList<>();
        ra.setSink((bytes, peer) -> sent.add(bytes));
        ra.activate(recording(events));
        InetSocketAddress peer = new InetSocketAddress("192.0.2.13", 2123);
        Gtpv2Message csr = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 9, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd("001010000000001"))));
        byte[] req = Gtpv2Codec.encode(csr);
        ra.receive(req, peer);
        Gtpv2Message rsp = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, 0, 9, (byte) 1,
                List.of(Gtpv2Ies.causeAccepted()));
        ra.sendCommand(new com.microjainslee.ra.gtpv2c.command.SendGtpv2Message(rsp, peer));
        ra.receive(req, peer);
        assertEquals(1, events.stream().filter(e -> e instanceof com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent).count());
        assertEquals(2, sent.size());
        assertEquals(Gtpv2MessageType.CREATE_SESSION_RESPONSE, Gtpv2Codec.decode(sent.get(1)).type());
    }

    @Test
    void tbcdAndApnRoundTrip() {
        String imsi = "001010000000001";
        assertEquals(imsi, Gtpv2Ies.tbcdToDigits(Gtpv2Ies.tbcd(imsi)));
        assertEquals("internet", Gtpv2Ies.decodeApn(Gtpv2Ies.encodeApn("internet")));
    }

    @Test
    void groupedBearerContextRoundTrip() throws Exception {
        GtpFteid s1u = new GtpFteid(0xabc, InetAddress.getByName("192.0.2.8"), 0);
        byte[] grouped = Gtpv2Ies.encodeBearerContext((byte) 5, s1u, Gtpv2Ies.causeAccepted());
        Gtpv2Message msg = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, 0, 1, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, grouped)));
        Gtpv2Message back = Gtpv2Codec.decode(Gtpv2Codec.encode(msg));
        List<Gtpv2Ie> inner = Gtpv2Ies.decodeGrouped(back.first(Gtpv2Ie.BEARER_CONTEXT).value());
        assertEquals(Gtpv2Ie.EBI, inner.get(0).type());
        assertEquals(5, inner.get(0).value()[0] & 0xff);
        GtpFteid fromMsg = Gtpv2Ies.s1uFromBearerContext(back);
        assertEquals(s1u.teid(), fromMsg.teid());
        assertEquals(s1u.address(), fromMsg.address());
    }

    @Test
    void legacyStuffedFteidInBearerContextStillParsed() throws Exception {
        GtpFteid s1u = new GtpFteid(0x1111, InetAddress.getByName("192.0.2.50"), 0);
        Gtpv2Message msg = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, 0, 1, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, Gtpv2Ies.encodeFteid(s1u))));
        List<Gtpv2Ie> inner = Gtpv2Ies.decodeGrouped(msg.first(Gtpv2Ie.BEARER_CONTEXT).value());
        assertTrue(inner.stream().noneMatch(i -> i.type() == Gtpv2Ie.EBI));
        GtpFteid decoded = Gtpv2Ies.s1uFromBearerContext(msg);
        assertEquals(s1u.teid(), decoded.teid());
        assertEquals(s1u.address(), decoded.address());
    }

    private static RaBootstrapPort recording(List<SleeEvent> events) {
        return new RaBootstrapPort() {
            @Override
            public ActivityHandle createActivityHandle(String id) {
                return () -> id;
            }

            @Override
            public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                events.add(event);
            }
        };
    }
}
