package com.microjainslee.ra.pfcp;

/**
 * Forwarding Action Rule (TS 29.244 Create FAR). {@code remote} becomes the
 * Outer Header Creation F-TEID inside Forwarding Parameters.
 */
public record PfcpFar(
        int farId,
        int applyAction,
        PfcpFteid remote,
        Integer outerHeaderRemoval,
        Integer barId) {

    /** Forwarding FAR with Outer Header Creation (GTP-U/UDP/IPv4 removal assumed for ingress). */
    public PfcpFar(int farId, int applyAction, PfcpFteid remote) {
        this(farId, applyAction, remote, null, null);
    }
}