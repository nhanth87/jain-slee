package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.util.Objects;

/** Fully-qualified TEID on a PFCP forwarding rule (TS 29.244). App maps to its own F-TEID type. */
public record PfcpFteid(int teid, InetAddress address, int interfaceType) {
    public PfcpFteid {
        Objects.requireNonNull(address);
    }

    public String key() {
        return Integer.toUnsignedString(teid) + "@" + address.getHostAddress();
    }
}
