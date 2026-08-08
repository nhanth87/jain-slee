/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.command;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.component.Ss7TcapComponent;

import java.util.List;

/**
 * Generic TCAP outbound command hierarchy — SBB → RA direction.
 * Each command carries raw components; the RA encodes and sends via jSS7.
 * Typed MAP SMS commands are handled by {@code MapProtocolAdapter.sendOutbound}.
 */
public sealed interface Ss7Command extends OutboundCommand, java.io.Serializable {

    String dialogId();
    Ss7Address targetAddress();

    // ── TCAP dialog primitives ───────────────────────────────

    /** Begin a new TCAP dialogue. */
    record TcapBegin(
            String dialogId, Ss7Address targetAddress, Ss7Address localAddress,
            int applicationContext, List<Ss7TcapComponent> components,
            int networkId
    ) implements Ss7Command {}

    /** Continue an existing TCAP dialogue. */
    record TcapContinue(
            String dialogId, Ss7Address targetAddress,
            List<Ss7TcapComponent> components, int networkId
    ) implements Ss7Command {}

    /** Normally end a TCAP dialogue (may include final components). */
    record TcapEnd(
            String dialogId, Ss7Address targetAddress,
            List<Ss7TcapComponent> components, int networkId
    ) implements Ss7Command {}

    /** Abort a TCAP dialogue. */
    record TcapAbort(
            String dialogId, Ss7Address targetAddress,
            int abortReason, int networkId
    ) implements Ss7Command {}

    /** Send a unidirectional TCAP message. */
    record TcapUni(
            String dialogId, Ss7Address targetAddress, Ss7Address localAddress,
            List<Ss7TcapComponent> components, int networkId
    ) implements Ss7Command {}

    // ── MAP SMS (OTA SMSC-GW) ────────────────────────────────

    /**
     * MAP sendRoutingInfoForSM toward HLR ({@code targetAddress}).
     *
     * @param dialogId              correlation id used as SLEE activity key
     * @param targetAddress         HLR SCCP address
     * @param localAddress          SMSC / SC SCCP address
     * @param msisdn                subscriber MSISDN digits
     * @param serviceCentreAddress  SC address digits in MAP AddressString
     * @param networkId             jSS7 network id
     */
    record MapSendRoutingInfoForSm(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String msisdn,
            String serviceCentreAddress,
            int networkId
    ) implements Ss7Command {}

    /**
     * Answer an inbound MAP sendRoutingInfoForSM on an existing SMS dialog.
     * {@code dialogId} is the jSS7 local dialog id (decimal string) from
     * {@code Ss7MapEvent}; {@code invokeId} is the request invoke id.
     *
     * @param imsi   IMSI digits returned to the SMSC
     * @param mscGt  serving MSC/VLR GT digits in LocationInfoWithLMSI
     * @param lmsi   optional LMSI octets ({@code null}/empty → omit)
     */
    record MapSendRoutingInfoForSmResponse(
            String dialogId,
            Ss7Address targetAddress,
            long invokeId,
            String imsi,
            String mscGt,
            byte[] lmsi,
            int networkId
    ) implements Ss7Command {
        public MapSendRoutingInfoForSmResponse(
                String dialogId, long invokeId, String imsi, String mscGt) {
            this(dialogId, Ss7Address.of("0", 6), invokeId, imsi, mscGt, null, 0);
        }

        public MapSendRoutingInfoForSmResponse(
                String dialogId, long invokeId, String imsi, String mscGt, byte[] lmsi, int networkId) {
            this(dialogId, Ss7Address.of("0", 6), invokeId, imsi, mscGt, lmsi, networkId);
        }
    }

    /**
     * MAP mt-ForwardSM toward MSC/VLR ({@code targetAddress}).
     *
     * @param dialogId     correlation id
     * @param targetAddress MSC SCCP address from SRI
     * @param localAddress  SMSC SCCP address
     * @param imsi          IMSI digits from SRI
     * @param scAddress     service centre address digits (SM_RP_OA)
     * @param tpUd          SMS TP-UD octets (UDH already included when UDHI set)
     * @param dataCoding    TP-DCS (0x04 for OTA)
     * @param protocolId    TP-PID (0x7F SIM Data Download)
     * @param udhi          TP-UDHI bit
     * @param networkId     jSS7 network id
     * @param lmsi          optional LMSI octets from SRI ({@code null}/empty → SM_RP_DA by IMSI)
     */
    record MapMtForwardSm(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String imsi,
            String scAddress,
            byte[] tpUd,
            int dataCoding,
            int protocolId,
            boolean udhi,
            int networkId,
            byte[] lmsi
    ) implements Ss7Command {
        /** Backward-compatible: no LMSI. */
        public MapMtForwardSm(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String imsi,
                String scAddress,
                byte[] tpUd,
                int dataCoding,
                int protocolId,
                boolean udhi,
                int networkId) {
            this(dialogId, targetAddress, localAddress, imsi, scAddress, tpUd,
                    dataCoding, protocolId, udhi, networkId, null);
        }
    }

    // ── MAP USSD (supplementary) ─────────────────────────────

    /**
     * Reply to an in-flight MO ProcessUnstructuredSS on an existing MAP dialog.
     * {@code dialogId} is the jSS7 local dialog id (decimal string) as published
     * on {@code Ss7MapEvent}.
     *
     * @param endDialog if true, send ProcessUnstructuredSS-Response and close;
     *                  if false, send UnstructuredSS-Request (CONTINUE menu)
     */
    record MapProcessUnstructuredSsResponse(
            String dialogId,
            Ss7Address targetAddress,
            long invokeId,
            String text,
            boolean endDialog,
            int networkId,
            int dataCoding
    ) implements Ss7Command {
        public MapProcessUnstructuredSsResponse(
                String dialogId, long invokeId, String text, boolean endDialog) {
            this(dialogId, Ss7Address.of("0", 8), invokeId, text, endDialog, 0, 0x0F);
        }

        public MapProcessUnstructuredSsResponse(
                String dialogId, long invokeId, String text, boolean endDialog, int dataCoding) {
            this(dialogId, Ss7Address.of("0", 8), invokeId, text, endDialog, 0, dataCoding);
        }
    }

    /**
     * Network-initiated UnstructuredSS-Request (or Notify) toward the MSC/VLR.
     * Uses {@code networkUnstructuredSsContext} v2.
     *
     * <p>{@code targetAddress} must be the SRI-SM {@code networkNodeNumber} (MSC/VLR GT),
     * never the subscriber MSISDN. {@code imsi} becomes MAP destination reference
     * (numbering plan land_mobile) per classic ussdgateway / 3GPP TS 29.002 table 7.3/2.
     */
    record MapUnstructuredSsRequest(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String text,
            int networkId,
            boolean notifyOnly,
            int dataCoding,
            String imsi
    ) implements Ss7Command {
        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId) {
            this(dialogId, targetAddress, localAddress, text, networkId, false, 0x0F, null);
        }

        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId,
                boolean notifyOnly) {
            this(dialogId, targetAddress, localAddress, text, networkId, notifyOnly, 0x0F, null);
        }

        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId,
                boolean notifyOnly,
                int dataCoding) {
            this(dialogId, targetAddress, localAddress, text, networkId, notifyOnly, dataCoding, null);
        }
    }

    /** Abort an existing MAP dialog by local dialog id. */
    record MapDialogAbort(
            String dialogId,
            Ss7Address targetAddress,
            int networkId
    ) implements Ss7Command {
        public MapDialogAbort(String dialogId) {
            this(dialogId, Ss7Address.of("0", 8), 0);
        }
    }
}
