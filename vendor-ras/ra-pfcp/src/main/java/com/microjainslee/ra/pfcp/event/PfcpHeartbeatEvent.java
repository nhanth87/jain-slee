package com.microjainslee.ra.pfcp.event;

import java.net.InetSocketAddress;
import java.util.Objects;

public record PfcpHeartbeatEvent(InetSocketAddress peer, boolean response) implements PfcpEvent {
    public PfcpHeartbeatEvent {
        Objects.requireNonNull(peer);
    }
}
