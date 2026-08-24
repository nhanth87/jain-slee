package com.microjainslee.ra.pfcp.command;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Drive a single heartbeat exchanges with a peer (also used by the RA's heartbeat timer). */
public record PfcpHeartbeatCommand(InetSocketAddress upf) implements PfcpOutboundCommand {
    public PfcpHeartbeatCommand {
        Objects.requireNonNull(upf);
    }
}