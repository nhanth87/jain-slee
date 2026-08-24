package com.microjainslee.ra.pfcp;

import java.net.InetSocketAddress;
import java.util.Objects;

/** Association FSM state for one PFCP peer (UPF). */
final class PfcpPeer {
    final InetSocketAddress address;
    volatile boolean associated;
    volatile int peerRecovery = -1;
    volatile String evidence = "no-association";

    PfcpPeer(InetSocketAddress address) {
        this.address = Objects.requireNonNull(address);
    }
}