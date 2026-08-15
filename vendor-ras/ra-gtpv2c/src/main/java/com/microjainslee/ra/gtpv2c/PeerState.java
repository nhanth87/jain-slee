package com.microjainslee.ra.gtpv2c;

import java.util.Objects;

/**
 * GTP peer liveness. {@link #live} is Echo Response evidence only.
 * Local LISTEN / RA started must never flip live to true.
 */
public record PeerState(String name, boolean localListen, boolean live, String evidence) {
    public PeerState {
        Objects.requireNonNull(name);
        Objects.requireNonNull(evidence);
        if (live && evidence.isBlank()) {
            throw new IllegalArgumentException("live requires peer evidence");
        }
    }

    public static PeerState down(String name, boolean localListen, String why) {
        return new PeerState(name, localListen, false, why);
    }

    public static PeerState up(String name, String evidence) {
        return new PeerState(name, true, true, evidence);
    }
}
