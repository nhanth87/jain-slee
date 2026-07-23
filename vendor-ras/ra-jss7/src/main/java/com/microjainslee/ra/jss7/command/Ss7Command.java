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
public sealed interface Ss7Command extends OutboundCommand {

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
            int networkId
    ) implements Ss7Command {}
}
