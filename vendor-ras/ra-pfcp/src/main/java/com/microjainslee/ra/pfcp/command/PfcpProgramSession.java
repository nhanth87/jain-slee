package com.microjainslee.ra.pfcp.command;

import com.microjainslee.ra.pfcp.PfcpRule;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

public record PfcpProgramSession(InetSocketAddress upf, long seid, List<PfcpRule> rules,
        InetAddress ueIpv4) implements PfcpOutboundCommand {
    public PfcpProgramSession {
        Objects.requireNonNull(upf);
        rules = List.copyOf(rules == null ? List.of() : rules);
    }

    /** Convenience constructor when the UE IPv4 is not known (e.g. delete). */
    public PfcpProgramSession(InetSocketAddress upf, long seid, List<PfcpRule> rules) {
        this(upf, seid, rules, null);
    }
}
