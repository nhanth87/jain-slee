package com.microjainslee.ra.pfcp.command;

import com.microjainslee.ra.pfcp.PfcpMessage;
import java.net.InetSocketAddress;
import java.util.Objects;

public record SendPfcpMessage(PfcpMessage message, InetSocketAddress peer) implements PfcpOutboundCommand {
    public SendPfcpMessage {
        Objects.requireNonNull(message);
        Objects.requireNonNull(peer);
    }
}
