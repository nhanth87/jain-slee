package com.microjainslee.ra.pfcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Full TS 29.244 PDR/FAR/QER/URR/BAR + association IE byte-layout tests. */
class PfcpIeCodecTest {

    @Test
    void encodesFullPdrFarQerUrrBarLayout() throws Exception {
        InetAddress localAddr = InetAddress.getByName("192.0.2.10");
        InetAddress remoteAddr = InetAddress.getByName("192.0.2.20");
        InetAddress ue = InetAddress.getByName("10.64.0.2");

        PfcpPdr pdr = new PfcpPdr((short) 1, 500, PfcpIe.INTERFACE_ACCESS,
                new PfcpFteid(0x01020304, localAddr, 0), ue, 1,
                List.of(1), List.of(1), false);
        PfcpFar far = new PfcpFar(1, PfcpIe.APPLY_FORW,
                new PfcpFteid(0x05060708, remoteAddr, 0), 0, null);
        PfcpQer qer = new PfcpQer(1, 3);
        PfcpUrr urr = new PfcpUrr(1, PfcpIe.MM_VOLUME, 1);
        PfcpBar bar = new PfcpBar(1, 3);

        List<PfcpIe> outer = PfcpCodec.decodeIes(PfcpCodec.encodeIes(
                PfcpIes.sessionIes(List.of(pdr), List.of(far), List.of(qer), List.of(urr), List.of(bar))));

        assertEquals(5, outer.size());
        assertEquals(PfcpIe.CREATE_PDR, outer.get(0).type());
        assertEquals(PfcpIe.CREATE_FAR, outer.get(1).type());
        assertEquals(PfcpIe.CREATE_QER, outer.get(2).type());
        assertEquals(PfcpIe.CREATE_URR, outer.get(3).type());
        assertEquals(PfcpIe.CREATE_BAR, outer.get(4).type());

        // Create PDR group layout.
        List<PfcpIe> pdrC = PfcpIes.children(outer.get(0));
        assertEquals(PfcpIe.PDR_ID, pdrC.get(0).type());
        assertArrayEquals(new byte[] {0x00, 0x01}, pdrC.get(0).value());
        assertEquals(PfcpIe.PRECEDENCE, pdrC.get(1).type());
        assertArrayEquals(new byte[] {0x00, 0x00, 0x01, (byte) 0xF4}, pdrC.get(1).value());

        assertEquals(PfcpIe.PDI, pdrC.get(2).type());
        List<PfcpIe> pdiC = PfcpIes.children(pdrC.get(2));
        assertEquals(3, pdiC.size());
        assertEquals(PfcpIe.SOURCE_INTERFACE, pdiC.get(0).type());
        assertArrayEquals(new byte[] {0x00}, pdiC.get(0).value());
        assertEquals(PfcpIe.F_TEID, pdiC.get(1).type());
        assertArrayEquals(new byte[] {(byte) 0x80, 0x01, 0x02, 0x03, 0x04,
                (byte) 192, 0, 2, 10}, pdiC.get(1).value());
        assertEquals(PfcpIe.UE_IP_ADDRESS, pdiC.get(2).type());
        assertArrayEquals(new byte[] {0x02, 10, 64, 0, 2}, pdiC.get(2).value());

        assertEquals(PfcpIe.FAR_ID, pdrC.get(3).type());
        assertArrayEquals(new byte[] {0, 0, 0, 1}, pdrC.get(3).value());
        assertEquals(PfcpIe.QER_ID, pdrC.get(4).type());
        assertEquals(PfcpIe.URR_ID, pdrC.get(5).type());

        // Create FAR group layout.
        List<PfcpIe> farC = PfcpIes.children(outer.get(1));
        assertEquals(PfcpIe.FAR_ID, farC.get(0).type());
        assertEquals(PfcpIe.APPLY_ACTION, farC.get(1).type());
        assertArrayEquals(new byte[] {0x02}, farC.get(1).value());
        assertEquals(PfcpIe.FORWARDING_PARAMETERS, farC.get(2).type());
        List<PfcpIe> fwdC = PfcpIes.children(farC.get(2));
        assertEquals(PfcpIe.DESTINATION_INTERFACE, fwdC.get(0).type());
        assertArrayEquals(new byte[] {0x01}, fwdC.get(0).value());
        assertEquals(PfcpIe.OUTER_HEADER_CREATION, fwdC.get(1).type());
        assertArrayEquals(new byte[] {0x15, 0x00, 0x05, 0x06, 0x07, 0x08,
                (byte) 192, 0, 2, 20}, fwdC.get(1).value());
        assertEquals(PfcpIe.OUTER_HEADER_REMOVAL, farC.get(3).type());
        assertArrayEquals(new byte[] {0x00}, farC.get(3).value());

        // Create QER group layout.
        List<PfcpIe> qerC = PfcpIes.children(outer.get(2));
        assertEquals(PfcpIe.QER_ID, qerC.get(0).type());
        assertArrayEquals(new byte[] {0, 0, 0, 1}, qerC.get(0).value());
        assertEquals(PfcpIe.GATE_STATUS, qerC.get(1).type());
        assertArrayEquals(new byte[] {0x03}, qerC.get(1).value());

        // Create URR group layout.
        List<PfcpIe> urrC = PfcpIes.children(outer.get(3));
        assertEquals(PfcpIe.URR_ID, urrC.get(0).type());
        assertArrayEquals(new byte[] {0, 0, 0, 1}, urrC.get(0).value());
        assertEquals(PfcpIe.MEASUREMENT_METHOD, urrC.get(1).type());
        assertArrayEquals(new byte[] {0x02}, urrC.get(1).value());
        assertEquals(PfcpIe.REPORTING_TRIGGERS, urrC.get(2).type());
        assertArrayEquals(new byte[] {0x00, 0x01}, urrC.get(2).value());

        // Create BAR group layout.
        List<PfcpIe> barC = PfcpIes.children(outer.get(4));
        assertEquals(PfcpIe.BAR_ID, barC.get(0).type());
        assertArrayEquals(new byte[] {0x01}, barC.get(0).value());
        assertEquals(PfcpIe.DOWNLINK_DATA_NOTIFICATION_DELAY, barC.get(1).type());
        assertArrayEquals(new byte[] {0x03}, barC.get(1).value());

        // Typed round-trips.
        assertEquals(pdr.pdrId(), PfcpIes.parsePdr(outer.get(0)).pdrId());
        assertEquals(remoteAddr, PfcpIes.parseFar(outer.get(1)).remote().address());
        assertEquals(3, PfcpIes.parseQer(outer.get(2)).gateStatus());
        assertEquals(PfcpIe.MM_VOLUME, PfcpIes.parseUrr(outer.get(3)).measurementMethod());
        assertEquals(3, PfcpIes.parseBar(outer.get(4)).downlinkDataNotificationDelay());
    }

    @Test
    void decodesNodeIdFSeidRecoveryBytes() throws Exception {
        PfcpIe nodeId = PfcpIes.nodeId(PfcpNodeId.of(InetAddress.getByName("192.0.2.30")));
        assertArrayEquals(new byte[] {0x00, (byte) 192, 0, 2, 30}, nodeId.value());

        PfcpIe fSeid = PfcpIes.fSeid(0x0102030405060708L, InetAddress.getByName("10.0.0.1"));
        assertArrayEquals(new byte[] {0x40, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                10, 0, 0, 1}, fSeid.value());

        PfcpIe recovery = PfcpIes.recoveryTimeStamp(0x00010400);
        assertArrayEquals(new byte[] {0x00, 0x01, 0x04, 0x00}, recovery.value());
    }

    @Test
    void messageCarriesIesIntoPayload() {
        PfcpMessage msg = PfcpMessage.associationSetupRequest(7,
                PfcpNodeId.of("upf1.example"), 0x11223344);
        assertEquals(PfcpMessageType.ASSOCIATION_SETUP_REQUEST, msg.type());
        assertEquals(0x11223344, msg.recoveryTimeStamp());
        assertEquals("upf1.example", msg.nodeId().fqdn());
        assertEquals(2, msg.ies().size());
    }
}