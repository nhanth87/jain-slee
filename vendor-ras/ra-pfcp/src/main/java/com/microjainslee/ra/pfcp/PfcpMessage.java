package com.microjainslee.ra.pfcp;

import java.util.Objects;

public record PfcpMessage(PfcpMessageType type, long seid, int sequence, byte[] payload) {
    public PfcpMessage {
        Objects.requireNonNull(type);
        payload = payload == null ? new byte[0] : payload.clone();
        if (sequence < 0 || sequence > 0xff_ffff) {
            throw new IllegalArgumentException("sequence is 24-bit");
        }
    }

    public static PfcpMessage heartbeatRequest(int sequence) {
        return new PfcpMessage(PfcpMessageType.HEARTBEAT_REQUEST, 0, sequence, new byte[0]);
    }

    public static PfcpMessage heartbeatResponse(int sequence) {
        return new PfcpMessage(PfcpMessageType.HEARTBEAT_RESPONSE, 0, sequence, new byte[0]);
    }

    public static PfcpMessage associationSetupRequest(int sequence) {
        return new PfcpMessage(PfcpMessageType.ASSOCIATION_SETUP_REQUEST, 0, sequence, new byte[0]);
    }

    public static PfcpMessage associationSetupResponse(int sequence) {
        return new PfcpMessage(PfcpMessageType.ASSOCIATION_SETUP_RESPONSE, 0, sequence, new byte[0]);
    }
}
