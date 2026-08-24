package com.microjainslee.ra.pfcp;

import java.util.List;
import java.util.Objects;

public record PfcpMessage(PfcpMessageType type, long seid, int sequence, byte[] payload) {
    public PfcpMessage {
        Objects.requireNonNull(type);
        payload = payload == null ? new byte[0] : payload.clone();
        if (sequence < 0 || sequence > 0xff_ffff) {
            throw new IllegalArgumentException("sequence is 24-bit");
        }
    }

    /** Decode the payload into TS 29.244 IEs. */
    public List<PfcpIe> ies() {
        return PfcpCodec.decodeIes(payload);
    }

    public PfcpIe first(int type) {
        return PfcpIes.find(ies(), type);
    }

    /** Recovery Time Stamp IE as a 32-bit unsigned-integer bit pattern (-1 when absent). */
    public int recoveryTimeStamp() {
        PfcpIe ie = first(PfcpIe.RECOVERY_TIME_STAMP);
        if (ie == null || ie.value().length < 4) {
            return -1;
        }
        return (ie.value()[0] & 0xff) << 24 | (ie.value()[1] & 0xff) << 16
                | (ie.value()[2] & 0xff) << 8 | (ie.value()[3] & 0xff);
    }

    public PfcpNodeId nodeId() {
        PfcpIe ie = first(PfcpIe.NODE_ID);
        return ie == null ? null : PfcpIes.parseNodeId(ie);
    }

    public static PfcpMessage heartbeatRequest(int sequence) {
        return new PfcpMessage(PfcpMessageType.HEARTBEAT_REQUEST, 0, sequence, new byte[0]);
    }

    public static PfcpMessage heartbeatRequest(int sequence, int recoverySeconds) {
        return new PfcpMessage(PfcpMessageType.HEARTBEAT_REQUEST, 0, sequence,
                PfcpCodec.encodeIes(List.of(PfcpIes.recoveryTimeStamp(recoverySeconds))));
    }

    public static PfcpMessage heartbeatResponse(int sequence) {
        return new PfcpMessage(PfcpMessageType.HEARTBEAT_RESPONSE, 0, sequence, new byte[0]);
    }

    public static PfcpMessage heartbeatResponse(int sequence, int recoverySeconds) {
        return new PfcpMessage(PfcpMessageType.HEARTBEAT_RESPONSE, 0, sequence,
                PfcpCodec.encodeIes(List.of(PfcpIes.recoveryTimeStamp(recoverySeconds))));
    }

    public static PfcpMessage associationSetupRequest(int sequence) {
        return new PfcpMessage(PfcpMessageType.ASSOCIATION_SETUP_REQUEST, 0, sequence, new byte[0]);
    }

    public static PfcpMessage associationSetupRequest(int sequence, PfcpNodeId nodeId, int recoverySeconds) {
        return new PfcpMessage(PfcpMessageType.ASSOCIATION_SETUP_REQUEST, 0, sequence,
                PfcpCodec.encodeIes(List.of(
                        PfcpIes.nodeId(nodeId), PfcpIes.recoveryTimeStamp(recoverySeconds))));
    }

    public static PfcpMessage associationSetupResponse(int sequence) {
        return new PfcpMessage(PfcpMessageType.ASSOCIATION_SETUP_RESPONSE, 0, sequence, new byte[0]);
    }

    public static PfcpMessage associationSetupResponse(int sequence, PfcpNodeId nodeId,
            int recoverySeconds, int cause) {
        return new PfcpMessage(PfcpMessageType.ASSOCIATION_SETUP_RESPONSE, 0, sequence,
                PfcpCodec.encodeIes(List.of(
                        PfcpIes.nodeId(nodeId), PfcpIes.cause(cause),
                        PfcpIes.recoveryTimeStamp(recoverySeconds))));
    }

    public static PfcpMessage sessionEstablishmentRequest(long seid, int sequence, List<PfcpIe> ies) {
        return new PfcpMessage(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, seid, sequence,
                PfcpCodec.encodeIes(ies));
    }

    public static PfcpMessage sessionModificationRequest(long seid, int sequence, List<PfcpIe> ies) {
        return new PfcpMessage(PfcpMessageType.SESSION_MODIFICATION_REQUEST, seid, sequence,
                PfcpCodec.encodeIes(ies));
    }

    public static PfcpMessage sessionDeletionRequest(long seid, int sequence, List<PfcpIe> ies) {
        return new PfcpMessage(PfcpMessageType.SESSION_DELETION_REQUEST, seid, sequence,
                PfcpCodec.encodeIes(ies));
    }

    public static PfcpMessage sessionReportRequest(long seid, int sequence, List<PfcpIe> ies) {
        return new PfcpMessage(PfcpMessageType.SESSION_REPORT_REQUEST, seid, sequence,
                PfcpCodec.encodeIes(ies));
    }

    public static PfcpMessage sessionReportResponse(long seid, int sequence, List<PfcpIe> ies) {
        return new PfcpMessage(PfcpMessageType.SESSION_REPORT_RESPONSE, seid, sequence,
                PfcpCodec.encodeIes(ies));
    }
}
