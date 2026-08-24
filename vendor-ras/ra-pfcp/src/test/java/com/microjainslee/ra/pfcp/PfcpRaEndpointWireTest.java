package com.microjainslee.ra.pfcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import com.microjainslee.ra.pfcp.command.SendPfcpMessage;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The actual PFCP rule payload must cross the wire on send and decode on receive. */
class PfcpRaEndpointWireTest {

    private static final InetSocketAddress UPF = new InetSocketAddress("192.0.2.20", 8805);

    private static PfcpRule rule() throws Exception {
        return new PfcpRule(9L, "pdr",
                new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 0),
                new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 0),
                "qer-1", true);
    }

    @Test
    void sendCommandEncodesRealRulesNotCount() throws Exception {
        List<byte[]> wire = new ArrayList<>();
        PfcpRaEndpoint ra = new PfcpRaEndpoint();
        ra.setSink((bytes, peer) -> wire.add(bytes));

        PfcpRule r = rule();
        ra.sendCommand(new PfcpProgramSession(UPF, 9L, List.of(r),
                InetAddress.getByName("10.64.0.2")));

        assertEquals(1, wire.size());
        PfcpMessage msg = PfcpCodec.decode(wire.get(0));
        assertEquals(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, msg.type());
        PfcpRules decoded = PfcpCodec.decodeRules(msg.payload());
        assertEquals(List.of(r), decoded.rules());
        assertEquals("10.64.0.2", decoded.ueIpv4().getHostAddress());
    }

    @Test
    void receiveDecodesSessionRequestRulesIntoEvent() throws Exception {
        List<SleeEvent> events = new ArrayList<>();
        PfcpRaEndpoint ra = new PfcpRaEndpoint();
        ra.activate(recording(events));

        PfcpRule r = rule();
        byte[] payload = PfcpCodec.encodeRules(List.of(r), InetAddress.getByName("10.64.0.2"));
        PfcpMessage req = new PfcpMessage(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 9L, 5, payload);
        ra.receive(PfcpCodec.encode(req), UPF);

        assertEquals(1, events.size());
        PfcpMessageEvent evt = assertInstanceOf(PfcpMessageEvent.class, events.get(0));
        assertEquals(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, evt.message().type());
        PfcpRules decoded = PfcpCodec.decodeRules(evt.message().payload());
        assertEquals(List.of(r), decoded.rules());
    }

    @Test
    void sessionReportRequestDispatchesAsMessageEvent() throws Exception {
        List<SleeEvent> events = new ArrayList<>();
        PfcpRaEndpoint ra = new PfcpRaEndpoint();
        ra.activate(recording(events));

        List<PfcpUsageReport> reports = List.of(
                new PfcpUsageReport(1, PfcpIe.UT_VOLTH, 100L, 200L, 30));
        List<PfcpIe> ies = new ArrayList<>(
                PfcpCodec.decodeIes(PfcpCodec.encodeUsageReports(reports)));
        ies.add(PfcpIes.queryUrr(1));
        ra.receive(PfcpCodec.encode(PfcpMessage.sessionReportRequest(9L, 5, ies)), UPF);

        assertEquals(1, events.size());
        PfcpMessageEvent evt = assertInstanceOf(PfcpMessageEvent.class, events.get(0));
        assertEquals(PfcpMessageType.SESSION_REPORT_REQUEST, evt.message().type());
        assertTrue(evt.message().type().isRequest());
        assertEquals(reports, PfcpCodec.decodeUsageReports(evt.message().payload()));
        assertTrue(PfcpCodec.hasQueryUrr(evt.message().payload()));
    }

    @Test
    void sendPfcpMessageCarriesSessionReportResponse() {
        List<byte[]> wire = new ArrayList<>();
        PfcpRaEndpoint ra = new PfcpRaEndpoint();
        ra.setSink((bytes, peer) -> wire.add(bytes));

        PfcpMessage resp = PfcpMessage.sessionReportResponse(9L, 5,
                List.of(PfcpIes.cause(PfcpIe.CAUSE_REQUEST_ACCEPTED)));
        ra.sendCommand(new SendPfcpMessage(resp, UPF));

        assertEquals(1, wire.size());
        PfcpMessage back = PfcpCodec.decode(wire.get(0));
        assertEquals(PfcpMessageType.SESSION_REPORT_RESPONSE, back.type());
        assertTrue(back.type().isResponse());
        assertEquals(PfcpIe.CAUSE_REQUEST_ACCEPTED,
                PfcpIes.find(back.ies(), PfcpIe.CAUSE).value()[0] & 0xff);
    }

    private static RaBootstrapPort recording(List<SleeEvent> events) {
        return new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) {
                return () -> id;
            }

            @Override public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                events.add(event);
            }
        };
    }
}