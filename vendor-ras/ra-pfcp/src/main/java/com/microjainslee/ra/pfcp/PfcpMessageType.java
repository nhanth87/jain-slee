package com.microjainslee.ra.pfcp;

/** TS 29.244 message types used on Sxa/Sxb. */
public enum PfcpMessageType {
    HEARTBEAT_REQUEST(1),
    HEARTBEAT_RESPONSE(2),
    ASSOCIATION_SETUP_REQUEST(5),
    ASSOCIATION_SETUP_RESPONSE(6),
    SESSION_ESTABLISHMENT_REQUEST(50),
    SESSION_ESTABLISHMENT_RESPONSE(51),
    SESSION_MODIFICATION_REQUEST(52),
    SESSION_MODIFICATION_RESPONSE(53),
    SESSION_DELETION_REQUEST(54),
    SESSION_DELETION_RESPONSE(55);

    public final int code;

    PfcpMessageType(int code) {
        this.code = code;
    }

    public static PfcpMessageType of(int code) {
        for (PfcpMessageType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown PFCP type " + code);
    }
}
