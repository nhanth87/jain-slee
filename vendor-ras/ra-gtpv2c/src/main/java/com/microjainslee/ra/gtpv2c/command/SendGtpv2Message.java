package com.microjainslee.ra.gtpv2c.command;

import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import java.net.InetSocketAddress;
import java.util.Objects;

public record SendGtpv2Message(Gtpv2Message message, InetSocketAddress peer)
        implements GtpOutboundCommand {
    public SendGtpv2Message {
        Objects.requireNonNull(message);
        Objects.requireNonNull(peer);
    }
}
