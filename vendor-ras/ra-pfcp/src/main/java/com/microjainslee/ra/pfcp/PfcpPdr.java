package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.util.List;

/**
 * Packet Detection Rule (TS 29.244 Create PDR). Java never forwards the packet —
 * this is the CP-side description of a PDR plus its linked FAR/QER/URR ids.
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
        boolean downlinkDataReport) {

    public PfcpPdr {
        qerIds = List.copyOf(qerIds == null ? List.of() : qerIds);
        urrIds = List.copyOf(urrIds == null ? List.of() : urrIds);
    }

    /** Convenience constructor: access-side PDR with a single linked FAR. */
    public PfcpPdr(short pdrId, int precedence, PfcpFteid local, int farId) {
        this(pdrId, precedence, PfcpIe.INTERFACE_ACCESS, local, null, farId,
                List.of(), List.of(), false);
    }
}