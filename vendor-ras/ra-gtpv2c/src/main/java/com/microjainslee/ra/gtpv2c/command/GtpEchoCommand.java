package com.microjainslee.ra.gtpv2c.command;

import java.net.InetSocketAddress;
import java.util.Objects;

public record GtpEchoCommand(InetSocketAddress peer) implements GtpOutboundCommand {
    public GtpEchoCommand {
        Objects.requireNonNull(peer);
    }
}
