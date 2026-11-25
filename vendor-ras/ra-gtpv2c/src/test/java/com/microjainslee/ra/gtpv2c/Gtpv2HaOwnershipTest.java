package com.microjainslee.ra.gtpv2c;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Session ownership (ADR 0002) on GTP-C: the accepted Create Session Response opens the session
 * under our Sender F-TEID TEID, the Delete Session Response closes it; the restart counter is
 * the configured cluster value, not a per-JVM constant.
 */
class Gtpv2HaOwnershipTest {

    private static final InetSocketAddress MME = new InetSocketAddress("192.0.2.1", 2123);
    private Gtpv2RaEndpoint ra;

    @AfterEach
    void tearDown() {
        if (ra != null) {
            ra.deactivate();
        }
    }

    @Test
    void createOpensAndDeleteClosesTheSession() throws Exception {
        ra = new Gtpv2RaEndpoint();
        ra.setRecovery((byte) 42);
        ra.activate(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return () -> id; }
            @Override public void fireEvent(SleeEvent e, ActivityHandle h, Address a) { }
        });
        assertEquals(42, ra.recovery());
        int sgw = 0x0101_0001;
        Gtpv2Message csResp = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, 0xa001, 1, (byte) 42,
                List.of(Gtpv2Ies.cause(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED), new Gtpv2Ie(Gtpv2Ie.FTEID, 0,
                        Gtpv2Ies.encodeFteid(new GtpFteid(sgw, InetAddress.getByName("192.0.2.14"), 11)))));
        ra.sendCommand(new SendGtpv2Message(csResp, MME));
        String key = Gtpv2RaEndpoint.sessionKey(sgw);
        assertTrue(ra.haSupport().isLocalOwner(key));

        ra.receive(Gtpv2Codec.encode(new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_REQUEST, sgw, 3, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.EBI, 0, new byte[] {5})))), MME);
        ra.sendCommand(new SendGtpv2Message(new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_RESPONSE, 0xa001, 3,
                (byte) 42, List.of(Gtpv2Ies.cause(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED))), MME));
        assertFalse(ra.haSupport().lookupOwner(key).isPresent());
    }

    @Test
    void rejectedCreateOpensNothing() throws Exception {
        ra = new Gtpv2RaEndpoint();
        ra.activate(new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return () -> id; }
            @Override public void fireEvent(SleeEvent e, ActivityHandle h, Address a) { }
        });
        Gtpv2Message reject = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, 0xa001, 1, (byte) 1,
                List.of(Gtpv2Ies.cause(73), new Gtpv2Ie(Gtpv2Ie.FTEID, 0,
                        Gtpv2Ies.encodeFteid(new GtpFteid(7, InetAddress.getByName("192.0.2.14"), 11)))));
        ra.sendCommand(new SendGtpv2Message(reject, MME));
        assertFalse(ra.haSupport().lookupOwner(Gtpv2RaEndpoint.sessionKey(7)).isPresent());
    }
}
