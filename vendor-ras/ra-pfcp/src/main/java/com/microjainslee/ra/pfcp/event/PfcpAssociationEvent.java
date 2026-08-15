package com.microjainslee.ra.pfcp.event;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Association Setup Response — peer evidence that Sxa/Sxb is live. */
public record PfcpAssociationEvent(InetSocketAddress peer, boolean associated) implements PfcpEvent {
    public PfcpAssociationEvent {
        Objects.requireNonNull(peer);
    }
}
