package com.microjainslee.ra.pfcp.command;

import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpMessage;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/**
 * UP-side answer to a Session Establishment / Modification / Deletion Request
 * (TS 29.244 §7.5.3 / §7.5.5 / §7.5.7). The RA adds the mandatory IEs it owns —
 * Node ID and UP F-SEID on an Establishment Response, Cause on every response —
 * and puts the CP's SEID in the header (§7.2.2.4.2). {@code ies} carries the rest
 * (Created PDR, Usage Report, Offending IE …).
 */
public record PfcpSessionAnswer(InetSocketAddress peer, PfcpMessage request, int cause, List<PfcpIe> ies)
        implements PfcpOutboundCommand {
    public PfcpSessionAnswer {
        Objects.requireNonNull(peer);
        Objects.requireNonNull(request);
        ies = List.copyOf(ies == null ? List.of() : ies);
    }

    public PfcpSessionAnswer(InetSocketAddress peer, PfcpMessage request, int cause) {
        this(peer, request, cause, List.of());
    }
}
