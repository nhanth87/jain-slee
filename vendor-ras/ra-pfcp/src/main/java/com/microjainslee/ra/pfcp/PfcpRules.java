package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.util.List;
import java.util.Objects;

/** Decoded PFCP session-rule payload: forwarding rules plus the optional UE IPv4. */
public record PfcpRules(List<PfcpRule> rules, InetAddress ueIpv4) {
    public PfcpRules {
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
    }

    public static PfcpRules empty() {
        return new PfcpRules(List.of(), null);
    }
}