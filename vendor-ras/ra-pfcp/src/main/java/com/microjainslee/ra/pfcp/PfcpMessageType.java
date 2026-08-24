package com.microjainslee.ra.pfcp;

/** TS 29.244 message types used on Sxa/Sxb/N4. */
public enum PfcpMessageType {
    UNKNOWN(-1),
    HEARTBEAT_REQUEST(1),
    HEARTBEAT_RESPONSE(2),
    ASSOCIATION_SETUP_REQUEST(5),
    ASSOCIATION_SETUP_RESPONSE(6),
    ASSOCIATION_UPDATE_REQUEST(7),
    ASSOCIATION_UPDATE_RESPONSE(8),
    ASSOCIATION_RELEASE_REQUEST(9),
    ASSOCIATION_RELEASE_RESPONSE(10),
    VERSION_NOT_SUPPORTED_RESPONSE(11),
    SESSION_ESTABLISHMENT_REQUEST(50),
    SESSION_ESTABLISHMENT_RESPONSE(51),
    SESSION_MODIFICATION_REQUEST(52),
    SESSION_MODIFICATION_RESPONSE(53),
    SESSION_DELETION_REQUEST(54),
    SESSION_DELETION_RESPONSE(55),
    SESSION_REPORT_REQUEST(56),
    SESSION_REPORT_RESPONSE(57);

    public final int code;

    PfcpMessageType(int code) {
        this.code = code;
    }

    /** Does the PFCP header carry an SEID (S flag) for this message? */
    public boolean seidInHeader() {
        return switch (this) {
            case HEARTBEAT_REQUEST, HEARTBEAT_RESPONSE,
                    ASSOCIATION_SETUP_REQUEST, ASSOCIATION_SETUP_RESPONSE,
                    ASSOCIATION_UPDATE_REQUEST, ASSOCIATION_UPDATE_RESPONSE,
                    ASSOCIATION_RELEASE_REQUEST, ASSOCIATION_RELEASE_RESPONSE,
                    VERSION_NOT_SUPPORTED_RESPONSE, UNKNOWN -> false;
            default -> true;
        };
    }

    /** Outbound request (drives T3/heartbeat style retransmit/recovery). */
    public boolean isRequest() {
        return switch (this) {
            case HEARTBEAT_REQUEST, ASSOCIATION_SETUP_REQUEST, ASSOCIATION_UPDATE_REQUEST,
                    ASSOCIATION_RELEASE_REQUEST, SESSION_ESTABLISHMENT_REQUEST,
                    SESSION_MODIFICATION_REQUEST, SESSION_DELETION_REQUEST, SESSION_REPORT_REQUEST -> true;
            default -> false;
        };
    }

    /** Matching response to a request we sent. */
    public boolean isResponse() {
        return switch (this) {
            case HEARTBEAT_RESPONSE, ASSOCIATION_SETUP_RESPONSE, ASSOCIATION_UPDATE_RESPONSE,
                    ASSOCIATION_RELEASE_RESPONSE, VERSION_NOT_SUPPORTED_RESPONSE,
                    SESSION_ESTABLISHMENT_RESPONSE, SESSION_MODIFICATION_RESPONSE,
                    SESSION_DELETION_RESPONSE, SESSION_REPORT_RESPONSE -> true;
            default -> false;
        };
    }

    public static PfcpMessageType of(int code) {
        for (PfcpMessageType t : values()) {
            if (t.code == code) {
                return t;
            }
        }
        return UNKNOWN;
    }
}
