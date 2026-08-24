package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.util.Objects;

/** PFCP Node ID (TS 29.244 §8.2.10) — exactly one of IPv4/6 address or FQDN. */
public record PfcpNodeId(InetAddress address, String fqdn) {
    public PfcpNodeId {
        if ((address == null) == (fqdn == null)) {
            throw new IllegalArgumentException("PfcpNodeId needs exactly one of address or fqdn");
        }
    }

    public static PfcpNodeId of(InetAddress address) {
        return new PfcpNodeId(Objects.requireNonNull(address), null);
    }

    public static PfcpNodeId of(String fqdn) {
        return new PfcpNodeId(null, Objects.requireNonNull(fqdn));
    }
}