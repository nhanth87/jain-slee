package com.microjainslee.ra.gtpv2c;

/** TS 29.274 message types used by SGW-C / PGW-C. */
public enum Gtpv2MessageType {
    ECHO_REQUEST(1),
    ECHO_RESPONSE(2),
    CREATE_SESSION_REQUEST(32),
    CREATE_SESSION_RESPONSE(33),
    MODIFY_BEARER_REQUEST(34),
    MODIFY_BEARER_RESPONSE(35),
    DELETE_SESSION_REQUEST(36),
    DELETE_SESSION_RESPONSE(37),
    CREATE_BEARER_REQUEST(95),
    CREATE_BEARER_RESPONSE(96),
    UPDATE_BEARER_REQUEST(97),
    UPDATE_BEARER_RESPONSE(98),
    DELETE_BEARER_REQUEST(99),
    DELETE_BEARER_RESPONSE(100),
    RELEASE_ACCESS_BEARERS_REQUEST(170),
    RELEASE_ACCESS_BEARERS_RESPONSE(171),
    DOWNLINK_DATA_NOTIFICATION(176);

    public final int code;

    Gtpv2MessageType(int code) {
        this.code = code;
    }

    public static Gtpv2MessageType of(int code) {
        for (Gtpv2MessageType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        throw new IllegalArgumentException("unknown GTPv2 type " + code);
    }
}
