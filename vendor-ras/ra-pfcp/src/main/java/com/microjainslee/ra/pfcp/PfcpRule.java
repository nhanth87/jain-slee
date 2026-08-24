package com.microjainslee.ra.pfcp;

import java.util.Objects;

/**
 * User-plane forwarding rule programmed over PFCP. Java never forwards the packet.
 * The optional {@code urr} carries the Create URR (measurement method, reporting
 * triggers and thresholds) for usage reporting; {@code null} = no URR.
 */
public record PfcpRule(long seid, String pdrId, PfcpFteid local, PfcpFteid remote,
        String qerId, boolean uplink, PfcpUrr urr) {
    public PfcpRule {
        Objects.requireNonNull(pdrId);
        Objects.requireNonNull(local);
        Objects.requireNonNull(remote);
    }

    /** Back-compat constructor: no URR. */
    public PfcpRule(long seid, String pdrId, PfcpFteid local, PfcpFteid remote,
            String qerId, boolean uplink) {
        this(seid, pdrId, local, remote, qerId, uplink, null);
    }
}
