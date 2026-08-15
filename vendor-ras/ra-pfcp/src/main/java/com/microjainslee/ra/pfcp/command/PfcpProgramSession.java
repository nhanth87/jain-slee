package com.microjainslee.ra.pfcp.command;

import com.microjainslee.ra.pfcp.PfcpRule;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

public record PfcpProgramSession(InetSocketAddress upf, long seid, List<PfcpRule> rules)
        implements PfcpOutboundCommand {
    public PfcpProgramSession {
        Objects.requireNonNull(upf);
        rules = List.copyOf(rules == null ? List.of() : rules);
    }
}
