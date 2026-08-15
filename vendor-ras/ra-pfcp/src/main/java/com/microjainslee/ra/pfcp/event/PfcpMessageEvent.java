package com.microjainslee.ra.pfcp.event;

import com.microjainslee.ra.pfcp.PfcpMessage;
import java.net.InetSocketAddress;
import java.util.Objects;

public record PfcpMessageEvent(PfcpMessage message, InetSocketAddress peer) implements PfcpEvent {
    public PfcpMessageEvent {
        Objects.requireNonNull(message);
        Objects.requireNonNull(peer);
    }
}
