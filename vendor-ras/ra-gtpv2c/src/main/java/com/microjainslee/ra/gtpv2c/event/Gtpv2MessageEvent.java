package com.microjainslee.ra.gtpv2c.event;

import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import java.net.InetSocketAddress;
import java.util.Objects;

public record Gtpv2MessageEvent(Gtpv2Message message, InetSocketAddress peer) implements GtpEvent {
    public Gtpv2MessageEvent {
        Objects.requireNonNull(message);
        Objects.requireNonNull(peer);
    }
}
