package com.microjainslee.ra.gtpv2c;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.gtpv2c.command.GtpEchoCommand;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.gtpv2c.event.GtpEchoEvent;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Gtpv2T3N3Test {

    private static final long T3_MS = 40L;
    private Gtpv2RaEndpoint ra;

    @AfterEach
    void tearDown() {
        if (ra != null) {
            ra.deactivate();
        }
    }

    @Test
    void defaultsAreThreeSecondsAndThreeRetries() {
        ra = new Gtpv2RaEndpoint((byte) 1);
        assertEquals(3_000L, ra.t3Millis());
        assertEquals(3, ra.n3());
        assertEquals(Gtpv2RaEndpoint.DEFAULT_T3_MS, ra.t3Millis());
        assertEquals(Gtpv2RaEndpoint.DEFAULT_N3, ra.n3());
    }

    @Test
    void echoRetransmitsOnceWithoutResponse() throws Exception {
        ra = new Gtpv2RaEndpoint((byte) 1, T3_MS, 3);
        CountDownLatch twoSends = new CountDownLatch(2);
        List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
        ra.setSink((bytes, peer) -> {
            sent.add(bytes);
            twoSends.countDown();
        });
        ra.activate(recording(new ArrayList<>()));
        InetSocketAddress peer = new InetSocketAddress("192.0.2.20", 2123);
        ra.sendCommand(new GtpEchoCommand(peer));
        assertTrue(twoSends.await(2, TimeUnit.SECONDS), "T3 should resend Echo Request");
        assertEquals(Gtpv2MessageType.ECHO_REQUEST, Gtpv2Codec.decode(sent.get(0)).type());
        assertEquals(Gtpv2Codec.decode(sent.get(0)).sequence(), Gtpv2Codec.decode(sent.get(1)).sequence());
        assertArrayEquals(sent.get(0), sent.get(1));
        assertTrue(ra.outstandingCount() >= 1);
        assertFalse(ra.peer(peer).live());
    }

    @Test
    void echoStopsOnMatchingResponse() throws Exception {
        ra = new Gtpv2RaEndpoint((byte) 1, T3_MS, 3);
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch extra = new CountDownLatch(1);
        List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
        ra.setSink((bytes, peer) -> {
            sent.add(bytes);
            if (sends.incrementAndGet() > 1) {
                extra.countDown();
            }
        });
        List<SleeEvent> events = new ArrayList<>();
        ra.activate(recording(events));
        InetSocketAddress peer = new InetSocketAddress("192.0.2.21", 2123);
        ra.sendCommand(new GtpEchoCommand(peer));
        assertEquals(1, sent.size());
        Gtpv2Message req = Gtpv2Codec.decode(sent.get(0));
        ra.receive(Gtpv2Codec.encode(Gtpv2Message.echoResponse(req.sequence(), (byte) 9)), peer);
        assertEquals(0, ra.outstandingCount());
        assertTrue(ra.peer(peer).live());
        assertTrue(events.stream().anyMatch(GtpEchoEvent.class::isInstance));
        assertFalse(extra.await(T3_MS * 3, TimeUnit.MILLISECONDS), "must not retransmit after Echo Response");
        assertEquals(1, sends.get());
    }

    @Test
    void createSessionResponseIsNotRetransmitted() throws Exception {
        ra = new Gtpv2RaEndpoint((byte) 1, T3_MS, 3);
        AtomicInteger sends = new AtomicInteger();
        CountDownLatch extra = new CountDownLatch(1);
        ra.setSink((bytes, peer) -> {
            if (sends.incrementAndGet() > 1) {
                extra.countDown();
            }
        });
        ra.activate(recording(new ArrayList<>()));
        InetSocketAddress peer = new InetSocketAddress("192.0.2.22", 2123);
        Gtpv2Message rsp = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, 0, 9, (byte) 1,
                List.of(Gtpv2Ies.causeAccepted()));
        ra.sendCommand(new SendGtpv2Message(rsp, peer));
        assertEquals(0, ra.outstandingCount());
        assertEquals(1, sends.get());
        assertFalse(extra.await(T3_MS * 3, TimeUnit.MILLISECONDS));
        assertEquals(1, sends.get());
    }

    @Test
    void echoN3ExhaustedMarksPeerDown() throws Exception {
        ra = new Gtpv2RaEndpoint((byte) 1, T3_MS, 1);
        CountDownLatch twoSends = new CountDownLatch(2);
        ra.setSink((bytes, peer) -> twoSends.countDown());
        ra.activate(recording(new ArrayList<>()));
        InetSocketAddress peer = new InetSocketAddress("192.0.2.23", 2123);
        ra.sendCommand(new GtpEchoCommand(peer));
        assertTrue(twoSends.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ra.outstandingCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, ra.outstandingCount());
        assertFalse(ra.peer(peer).live());
        assertTrue(ra.peer(peer).evidence().contains("t3-n3-exhausted"));
    }

    @Test
    void procedureRequestStopsOnMatchingResponse() {
        ra = new Gtpv2RaEndpoint((byte) 1, T3_MS, 3);
        List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
        ra.setSink((bytes, peer) -> sent.add(bytes));
        ra.activate(recording(new ArrayList<>()));
        InetSocketAddress peer = new InetSocketAddress("192.0.2.24", 2123);
        Gtpv2Message csr = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 42, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.IMSI, 0, new byte[] {0x01})));
        ra.sendCommand(new SendGtpv2Message(csr, peer));
        assertEquals(1, ra.outstandingCount());
        Gtpv2Message csrsp = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, 0, 42, (byte) 1,
                List.of(Gtpv2Ies.causeAccepted()));
        ra.receive(Gtpv2Codec.encode(csrsp), peer);
        assertEquals(0, ra.outstandingCount());
        assertEquals(1, sent.size());
        assertEquals(Gtpv2MessageType.CREATE_SESSION_REQUEST, Gtpv2Codec.decode(sent.get(0)).type());
    }

    @Test
    void requestTypesAreNotEvenOdd() {
        assertTrue(Gtpv2MessageType.ECHO_REQUEST.isRequest());
        assertFalse(Gtpv2MessageType.ECHO_RESPONSE.isRequest());
        assertTrue(Gtpv2MessageType.ECHO_RESPONSE.isResponse());
        assertTrue(Gtpv2MessageType.CREATE_SESSION_REQUEST.isRequest());
        assertTrue(Gtpv2MessageType.CREATE_SESSION_RESPONSE.isResponse());
        assertTrue(Gtpv2MessageType.CREATE_BEARER_REQUEST.isRequest());
        assertTrue(Gtpv2MessageType.CREATE_BEARER_RESPONSE.isResponse());
        assertFalse(Gtpv2MessageType.CREATE_BEARER_REQUEST.isResponse());
        assertFalse(Gtpv2MessageType.CREATE_SESSION_RESPONSE.isRequest());
        assertTrue(Gtpv2MessageType.RELEASE_ACCESS_BEARERS_REQUEST.isRequest());
        assertTrue(Gtpv2MessageType.DOWNLINK_DATA_NOTIFICATION.isRequest());
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
