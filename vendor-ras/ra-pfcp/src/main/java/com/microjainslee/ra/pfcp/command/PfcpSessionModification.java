package com.microjainslee.ra.pfcp.command;

import com.microjainslee.ra.pfcp.PfcpIe;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/** PFCP Session Modification Request (TS 29.244 §7.5.4) carrying the session IE set. */
public record PfcpSessionModification(InetSocketAddress upf, long seid, List<PfcpIe> ies)
        implements PfcpOutboundCommand {
    public PfcpSessionModification {
        Objects.requireNonNull(upf);
        ies = List.copyOf(ies == null ? List.of() : ies);
    }

    public PfcpSessionModification(InetSocketAddress upf, long seid) {
        this(upf, seid, List.of());
    }
}