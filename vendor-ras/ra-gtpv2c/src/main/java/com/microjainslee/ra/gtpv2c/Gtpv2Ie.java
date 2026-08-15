package com.microjainslee.ra.gtpv2c;

import java.util.Objects;

public record Gtpv2Ie(int type, int instance, byte[] value) {
    public Gtpv2Ie {
        Objects.requireNonNull(value);
        if (instance < 0 || instance > 15) {
            throw new IllegalArgumentException("IE instance 0–15");
        }
    }

    public static final int IMSI = 1;
    public static final int CAUSE = 2;
    public static final int RECOVERY = 3;
    public static final int APN = 71;
    public static final int FTEID = 87;
    public static final int BEARER_CONTEXT = 93;
    public static final int CHARGING_ID = 94;
    /** Lab alias — not IE 93 (that is Bearer Context). PAA is {@link #PDN_ADDRESS}. */
    public static final int UE_IP = 80;
    public static final int PDN_ADDRESS = 79;
    public static final int SEQUENCE = 0;
}
