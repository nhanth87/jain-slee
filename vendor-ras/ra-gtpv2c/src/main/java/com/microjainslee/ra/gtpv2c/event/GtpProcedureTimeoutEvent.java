package com.microjainslee.ra.gtpv2c.event;

import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * A request this RA sent got no response after T3 × (N3 + 1) (TS 29.274 §7.6). The SBB that
 * started the procedure ends it (e.g. an SGW-C answers the MME "Remote peer not responding").
 */
public record GtpProcedureTimeoutEvent(Gtpv2MessageType type, int sequence, InetSocketAddress peer)
        implements GtpEvent {
    public GtpProcedureTimeoutEvent {
        Objects.requireNonNull(type);
        Objects.requireNonNull(peer);
    }
}
