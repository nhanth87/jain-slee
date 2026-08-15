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
