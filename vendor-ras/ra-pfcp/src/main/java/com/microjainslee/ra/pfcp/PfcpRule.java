package com.microjainslee.ra.pfcp;

import java.util.Objects;

/** User-plane forwarding rule programmed over PFCP. Java never forwards the packet. */
public record PfcpRule(long seid, String pdrId, PfcpFteid local, PfcpFteid remote, String qerId, boolean uplink) {
    public PfcpRule {
        Objects.requireNonNull(pdrId);
        Objects.requireNonNull(local);
        Objects.requireNonNull(remote);
    }
}
