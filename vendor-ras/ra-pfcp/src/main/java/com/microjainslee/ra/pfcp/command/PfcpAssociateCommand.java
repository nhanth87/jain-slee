package com.microjainslee.ra.pfcp.command;

import java.net.InetSocketAddress;
import java.util.Objects;

public record PfcpAssociateCommand(InetSocketAddress upf) implements PfcpOutboundCommand {
    public PfcpAssociateCommand {
        Objects.requireNonNull(upf);
    }
}
