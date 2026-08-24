package com.microjainslee.ra.pfcp;

import java.net.InetAddress;

/** CP F-SEID (TS 29.244) — the PFCP node's control-plane SEID and address. */
public record PfcpFSeid(long seid, InetAddress ipv4) {
    public PfcpFSeid(long seid) {
        this(seid, null);
    }
}