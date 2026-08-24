package com.microjainslee.ra.pfcp.command;

import com.microjainslee.ra.pfcp.PfcpIe;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/** PFCP Session Establishment Request (TS 29.244 §7.5.3) carrying the session IE set. */
public record PfcpSessionEstablishment(InetSocketAddress upf, long seid, List<PfcpIe> ies)
        implements PfcpOutboundCommand {
    public PfcpSessionEstablishment {
        Objects.requireNonNull(upf);
        ies = List.copyOf(ies == null ? List.of() : ies);
    }

    public PfcpSessionEstablishment(InetSocketAddress upf, long seid) {
        this(upf, seid, List.of());
    }
}