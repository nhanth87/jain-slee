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
            int networkId,
            String preferredAspName,
            int remotePc,
            int mapVersion
    ) implements Ss7Command {
        /** No sticky ASP pin (classic SLS among ACTIVE of N). Defaults MAP v3. */
        public MapSendRoutingInfoForSm(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String msisdn,
                String serviceCentreAddress,
                int networkId) {
            this(dialogId, targetAddress, localAddress, msisdn, serviceCentreAddress,
                    networkId, null, -1, 3);
        }

        public MapSendRoutingInfoForSm(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String msisdn,
                String serviceCentreAddress,
                int networkId,
                String preferredAspName,
                int remotePc) {
            this(dialogId, targetAddress, localAddress, msisdn, serviceCentreAddress,
                    networkId, preferredAspName, remotePc, 3);
        }
    }

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
     * @param udl           TP-UDL as the composer knows it — septets for GSM-7, octets otherwise
     *                      (TS 23.040 §9.2.3.16). {@code null} keeps the legacy text re-encode
     *                      path, which double-packs pre-packed GSM-7 bytes; pass it whenever the
     *                      caller already holds the packed octets.
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
            byte[] lmsi,
            String preferredAspName,
            int remotePc,
            Integer udl
    ) implements Ss7Command {
        /** Backward-compatible: no LMSI / no sticky ASP pin / legacy text path. */
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
                    dataCoding, protocolId, udhi, networkId, null, null, -1, null);
        }

        /** Backward-compatible: LMSI, no sticky ASP pin, legacy text path. */
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
                int networkId,
                byte[] lmsi) {
            this(dialogId, targetAddress, localAddress, imsi, scAddress, tpUd,
                    dataCoding, protocolId, udhi, networkId, lmsi, null, -1, null);
        }
    }

    /**
     * MAP MO-relay forward of an inbound short message toward a destination SMSC/MSC
     * peer over SS7 (SS7→SS7 gateway leg, TS 29.002 MO-ForwardSM relay context).
     *
     * <p>Unlike {@link MapMtForwardSm} (SMSC-composed MT), this re-sends an {@code moForwardSM}
     * primitive received on one SS7 leg onward to an SS7 peer named by {@code targetAddress}
     * (usually the tenant's SMSC GT via {@code upperGt}). It carries the original SM point-to-point
     * data; {@code smRpDaMisidn} supplies the RP-DA destination subscriber.
     *
     * @param dialogId        correlation id used as SLEE activity key (new forward leg)
     * @param targetAddress   destination SMSC/MSC SCCP address (route {@code upperGt})
     * @param localAddress    this gateway SCCP address (route {@code scGt})
     * @param smRpDaMsisdn    RP-DA destination MSISDN digits (subscriber to deliver to)
     * @param smRpOaMsisdn    RP-OA originator MSISDN digits (from the inbound MO)
     * @param smRpOaServiceCentreAddress  SC GT digits to carry as RP-OA SC on the leg
     * @param tpUd            TP-UserData (payload) octets from the inbound MO
     * @param dataCoding      TP-DCS (0=GSM7, 1=8bit, 2=UCS2)
     * @param protocolId      TP-PID
     * @param udhi            TP-UDHI flag
     * @param networkId       jSS7 network id
     * @param preferredAspName  optional sticky ASP pin ({@code null} → SLS among ACTIVE)
     * @param remotePc        optional peer PC for route pin ({@code -1} → route by GT)
     */
    record MapMoForwardSm(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String smRpDaMsisdn,
            String smRpOaMsisdn,
            String smRpOaServiceCentreAddress,
            byte[] tpUd,
            int dataCoding,
            int protocolId,
            boolean udhi,
            int networkId,
            String preferredAspName,
            int remotePc
    ) implements Ss7Command {
        /** No sticky ASP pin. */
        public MapMoForwardSm(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String smRpDaMsisdn,
                String smRpOaMsisdn,
                String smRpOaServiceCentreAddress,
                byte[] tpUd,
                int dataCoding,
                int protocolId,
                boolean udhi,
                int networkId) {
            this(dialogId, targetAddress, localAddress, smRpDaMsisdn, smRpOaMsisdn,
                    smRpOaServiceCentreAddress, tpUd, dataCoding, protocolId, udhi,
                    networkId, null, -1);
        }
    }

    /**
     * MAP reportSM-DeliveryStatus toward HLR ({@code targetAddress}) — SC reports
     * memory-full / absent / successful after MT outcome (TS 29.002).
     *
     * @param outcome  {@link org.restcomm.protocols.ss7.map.api.service.sms.SMDeliveryOutcome}
     *                 enum name ({@code absentSubscriber}, {@code memoryCapacityExceeded},
     *                 {@code successfulTransfer})
     */
    record MapReportSMDeliveryStatus(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String msisdn,
            String serviceCentreAddress,
            String outcome,
            int networkId,
            String preferredAspName,
            int remotePc
    ) implements Ss7Command {
        public MapReportSMDeliveryStatus(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String msisdn,
                String serviceCentreAddress,
                String outcome,
                int networkId) {
            this(dialogId, targetAddress, localAddress, msisdn, serviceCentreAddress,
                    outcome, networkId, null, -1);
        }
    }

    // ── MAP GMLC (mobility / call handling / LCS) ────────────

    /** MAP AnyTimeInterrogation toward the subscriber HLR. */
    record MapAtiRequest(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String msisdn,
            String gsmScfAddress,
            String requestedDomain,
            boolean requestLocationInformation,
            boolean requestSubscriberState,
            boolean requestCurrentLocation,
            boolean requestImei,
            boolean requestMsClassmark,
            boolean requestMnpInfo,
            boolean requestEpsLocationInformation,
            int networkId,
            String preferredAspName,
            int remotePc
    ) implements Ss7Command {}

    /** MAP call-handling SendRoutingInformation (locationInfoRetrievalContext v2/v3). */
    record MapSendRoutingInformation(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String msisdn,
            int networkId,
            String preferredAspName,
            int remotePc,
            int mapVersion
    ) implements Ss7Command {
        /** Defaults MAP v3 (classic first attempt). */
        public MapSendRoutingInformation(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String msisdn,
                int networkId,
                String preferredAspName,
                int remotePc) {
            this(dialogId, targetAddress, localAddress, msisdn, networkId,
                    preferredAspName, remotePc, 3);
        }
    }

    /** MAP ProvideSubscriberInfo toward a serving VLR/SGSN. */
    record MapProvideSubscriberInfo(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String imsi,
            byte[] lmsi,
            String requestedDomain,
            boolean requestLocationInformation,
            boolean requestSubscriberState,
            boolean requestCurrentLocation,
            boolean requestImei,
            boolean requestMsClassmark,
            boolean requestMnpInfo,
            boolean requestEpsLocationInformation,
            int networkId,
            String preferredAspName,
            int remotePc
    ) implements Ss7Command {}

    /** MAP SendRoutingInfoForLCS toward the subscriber HLR. */
    record MapSendRoutingInfoForLcs(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String mlcNumber,
            String imsi,
            String msisdn,
            int networkId,
            String preferredAspName,
            int remotePc
    ) implements Ss7Command {}

    /**
     * MAP ProvideSubscriberLocation toward the serving MSC/SGSN.
     * Enum-valued fields use their jSS7 enum names and are rejected when unknown.
     * {@code callSessionUnrelated} / {@code callSessionRelated} carry the TS 29.002
     * LCS-PrivacyCheck IE ({@code PrivacyCheckRelatedAction} values:
     * {@code allowedWithoutNotification}, {@code allowedWithNotification},
     * {@code allowedIfNoResponse}, {@code restrictedIfNoResponse}, {@code notAllowed}).
     * {@code callSessionUnrelated} is mandatory within the IE and {@code callSessionRelated}
     * is optional, so a blank {@code callSessionUnrelated} omits the IE entirely.
     * BER/DER encoding stays in jSS7 map-api/impl.
     */
    record MapProvideSubscriberLocation(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String locationEstimateType,
            String mlcNumber,
            String lcsClientType,
            boolean privacyOverride,
            String imsi,
            String msisdn,
            byte[] lmsi,
            String imei,
            String lcsPriority,
            Integer horizontalAccuracy,
            Integer verticalAccuracy,
            boolean verticalCoordinateRequested,
            String responseTimeCategory,
            boolean velocityRequested,
            String lcsQosClass,
            Integer lcsReferenceNumber,
            Integer lcsServiceTypeId,
            String callSessionUnrelated,
            String callSessionRelated,
            int networkId,
            String preferredAspName,
            int remotePc
    ) implements Ss7Command {}

    /** ReturnResult for an inbound SubscriberLocationReport on its existing LCS dialog. */
    record MapSubscriberLocationReportResponse(
            String dialogId,
            Ss7Address targetAddress,
            long invokeId,
            String naEsrd,
            String naEsrk,
            Integer lcsReferenceNumber,
            int networkId
    ) implements Ss7Command {}

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
     * Network-initiated UnstructuredSS-Request/Notify toward MSC, or Case 2 MAP2MAP hop.
     * Uses {@code networkUnstructuredSsContext} v2.
     *
     * <p>{@code targetAddress} = MSC {@code networkNodeNumber} (NI) or hop dest GT (Case 2) —
     * never the subscriber MSISDN as SCCP CalledParty.
     * {@code imsi} → MAP destReference land_mobile when NI knows IMSI.
     * {@code msisdn} → component MSISDN (ISDN); also MAP destReference when
     * {@code processUnstructured} is true (Ethio MAP2MAP hop opcode 59).
     * {@code processUnstructured}=false → {@code unstructuredSS-Request}/Notify (opcode 60).
     */
    record MapUnstructuredSsRequest(
            String dialogId,
            Ss7Address targetAddress,
            Ss7Address localAddress,
            String text,
            int networkId,
            boolean notifyOnly,
            int dataCoding,
            String imsi,
            String msisdn,
            boolean processUnstructured,
            String preferredAspName,
            int remotePc
    ) implements Ss7Command {
        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId) {
            this(dialogId, targetAddress, localAddress, text, networkId, false, 0x0F, null, null, false, null, -1);
        }

        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId,
                boolean notifyOnly) {
            this(dialogId, targetAddress, localAddress, text, networkId, notifyOnly, 0x0F, null, null, false, null, -1);
        }

        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId,
                boolean notifyOnly,
                int dataCoding) {
            this(dialogId, targetAddress, localAddress, text, networkId, notifyOnly, dataCoding, null, null, false, null, -1);
        }

        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId,
                boolean notifyOnly,
                int dataCoding,
                String imsi) {
            this(dialogId, targetAddress, localAddress, text, networkId, notifyOnly, dataCoding, imsi, null, false, null, -1);
        }

        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId,
                boolean notifyOnly,
                int dataCoding,
                String imsi,
                String msisdn) {
            this(dialogId, targetAddress, localAddress, text, networkId, notifyOnly, dataCoding, imsi, msisdn, false, null, -1);
        }

        public MapUnstructuredSsRequest(
                String dialogId,
                Ss7Address targetAddress,
                Ss7Address localAddress,
                String text,
                int networkId,
                boolean notifyOnly,
                int dataCoding,
                String imsi,
                String msisdn,
                boolean processUnstructured) {
            this(dialogId, targetAddress, localAddress, text, networkId, notifyOnly, dataCoding, imsi, msisdn,
                    processUnstructured, null, -1);
        }
    }

    /**
     * Continue NI UnstructuredSS-Request/Notify on an existing supplementary dialog.
     * {@code dialogId} is the correlation id (or decimal local dialog id).
     * Does <strong>not</strong> create a new MAP dialog — classic JSESSIONID multi-POST.
     */
    record MapUnstructuredSsContinue(
            String dialogId,
            Ss7Address targetAddress,
            String text,
            boolean notifyOnly,
            int dataCoding,
            int networkId
    ) implements Ss7Command {
        public MapUnstructuredSsContinue(
                String dialogId, String text, boolean notifyOnly, int dataCoding) {
            this(dialogId, Ss7Address.of("0", 8), text, notifyOnly, dataCoding, 0);
        }
    }

    /**
     * Close an existing MAP dialog ({@code MAPDialog.close(prearrangedEnd)}).
     * {@code dialogId} is correlation id or decimal local dialog id.
     */
    record MapDialogClose(
            String dialogId,
            Ss7Address targetAddress,
            boolean prearrangedEnd,
            int networkId
    ) implements Ss7Command {
        public MapDialogClose(String dialogId, boolean prearrangedEnd) {
            this(dialogId, Ss7Address.of("0", 8), prearrangedEnd, 0);
        }
    }

    /**
     * Abort an existing MAP dialog by local dialog id <em>or</em> correlation id
     * (ra-jss7 reverse-maps UUID corr → local id).
     */
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
