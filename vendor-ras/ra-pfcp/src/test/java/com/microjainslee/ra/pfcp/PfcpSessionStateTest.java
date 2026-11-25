package com.microjainslee.ra.pfcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S1 release / re-establishment at the SGW-U (TS 23.401 §5.3.4 / §5.3.5, TS 29.244 §7.5.4):
 * Create Session installs uplink FORW and downlink BUFF; Modify Bearer turns the downlink FAR
 * to FORW toward the eNB; Release Access Bearers buffers again.
 */
class PfcpSessionStateTest {

    private static PfcpFteid f(int teid, String ip) throws Exception {
        return new PfcpFteid(teid, InetAddress.getByName(ip), 0);
    }

    private static List<PfcpIe> establishment() throws Exception {
        InetAddress ue = InetAddress.getByName("10.64.0.2");
        return List.of(
                PfcpIes.createPdr(new PfcpPdr((short) 1, 254, PfcpIe.INTERFACE_ACCESS, f(0x1001, "192.0.2.16"), ue,
                        1, List.of(1), List.of(), false, PfcpIes.OHR_GTPU_UDP_IPV4)),
                PfcpIes.createFar(new PfcpFar(1, PfcpIe.APPLY_FORW, f(0x2001, "192.0.2.13")), PfcpIe.INTERFACE_CORE),
                PfcpIes.createPdr(new PfcpPdr((short) 2, 253, PfcpIe.INTERFACE_CORE, f(0x1002, "192.0.2.16"), ue,
                        2, List.of(1), List.of(), false, PfcpIes.OHR_GTPU_UDP_IPV4)),
                PfcpIes.createFar(new PfcpFar(2, PfcpIe.APPLY_BUFF | PfcpIe.APPLY_NOCP, null), PfcpIe.INTERFACE_ACCESS),
                PfcpIes.createQer(new PfcpQer(1)));
    }

    @Test
    void bufferedDownlinkHasNoTunnelUntilModifyBearer() throws Exception {
        PfcpSessionState st = PfcpSessionState.empty().apply(establishment());
        assertEquals(1, st.tunnels(7L).size());
        assertEquals("pdr-1", st.tunnels(7L).get(0).pdrId());
        assertEquals("10.64.0.2", st.ueIpv4().getHostAddress());

        PfcpSessionState modified = st.apply(List.of(PfcpIes.updateFar(2, PfcpIe.APPLY_FORW,
                f(0xe001, "192.0.2.1"), PfcpIe.INTERFACE_ACCESS)));
        assertEquals(2, modified.tunnels(7L).size());
        PfcpRule dl = modified.tunnels(7L).get(1);
        assertEquals(0x1002, dl.local().teid());
        assertEquals(0xe001, dl.remote().teid());
        assertEquals(false, dl.uplink());

        PfcpSessionState released = modified.apply(List.of(PfcpIes.updateFar(2,
                PfcpIe.APPLY_BUFF | PfcpIe.APPLY_NOCP, null, PfcpIe.INTERFACE_ACCESS)));
        assertEquals(1, released.tunnels(7L).size());
        // The eNB target is kept: a later Update FAR with only FORW resumes toward it.
        assertEquals(0xe001, released.fars().get(2).remote().teid());
    }

    @Test
    void unknownFarIsARuleFailure() throws Exception {
        PfcpSessionState st = PfcpSessionState.empty().apply(establishment());
        assertThrows(PfcpSessionState.RuleNotFound.class, () -> st.apply(List.of(
                PfcpIes.updateFar(9, PfcpIe.APPLY_FORW, f(1, "192.0.2.1"), PfcpIe.INTERFACE_ACCESS))));
        assertThrows(PfcpSessionState.RuleNotFound.class, () -> st.apply(List.of(PfcpIes.removePdr(9))));
    }

    /** PGW-U (anchor): UL F-TEID → SGi, DL UE IP → GTP-U toward the SGW = one tunnel. */
    @Test
    void anchorShapeIsOneTunnelTowardTheSgw() throws Exception {
        InetAddress ue = InetAddress.getByName("10.64.0.2");
        PfcpSessionState st = PfcpSessionState.empty().apply(List.of(
                PfcpIes.createPdr(new PfcpPdr((short) 1, 255, PfcpIe.INTERFACE_ACCESS, f(0x3001, "192.0.2.16"), ue,
                        1, List.of(), List.of(), false, PfcpIes.OHR_GTPU_UDP_IPV4)),
                PfcpIes.createFar(new PfcpFar(1, PfcpIe.APPLY_FORW, null), PfcpIe.INTERFACE_CORE),
                PfcpIes.createPdr(new PfcpPdr((short) 2, 254, PfcpIe.INTERFACE_CORE, null, ue,
                        2, List.of(), List.of(), false, null)),
                PfcpIes.createFar(new PfcpFar(2, PfcpIe.APPLY_FORW, f(0x1002, "192.0.2.14")), PfcpIe.INTERFACE_ACCESS)));
        assertEquals(1, st.tunnels(9L).size());
        PfcpRule t = st.tunnels(9L).get(0);
        assertEquals(0x3001, t.local().teid());
        assertEquals(0x1002, t.remote().teid());
        assertEquals("10.64.0.2", st.ueIpv4().getHostAddress());
    }

    @Test
    void removePdrDropsTheTunnel() throws Exception {
        PfcpSessionState st = PfcpSessionState.empty().apply(establishment())
                .apply(List.of(PfcpIes.removePdr(1), PfcpIes.removeFar(1)));
        assertEquals(0, st.tunnels(7L).size());
    }
}
