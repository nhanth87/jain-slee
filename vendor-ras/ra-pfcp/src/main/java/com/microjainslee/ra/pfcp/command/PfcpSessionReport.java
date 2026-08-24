package com.microjainslee.ra.pfcp.command;

import com.microjainslee.ra.pfcp.PfcpIe;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/** PFCP Session Report Request (TS 29.244 §7.5.6) carrying usage/downlink-data report IEs. */
public record PfcpSessionReport(InetSocketAddress upf, long seid, List<PfcpIe> ies)
        implements PfcpOutboundCommand {
    public PfcpSessionReport {
        Objects.requireNonNull(upf);
        ies = List.copyOf(ies == null ? List.of() : ies);
    }

    public PfcpSessionReport(InetSocketAddress upf, long seid) {
        this(upf, seid, List.of());
    }
}