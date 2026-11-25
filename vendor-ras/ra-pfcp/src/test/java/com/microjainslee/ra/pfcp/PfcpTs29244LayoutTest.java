package com.microjainslee.ra.pfcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import com.microjainslee.ra.pfcp.command.PfcpSessionAnswer;
import com.microjainslee.ra.pfcp.command.PfcpSessionDeletion;
import com.microjainslee.ra.pfcp.command.PfcpSessionModification;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Byte layouts against TS 29.244 written by hand (not by the codec under test), plus one
 * session lifecycle between two RAs. Every encoded message is also written as a text2pcap
 * hex dump to {@code target/pfcp-ts29244.txt} so it can be checked with Wireshark:
 * {@code text2pcap -u 8805,8805 target/pfcp-ts29244.txt x.pcap && tshark -r x.pcap -V}.
 */
class PfcpTs29244LayoutTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final InetSocketAddress UP = new InetSocketAddress("192.0.2.16", 8805);
    private static final InetSocketAddress CP = new InetSocketAddress("192.0.2.14", 8805);

    /** §7.2.2.1 node message: no SEID, header 8 octets; §7.4.2.1 Heartbeat Request = Recovery Time Stamp. */
    @Test
    void heartbeatRequestByHand() {
        byte[] expected = HEX.parseHex(
                "20" + "01" + "000c"          // v1, S=0 | type 1 | length 12 (= 4 + IE 8)
                + "000007" + "00"             // sequence 7 | spare
                + "0060" + "0004" + "e1b2c3d4"); // Recovery Time Stamp (96), len 4
        assertArrayEquals(expected, PfcpCodec.encode(PfcpMessage.heartbeatRequest(7, 0xe1b2c3d4)));
        PfcpMessage back = PfcpCodec.decode(expected);
        assertEquals(PfcpMessageType.HEARTBEAT_REQUEST, back.type());
        assertEquals(0xe1b2c3d4, back.recoveryTimeStamp());
    }

    /** §7.2.2.2 session message: S=1, 8-octet SEID, length counts SEID + sequence + IEs. */
    @Test
    void sessionHeaderByHand() {
        byte[] expected = HEX.parseHex(
                "21" + "36" + "0011"          // v1, S=1 | type 54 Deletion Req | length 17
                + "0102030405060708"          // SEID
                + "00002a" + "00"             // sequence 42 | spare
                + "0013" + "0001" + "01");    // Cause (19), len 1, accepted
        PfcpMessage msg = new PfcpMessage(PfcpMessageType.SESSION_DELETION_REQUEST, 0x0102030405060708L, 42,
                PfcpCodec.encodeIes(List.of(PfcpIes.cause(1))));
        assertArrayEquals(expected, PfcpCodec.encode(msg));
    }

    /** §8.2.38 Node ID FQDN = RFC 1035 labels; §8.2.37 F-SEID V4 = bit 2; §8.2.3 F-TEID V4 = bit 1. */
    @Test
    void identityIesByHand() throws Exception {
        assertArrayEquals(HEX.parseHex("02" + "03" + "757066" + "036c6162"),
                PfcpIes.nodeId(PfcpNodeId.of("upf.lab")).value());
        assertEquals("upf.lab", PfcpIes.parseNodeId(PfcpIes.nodeId(PfcpNodeId.of("upf.lab"))).fqdn());
        assertArrayEquals(HEX.parseHex("02" + "0000000000000009" + "c000020e"),
                PfcpIes.fSeid(9L, InetAddress.getByName("192.0.2.14")).value());
        assertArrayEquals(HEX.parseHex("01" + "0000a001" + "c0000210"),
                PfcpIes.fTeid(new PfcpFteid(0xa001, InetAddress.getByName("192.0.2.16"), 0)).value());
        // §8.2.62: V4 = bit 2, S/D = bit 3 (destination, downlink match).
        assertArrayEquals(HEX.parseHex("06" + "0a400002"),
                PfcpIes.ueIpAddress(InetAddress.getByName("10.64.0.2"), true).value());
        // §7.5.4.3 Update FAR {FAR ID 2, Apply Action FORW, Update Forwarding Parameters
        // {Destination Interface ACCESS, Outer Header Creation GTP-U/UDP/IPv4 TEID e001 @ 192.0.2.1}}.
        PfcpIe upd = PfcpIes.updateFar(2, PfcpIe.APPLY_FORW,
                new PfcpFteid(0xe001, InetAddress.getByName("192.0.2.1"), 0), PfcpIe.INTERFACE_ACCESS);
        assertArrayEquals(HEX.parseHex(
                "006c000400000002" + "002c000102"
                + "000b0013" + "002a000100" + "0054000a" + "0100" + "0000e001" + "c0000201"), upd.value());
    }

    /**
     * CP RA ↔ UP RA: Establishment (header SEID 0, CP F-SEID) → Response (UP F-SEID) →
     * Modification and Deletion carry the UP's SEID in the header (§7.2.2.4.2).
     */
    @Test
    void sessionLifecycleBetweenTwoRas() throws Exception {
        List<byte[]> dump = new ArrayList<>();
        PfcpRaEndpoint cp = new PfcpRaEndpoint();
        PfcpRaEndpoint up = new PfcpRaEndpoint();
        cp.setNodeId(PfcpNodeId.of(CP.getAddress()));
        up.setNodeId(PfcpNodeId.of(UP.getAddress()));
        List<PfcpMessage> atUp = new ArrayList<>();
        List<PfcpMessage> atCp = new ArrayList<>();
        cp.activate(sink(atCp));
        up.activate(sink(atUp));
        cp.setSink((b, p) -> { dump.add(b); up.receive(b, CP); });
        up.setSink((b, p) -> { dump.add(b); cp.receive(b, UP); });
        try {
            cp.sendCommand(new com.microjainslee.ra.pfcp.command.PfcpAssociateCommand(UP));
            cp.doSendHeartbeat(UP);

            PfcpRule ul = new PfcpRule(77L, "pdr-ul", new PfcpFteid(0x1001, UP.getAddress(), 1),
                    new PfcpFteid(0x2001, InetAddress.getByName("192.0.2.13"), 5), "qer-1", true,
                    new PfcpUrr(7, PfcpIe.MM_VOLUME, PfcpIe.UT_VOLTH, 1_000_000L, 0));
            cp.sendCommand(new PfcpProgramSession(UP, 77L, List.of(ul), InetAddress.getByName("10.64.0.2")));
            PfcpMessage est = atUp.getLast();
            assertEquals(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, est.type());
            assertEquals(0L, est.seid());
            assertEquals(77L, est.sessionSeid());
            PfcpRules rules = PfcpCodec.decodeRules(est.sessionSeid(), est.payload());
            assertEquals(1, rules.rules().size());
            assertEquals("10.64.0.2", rules.ueIpv4().getHostAddress());
            assertEquals(7, rules.rules().get(0).urr().urrId());
            assertEquals(1_000_000L, rules.rules().get(0).urr().volumeThresholdBytes());

            up.sendCommand(new PfcpSessionAnswer(CP, est, PfcpIe.CAUSE_REQUEST_ACCEPTED));
            PfcpMessage resp = atCp.getLast();
            assertEquals(PfcpMessageType.SESSION_ESTABLISHMENT_RESPONSE, resp.type());
            assertEquals(77L, resp.seid(), "response header = CP SEID");
            assertNotNull(resp.nodeId());
            assertEquals(1, resp.first(PfcpIe.CAUSE).value()[0]);
            assertNotNull(resp.first(PfcpIe.F_SEID), "UP F-SEID");

            cp.sendCommand(new PfcpSessionModification(UP, 77L, List.of(PfcpIes.updateFar(2, PfcpIe.APPLY_FORW,
                    new PfcpFteid(0xe001, InetAddress.getByName("192.0.2.1"), 0), PfcpIe.INTERFACE_ACCESS))));
            PfcpMessage mod = atUp.getLast();
            assertEquals(PfcpMessageType.SESSION_MODIFICATION_REQUEST, mod.type());
            assertEquals(77L, mod.seid());
            up.sendCommand(new PfcpSessionAnswer(CP, mod, PfcpIe.CAUSE_REQUEST_ACCEPTED));

            cp.sendCommand(new PfcpSessionDeletion(UP, 77L, List.of()));
            PfcpMessage del = atUp.getLast();
            assertEquals(PfcpMessageType.SESSION_DELETION_REQUEST, del.type());
            up.sendCommand(new PfcpSessionAnswer(CP, del, PfcpIe.CAUSE_REQUEST_ACCEPTED,
                    PfcpCodec.decodeIes(PfcpCodec.encodeUsageReports(
                            List.of(new PfcpUsageReport(7, PfcpIe.UT_VOLTH, 10, 20, 3)), PfcpIe.USAGE_REPORT_SDR))));
            assertEquals(PfcpMessageType.SESSION_DELETION_RESPONSE, atCp.getLast().type());
            assertEquals(20L, PfcpCodec.decodeUsageReports(atCp.getLast().payload()).get(0).downlinkVolume());

            up.sendCommand(new com.microjainslee.ra.pfcp.command.SendPfcpMessage(new PfcpMessage(
                    PfcpMessageType.SESSION_REPORT_REQUEST, 77L, 9, PfcpCodec.encodeUsageReports(
                            List.of(new PfcpUsageReport(7, PfcpIe.UT_PERIO, 1, 2, 60)))), CP));
        } finally {
            cp.deactivate();
            up.deactivate();
        }
        assertTrue(dump.size() >= 9, "assoc, heartbeat, est, mod, del (+ answers), report");
        StringBuilder sb = new StringBuilder();
        for (byte[] b : dump) {
            for (int i = 0; i < b.length; i += 16) {
                sb.append(String.format("%06x ", i));
                for (int j = i; j < Math.min(b.length, i + 16); j++) {
                    sb.append(String.format(" %02x", b[j] & 0xff));
                }
                sb.append('\n');
            }
        }
        Path out = Path.of("target", "pfcp-ts29244.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb, StandardCharsets.US_ASCII);
    }

    private static RaBootstrapPort sink(List<PfcpMessage> into) {
        return new RaBootstrapPort() {
            @Override public ActivityHandle createActivityHandle(String id) { return () -> id; }
            @Override public void fireEvent(SleeEvent e, ActivityHandle h, Address a) {
                if (e instanceof com.microjainslee.ra.pfcp.event.PfcpMessageEvent m) {
                    into.add(m.message());
                }
            }
        };
    }
}
