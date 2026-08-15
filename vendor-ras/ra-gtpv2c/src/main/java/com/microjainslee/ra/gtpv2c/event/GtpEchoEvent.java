package com.microjainslee.ra.gtpv2c.event;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Peer Echo Response — the only evidence that GTP-C is live. */
public record GtpEchoEvent(InetSocketAddress peer, byte recovery, boolean response)
        implements GtpEvent {
    public GtpEchoEvent {
        Objects.requireNonNull(peer);
    }
}
