package com.microjainslee.ra.gtpv2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.gtpv2c.command.GtpEchoCommand;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Seen on the S5-C wire: the SGW-C's Echo Request and its first Create Session Request both
 * carried sequence 1; the PGW-C replayed its cached Echo Response for the Create Session Request
 * and the procedure never reached its SBB.
 */
class Gtpv2TransactionKeyTest {

    private static final InetSocketAddress SGWC = new InetSocketAddress("192.0.2.14", 2123);
    private Gtpv2RaEndpoint ra;

    @AfterEach
    void tearDown() {
        if (ra != null) {
            ra.deactivate();
        }
    }

    @Test
    void anEchoAndAProcedureOnTheSameSequenceAreTwoTransactions() {
        ra = new Gtpv2RaEndpoint((byte) 1);
        List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
        List<SleeEvent> fired = Collections.synchronizedList(new ArrayList<>());
        ra.setSink((bytes, peer) -> sent.add(bytes));
        ra.activate(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return () -> id; }
            @Override public void fireEvent(SleeEvent e, ActivityHandle h, Address a) { fired.add(e); }
        });

        ra.receive(Gtpv2Codec.encode(Gtpv2Message.echoRequest(1, (byte) 1)), SGWC);
        assertEquals(Gtpv2MessageType.ECHO_RESPONSE, Gtpv2Codec.decode(sent.get(0)).type());

        Gtpv2Message csr = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 1, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd("001010000000009"))));
        ra.receive(Gtpv2Codec.encode(csr), SGWC);
        assertEquals(1, sent.size(), "no Echo Response replayed for the Create Session Request");
        assertEquals(1, fired.size());
        assertTrue(fired.get(0) instanceof Gtpv2MessageEvent m
                && m.message().type() == Gtpv2MessageType.CREATE_SESSION_REQUEST);
    }

    @Test
    void echoSequencesStayOutOfTheProcedureRange() {
        ra = new Gtpv2RaEndpoint((byte) 1);
        List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
        ra.setSink((bytes, peer) -> sent.add(bytes));
        ra.sendCommand(new GtpEchoCommand(SGWC));
        int seq = Gtpv2Codec.decode(sent.get(0)).sequence();
        assertTrue(seq >= Gtpv2RaEndpoint.ECHO_SEQUENCE_BASE, "echo seq 0x" + Integer.toHexString(seq));
    }

    /** An unanswered procedure ends as an event, so the SBB that started it can answer its own peer. */
    @Test
    void anUnansweredRequestFiresAProcedureTimeout() throws Exception {
        ra = new Gtpv2RaEndpoint((byte) 1, 20L, 1);
        List<SleeEvent> fired = Collections.synchronizedList(new ArrayList<>());
        ra.activate(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return () -> id; }
            @Override public void fireEvent(SleeEvent e, ActivityHandle h, Address a) { fired.add(e); }
        });
        Gtpv2Message csr = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 7, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd("001010000000009"))));
        ra.sendCommand(new com.microjainslee.ra.gtpv2c.command.SendGtpv2Message(csr, SGWC));
        long deadline = System.currentTimeMillis() + 2_000;
        while (fired.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, fired.size());
        var timeout = (com.microjainslee.ra.gtpv2c.event.GtpProcedureTimeoutEvent) fired.get(0);
        assertEquals(Gtpv2MessageType.CREATE_SESSION_REQUEST, timeout.type());
        assertEquals(7, timeout.sequence());
        assertEquals(SGWC, timeout.peer());
    }
}
