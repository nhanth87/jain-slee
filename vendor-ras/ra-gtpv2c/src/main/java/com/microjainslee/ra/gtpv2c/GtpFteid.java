package com.microjainslee.ra.gtpv2c;

import java.net.InetAddress;
import java.util.Objects;

/** Fully-qualified TEID on the GTPv2-C wire (TS 29.274). App maps to its own F-TEID type. */
public record GtpFteid(int teid, InetAddress address, int interfaceType) {
    public GtpFteid {
        Objects.requireNonNull(address);
    }

    public String key() {
        return Integer.toUnsignedString(teid) + "@" + address.getHostAddress();
    }
}
