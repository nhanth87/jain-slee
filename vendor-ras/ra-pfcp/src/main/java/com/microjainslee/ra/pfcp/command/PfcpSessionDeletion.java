package com.microjainslee.ra.pfcp.command;

import com.microjainslee.ra.pfcp.PfcpIe;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/** PFCP Session Deletion Request (TS 29.244 §7.5.5). */
public record PfcpSessionDeletion(InetSocketAddress upf, long seid, List<PfcpIe> ies)
        implements PfcpOutboundCommand {
    public PfcpSessionDeletion {
        Objects.requireNonNull(upf);
        ies = List.copyOf(ies == null ? List.of() : ies);
    }

    public PfcpSessionDeletion(InetSocketAddress upf, long seid) {
        this(upf, seid, List.of());
    }
}