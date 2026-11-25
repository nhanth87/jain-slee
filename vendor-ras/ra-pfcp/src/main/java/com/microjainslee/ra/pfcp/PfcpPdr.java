package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.util.List;

/**
 * Packet Detection Rule (TS 29.244 §7.5.2.2 Create PDR). Java never forwards the
 * packet — this is the CP-side description of a PDR plus its linked FAR/QER/URR ids.
 * {@code outerHeaderRemoval} (§8.2.64, null = absent) strips the GTP-U header on a
 * PDR that matches an F-TEID.
 */
public record PfcpPdr(
        short pdrId,
        int precedence,
        int sourceInterface,
        PfcpFteid local,
        InetAddress ueIpv4,
        Integer farId,
        List<Integer> qerIds,
        List<Integer> urrIds,
        boolean downlinkDataReport,
        Integer outerHeaderRemoval) {

    public PfcpPdr {
        qerIds = List.copyOf(qerIds == null ? List.of() : qerIds);
        urrIds = List.copyOf(urrIds == null ? List.of() : urrIds);
    }

    /** Back-compat constructor: no Outer Header Removal. */
    public PfcpPdr(short pdrId, int precedence, int sourceInterface, PfcpFteid local,
            InetAddress ueIpv4, Integer farId, List<Integer> qerIds, List<Integer> urrIds,
            boolean downlinkDataReport) {
        this(pdrId, precedence, sourceInterface, local, ueIpv4, farId, qerIds, urrIds,
                downlinkDataReport, null);
    }

    /** Convenience constructor: access-side PDR with a single linked FAR. */
    public PfcpPdr(short pdrId, int precedence, PfcpFteid local, int farId) {
        this(pdrId, precedence, PfcpIe.INTERFACE_ACCESS, local, null, farId,
                List.of(), List.of(), false, null);
    }
}
