package com.microjainslee.ra.gtpv2c;

/** TS 29.274 message types used by SGW-C / PGW-C. */
public enum Gtpv2MessageType {
    UNKNOWN(0),
    ECHO_REQUEST(1),
    ECHO_RESPONSE(2),
    VERSION_NOT_SUPPORTED(3),
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
    DOWNLINK_DATA_NOTIFICATION(176),
    DOWNLINK_DATA_NOTIFICATION_ACKNOWLEDGE(177);

    public final int code;

    Gtpv2MessageType(int code) {
        this.code = code;
    }

    /**
     * TS 29.274 §5.1: Echo and Version Not Supported carry T=0 (no TEID).
     * Create/Modify/Delete Session and bearer procedures carry T=1.
     */
    public boolean teidInHeader() {
        return switch (this) {
            case UNKNOWN, ECHO_REQUEST, ECHO_RESPONSE, VERSION_NOT_SUPPORTED -> false;
            default -> true;
        };
    }

    /**
     * Outbound T3/N3 applies to these only. Even/odd is not a detector:
     * Echo Request=1 is odd, Create Bearer Request=95 is odd, while
     * Create Session Request=32 is even.
     */
    public boolean isRequest() {
        return switch (this) {
            case ECHO_REQUEST,
                    CREATE_SESSION_REQUEST,
                    MODIFY_BEARER_REQUEST,
                    DELETE_SESSION_REQUEST,
                    CREATE_BEARER_REQUEST,
                    UPDATE_BEARER_REQUEST,
                    DELETE_BEARER_REQUEST,
                    RELEASE_ACCESS_BEARERS_REQUEST,
                    DOWNLINK_DATA_NOTIFICATION -> true;
            default -> false;
        };
    }

    /** Matching reply to a request we sent — cancels T3. Never retransmitted. */
    public boolean isResponse() {
        return switch (this) {
            case ECHO_RESPONSE,
                    VERSION_NOT_SUPPORTED,
                    CREATE_SESSION_RESPONSE,
                    MODIFY_BEARER_RESPONSE,
                    DELETE_SESSION_RESPONSE,
                    CREATE_BEARER_RESPONSE,
                    UPDATE_BEARER_RESPONSE,
                    DELETE_BEARER_RESPONSE,
                    RELEASE_ACCESS_BEARERS_RESPONSE,
                    DOWNLINK_DATA_NOTIFICATION_ACKNOWLEDGE -> true;
            default -> false;
        };
    }

    public static Gtpv2MessageType of(int code) {
        for (Gtpv2MessageType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return UNKNOWN;
    }
}
