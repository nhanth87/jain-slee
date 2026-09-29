/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.command.Ss7Command.MtLrRequest;
import com.microjainslee.ra.jss7.transport.Ss7Stack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.IMEI;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.LMSI;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.primitives.SubscriberIdentity;
import org.restcomm.protocols.ss7.map.api.service.callhandling.InterrogationType;
import org.restcomm.protocols.ss7.map.api.service.callhandling.MAPDialogCallHandling;
import org.restcomm.protocols.ss7.map.api.primitives.GSNAddress;
import org.restcomm.protocols.ss7.map.api.primitives.GSNAddressAddressType;
import org.restcomm.protocols.ss7.map.api.service.lsm.Area;
import org.restcomm.protocols.ss7.map.api.service.lsm.AreaDefinition;
import org.restcomm.protocols.ss7.map.api.service.lsm.AreaEventInfo;
import org.restcomm.protocols.ss7.map.api.service.lsm.AreaIdentification;
import org.restcomm.protocols.ss7.map.api.service.lsm.AreaType;
import org.restcomm.protocols.ss7.map.api.service.lsm.DeferredLocationEventType;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSClientID;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSClientType;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSPrivacyCheck;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSPriority;
import org.restcomm.protocols.ss7.map.api.service.lsm.PrivacyCheckRelatedAction;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSQoS;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSQoSClass;
import org.restcomm.protocols.ss7.map.api.service.lsm.LocationEstimateType;
import org.restcomm.protocols.ss7.map.api.service.lsm.LocationType;
import org.restcomm.protocols.ss7.map.api.service.lsm.MAPDialogLsm;
import org.restcomm.protocols.ss7.map.api.service.lsm.OccurrenceInfo;
import org.restcomm.protocols.ss7.map.api.service.lsm.PeriodicLDRInfo;
import org.restcomm.protocols.ss7.map.api.service.lsm.ResponseTime;
import org.restcomm.protocols.ss7.map.api.service.lsm.ResponseTimeCategory;
import org.restcomm.protocols.ss7.map.api.service.lsm.SupportedGADShapes;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.RequestedInfo;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.RequestedNodes;
import org.restcomm.protocols.ss7.map.service.mobility.subscriberInformation.RequestedNodesImpl;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound MAP operations used by a GMLC. All new-dialog operations retain the
 * application correlation id until a terminal MAP dialog callback removes it.
 */
final class MapGmlcOutbound {

    private static final Logger LOG = LogManager.getLogger(MapGmlcOutbound.class);

    private final MAPProvider provider;
    private final ParameterFactory sccpFactory;
    private final Map<Long, String> localToCorrelation = new ConcurrentHashMap<>();

    MapGmlcOutbound(MAPProvider provider, Ss7Stack stack) {
        this(provider, stack.sccpProvider() == null
                ? null
                : stack.sccpProvider().getParameterFactory());
    }

    MapGmlcOutbound(MAPProvider provider, ParameterFactory sccpFactory) {
        this.provider = provider;
        this.sccpFactory = sccpFactory;
    }

    boolean send(OutboundCommand command) {
        if (provider == null) {
            return false;
        }
        return switch (command) {
            case Ss7Command.MapAtiRequest ati -> {
                requireDialogFactory();
                sendAti(ati);
                yield true;
            }
            case Ss7Command.MapSendRoutingInformation sri -> {
                requireDialogFactory();
                sendSri(sri);
                yield true;
            }
            case Ss7Command.MapProvideSubscriberInfo psi -> {
                requireDialogFactory();
                sendPsi(psi);
                yield true;
            }
            case Ss7Command.MapSendRoutingInfoForLcs sriLcs -> {
                requireDialogFactory();
                sendSriLcs(sriLcs);
                yield true;
            }
            case Ss7Command.MapProvideSubscriberLocation psl -> {
                requireDialogFactory();
                sendPsl(psl);
                yield true;
            }
            case Ss7Command.MapSubscriberLocationReportResponse slr -> {
                replySlr(slr);
                yield true;
            }
            default -> false;
        };
    }

    String correlate(Long localDialogId, String fallback) {
        if (localDialogId == null) {
            return fallback;
        }
        return localToCorrelation.getOrDefault(localDialogId, fallback);
    }

    void forget(Long localDialogId) {
        if (localDialogId != null) {
            localToCorrelation.remove(localDialogId);
        }
    }

    void clearAll() {
        localToCorrelation.clear();
    }

    static MAPApplicationContext applicationContextFor(Ss7Command command) {
        return switch (command) {
            case Ss7Command.MapAtiRequest _ -> context(MAPApplicationContextName.anyTimeEnquiryContext);
            case Ss7Command.MapSendRoutingInformation sri ->
                    context(MAPApplicationContextName.locationInfoRetrievalContext, sri.mapVersion());
            case Ss7Command.MapProvideSubscriberInfo _ ->
                    context(MAPApplicationContextName.subscriberInfoEnquiryContext);
            case Ss7Command.MapSendRoutingInfoForLcs _ ->
                    context(MAPApplicationContextName.locationSvcGatewayContext);
            case Ss7Command.MapProvideSubscriberLocation _ ->
                    context(MAPApplicationContextName.locationSvcEnquiryContext);
            default -> throw new IllegalArgumentException(
                    "No GMLC application context for " + command.getClass().getSimpleName());
        };
    }

    private void sendAti(Ss7Command.MapAtiRequest cmd) {
        MAPDialogMobility dialog = null;
        try {
            MAPParameterFactory pf = provider.getMAPParameterFactory();
            dialog = provider.getMAPServiceMobility().createNewDialog(
                    applicationContextFor(cmd), toSccp(cmd.localAddress()), null,
                    toSccp(cmd.targetAddress()), null);
            prepare(dialog, cmd);
            ISDNAddressString msisdn = isdn(pf, cmd.msisdn(), "ATI msisdn");
            SubscriberIdentity identity = pf.createSubscriberIdentity(msisdn);
            // RequestedInfo flags come from command/config (brute-force friendly);
            // requestedDomain omitted (null); imei/classmark/mnp stay off. The 9th
            // flag is locationInformationEPSSupported (RequestInfo
            // epsLocationInformationRequested, TS 29.002 §8.2.5.1.3) — that is what
            // makes the HLR return the E-UTRAN cell (ECGI) instead of only the legacy
            // CGI, so it follows the command instead of being forced off. 5G has no
            // EPS-style request bit: NR-CGI arrives in the same answer as
            // locationInformation5GS whenever the network offers it. The optional
            // requestedNodes BIT STRING (TS 29.271 Rel-15+) asks the HLR to interrogate
            // the MME/SGSN instead of the CS VLR — deployment-opt-in, see
            // Ss7Command.MapRequestedNodes.
            RequestedInfo requested = requestedInfo(pf, cmd.requestLocationInformation(),
                    cmd.requestSubscriberState(), cmd.requestCurrentLocation(),
                    cmd.requestEpsLocationInformation(), cmd.requestedNodes());
            ISDNAddressString gsmScf = isdn(pf, cmd.gsmScfAddress(), "ATI gsmScfAddress");
            dialog.addAnyTimeInterrogationRequest(identity, requested, gsmScf, null);
            sendOnce(dialog, cmd.dialogId(), "ATI");
        } catch (MAPException | RuntimeException e) {
            failUnsent(dialog, cmd.dialogId(), "ATI", e);
        }
    }

    private void sendSri(Ss7Command.MapSendRoutingInformation cmd) {
        MAPDialogCallHandling dialog = null;
        try {
            MAPParameterFactory pf = provider.getMAPParameterFactory();
            dialog = provider.getMAPServiceCallHandling().createNewDialog(
                    applicationContextFor(cmd), toSccp(cmd.localAddress()), null,
                    toSccp(cmd.targetAddress()), null);
            prepare(dialog, cmd);
            ISDNAddressString msisdn = isdn(pf, cmd.msisdn(), "SRI msisdn");
            // V3 requires InterrogationType + gmsc-OrGsmSCF-Address (classic GMLC oracle).
            // The 4-arg short overload leaves interrogationType null and fails encode on V3.
            if (cmd.mapVersion() <= 2) {
                dialog.addSendRoutingInformationRequest(msisdn, null, null, null);
            } else {
                ISDNAddressString gmsc = isdn(pf, cmd.localAddress().globalTitle(), "SRI gmscAddress");
                dialog.addSendRoutingInformationRequest(
                        msisdn, null, null,
                        InterrogationType.basicCall, false, null, gmsc, null,
                        null, null, null, null, false,
                        null, null, false, null, null,
                        null, false, null, false, false, false,
                        false, null, null, null, false, null);
            }
            sendOnce(dialog, cmd.dialogId(), "SRI");
        } catch (MAPException | RuntimeException e) {
            failUnsent(dialog, cmd.dialogId(), "SRI", e);
        }
    }

    /**
     * Build RequestedInfo for ATI/PSI. {@code eps} is
     * {@code epsLocationInformationRequested} (4G ECGI); {@code nodes} is the optional
     * {@code RequestedNodes} BIT STRING (TS 29.271) — {@code null} keeps it off the wire
     * exactly as before. The 13-arg factory overload is used unconditionally: with
     * {@code tadsData}/{@code servingNodeIndication}/{@code localTimeZoneRequest} false
     * it encodes byte-for-byte what the 9-arg overload does, and it is the only way to
     * carry {@code requestedNodes}.
     */
    private static RequestedInfo requestedInfo(MAPParameterFactory pf,
            boolean locationInformation, boolean subscriberState, boolean currentLocation,
            boolean eps, Ss7Command.MapRequestedNodes nodes) {
        RequestedNodes requestedNodes = null;
        if (nodes != null) {
            requestedNodes = switch (nodes) {
                case NONE -> null;
                case MME -> new RequestedNodesImpl(true, false);
                case MME_SGSN -> new RequestedNodesImpl(true, true);
            };
        }
        return pf.createRequestedInfo(locationInformation, subscriberState, null,
                currentLocation, null, false, false, false, eps, false, requestedNodes,
                false, false);
    }

    private void sendPsi(Ss7Command.MapProvideSubscriberInfo cmd) {
        MAPDialogMobility dialog = null;
        try {
            MAPParameterFactory pf = provider.getMAPParameterFactory();
            dialog = provider.getMAPServiceMobility().createNewDialog(
                    applicationContextFor(cmd), toSccp(cmd.localAddress()), null,
                    toSccp(cmd.targetAddress()), null);
            prepare(dialog, cmd);
            IMSI imsi = pf.createIMSI(digitsRequired(cmd.imsi(), "PSI imsi"));
            LMSI lmsi = bytesPresent(cmd.lmsi()) ? pf.createLMSI(cmd.lmsi().clone()) : null;
            // Same policy as ATI: flags from command/config, no requestedDomain, no
            // imei/classmark/mnp; epsLocationInformationRequested follows the command
            // so PSI can also return the E-UTRAN cell (ECGI).
            RequestedInfo requested = requestedInfo(pf, cmd.requestLocationInformation(),
                    cmd.requestSubscriberState(), cmd.requestCurrentLocation(),
                    cmd.requestEpsLocationInformation(), cmd.requestedNodes());
            dialog.addProvideSubscriberInfoRequest(imsi, lmsi, requested, null, null);
            sendOnce(dialog, cmd.dialogId(), "PSI");
        } catch (MAPException | RuntimeException e) {
            failUnsent(dialog, cmd.dialogId(), "PSI", e);
        }
    }

    private void sendSriLcs(Ss7Command.MapSendRoutingInfoForLcs cmd) {
        MAPDialogLsm dialog = null;
        try {
            MAPParameterFactory pf = provider.getMAPParameterFactory();
            dialog = provider.getMAPServiceLsm().createNewDialog(
                    applicationContextFor(cmd), toSccp(cmd.localAddress()), null,
                    toSccp(cmd.targetAddress()), null);
            prepare(dialog, cmd);
            SubscriberIdentity identity = subscriberIdentity(pf, cmd.imsi(), cmd.msisdn(), "SRI-LCS");
            dialog.addSendRoutingInfoForLCSRequest(
                    isdn(pf, cmd.mlcNumber(), "SRI-LCS mlcNumber"), identity, null);
            sendOnce(dialog, cmd.dialogId(), "SRI-LCS");
        } catch (MAPException | RuntimeException e) {
            failUnsent(dialog, cmd.dialogId(), "SRI-LCS", e);
        }
    }

    private void sendPsl(Ss7Command.MapProvideSubscriberLocation cmd) {
        MAPDialogLsm dialog = null;
        try {
            MAPParameterFactory pf = provider.getMAPParameterFactory();
            dialog = provider.getMAPServiceLsm().createNewDialog(
                    applicationContextFor(cmd), toSccp(cmd.localAddress()), null,
                    toSccp(cmd.targetAddress()), null);
            prepare(dialog, cmd);

            MtLrRequest mtLr = cmd.mtLr();
            // Deferred MT-LR: LocationType carries the DeferredLocationEventType, the
            // event-specific IE (PeriodicLDRInfo / AreaEventInfo) is filled, and the
            // H-GMLC address is mandatory (TS 29.002 §8.5.3). A deferred request gets no
            // immediate answer — the network reports the event later via SLR.
            // TS 29.002 LocationEstimateType.activateDeferredLocation(3) arms the
            // procedure; the trigger decides it, so the command's estimate text is not
            // parsed for a deferred request (currentLocation would ask for a fix now).
            DeferredLocationEventType deferred = deferredType(pf, mtLr);
            LocationType locationType = deferred != null
                    ? pf.createLocationType(LocationEstimateType.activateDeferredLocation, deferred)
                    : pf.createLocationType(enumValue(LocationEstimateType.class,
                            cmd.locationEstimateType(), "PSL locationEstimateType"), null);
            ISDNAddressString mlc = isdn(pf, cmd.mlcNumber(), "PSL mlcNumber");
            LCSClientID client = pf.createLCSClientID(
                    enumValue(LCSClientType.class, cmd.lcsClientType(), "PSL lcsClientType"),
                    null, null, null, null, null, null);
            IMSI imsi = blank(cmd.imsi()) ? null : pf.createIMSI(digitsRequired(cmd.imsi(), "PSL imsi"));
            ISDNAddressString msisdn = blank(cmd.msisdn())
                    ? null : isdn(pf, cmd.msisdn(), "PSL msisdn");
            requireOneIdentity(imsi, msisdn, "PSL");
            LMSI lmsi = bytesPresent(cmd.lmsi()) ? pf.createLMSI(cmd.lmsi().clone()) : null;
            IMEI imei = blank(cmd.imei()) ? null : pf.createIMEI(digitsRequired(cmd.imei(), "PSL imei"));
            LCSPriority priority = blank(cmd.lcsPriority()) ? null
                    : enumValue(LCSPriority.class, cmd.lcsPriority(), "PSL lcsPriority");
            LCSQoS qos = qos(pf, cmd);
            LCSPrivacyCheck privacyCheck = privacyCheck(pf, cmd);
            Integer lcsRef = cmd.lcsReferenceNumber();
            if (lcsRef != null && (lcsRef < 0 || lcsRef > 255)) {
                // LCS-ReferenceNumber is OCTET STRING (SIZE(1)): a wider value would be
                // truncated on the wire and the later SLR could never correlate.
                throw new IllegalArgumentException(
                        "PSL lcsReferenceNumber must be 0..255, got " + lcsRef);
            }
            PeriodicLDRInfo periodicInfo = periodicInfo(pf, mtLr);
            AreaEventInfo areaEventInfo = areaEventInfo(pf, mtLr);
            GSNAddress hgmlc = hgmlc(pf, mtLr);
            SupportedGADShapes shapes = gadShapes(pf, mtLr == null ? null : mtLr.shape());

            dialog.addProvideSubscriberLocationRequest(
                    locationType, mlc, client, cmd.privacyOverride(), imsi, msisdn, lmsi, imei,
                    priority, qos, null, shapes, cmd.lcsReferenceNumber(), cmd.lcsServiceTypeId(),
                    null, privacyCheck, areaEventInfo, hgmlc,
                    mtLr != null && mtLr.shortCircuit(), periodicInfo, null);
            sendOnce(dialog, cmd.dialogId(), "PSL");
        } catch (MAPException | RuntimeException e) {
            failUnsent(dialog, cmd.dialogId(), "PSL", e);
        }
    }

    /**
     * {@code DeferredLocationEventType} bit set of an MT-LR trigger, or null for an
     * immediate request. jSS7 takes the five bits positionally in the TS 29.002 order
     * {@code msAvailable, enteringIntoArea, leavingFromArea, beingInsideArea,
     * periodicLDR}.
     */
    private static DeferredLocationEventType deferredType(MAPParameterFactory pf,
            Ss7Command.MtLrRequest mtLr) {
        if (mtLr == null || blank(mtLr.type())) {
            return null;
        }
        String type = mtLr.type();
        String occurrence = blank(mtLr.occurrence()) ? null : mtLr.occurrence();
        boolean area = type.startsWith("area");
        boolean msAvailable = type.equals("ueAvailable");
        boolean entering = type.equals("areaEntering")
                || (type.equals("areaEvent") && "entering".equals(occurrence));
        boolean leaving = type.equals("areaLeaving")
                || (type.equals("areaEvent") && "leaving".equals(occurrence));
        boolean inside = type.equals("areaInside")
                || (type.equals("areaEvent") && "inside".equals(occurrence));
        boolean periodic = type.equals("periodic");
        if (!msAvailable && !entering && !leaving && !inside && !periodic) {
            throw new IllegalArgumentException("Unsupported MT-LR trigger type: " + type);
        }
        if (area && (mtLr.areaId() == null || mtLr.areaId().isBlank())) {
            throw new IllegalArgumentException("MT-LR area trigger needs areaId hex digits");
        }
        LOG.info("[ra-jss7] PSL deferred MT-LR type={} entering={} leaving={} inside={} periodic={}",
                type, entering, leaving, inside, periodic);
        return pf.createDeferredLocationEventType(msAvailable, entering, leaving, inside, periodic);
    }

    /**
     * {@code PeriodicLDRInfo} (TS 29.002 §8.5.4): {@code reportingInterval} is the gap
     * between reports in seconds and {@code reportingAmount} is the NUMBER of reports
     * (1..8 639 999), not a duration. The GMLC API speaks interval + total duration, so
     * the amount is {@code duration / interval} (at least one report); TS 29.002 caps
     * {@code reportingAmount x reportingInterval} at 8 639 999 s (99 days) for OMA
     * MLP/RLP compatibility. {@code reportingOptionMilliseconds} (Rel-15) stays absent.
     */
    private static PeriodicLDRInfo periodicInfo(MAPParameterFactory pf, Ss7Command.MtLrRequest mtLr) {
        if (mtLr == null || !"periodic".equals(mtLr.type())) {
            return null;
        }
        Integer interval = mtLr.intervalSeconds();
        if (interval == null || interval <= 0) {
            throw new IllegalArgumentException("periodic MT-LR needs intervalSeconds");
        }
        Integer duration = mtLr.durationSeconds();
        if (duration == null || duration <= 0) {
            throw new IllegalArgumentException(
                    "periodic MT-LR needs durationSeconds (PeriodicLDRInfo reportingAmount is mandatory)");
        }
        int amount = Math.max(1, duration / interval);
        long product = (long) interval * amount;
        if (product > 8_639_999L) {
            throw new IllegalArgumentException(
                    "periodic MT-LR reportingAmount x reportingInterval must be <= 8639999 s, got "
                            + product);
        }
        return pf.createPeriodicLDRInfo(amount, interval, null);
    }

    /**
     * {@code AreaEventInfo} (TS 29.002 §8.5.3): the area octets come from
     * {@code AreaIdentification} and are carried verbatim, so the caller supplies them
     * as hex. {@code occurrenceInfo} is one-time for the edge triggers and multiple-time
     * for "inside area", which the network has to keep re-evaluating. {@code intervalTime}
     * stays absent — it is the "report at most every N s" throttle.
     */
    private static AreaEventInfo areaEventInfo(MAPParameterFactory pf, Ss7Command.MtLrRequest mtLr) {
        if (mtLr == null || blank(mtLr.type()) || !mtLr.type().startsWith("area")) {
            return null;
        }
        AreaType areaType = enumValue(AreaType.class, mtLr.areaType(), "PSL areaType");
        String hex = mtLr.areaId() == null ? "" : mtLr.areaId().trim();
        if (hex.isEmpty() || hex.length() % 2 != 0 || !hex.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException(
                    "PSL areaId must be an even number of hex digits (AreaIdentification octets)");
        }
        AreaIdentification id = pf.createAreaIdentification(hexToBytes(hex));
        Area area = pf.createArea(areaType, id);
        AreaDefinition definition = pf.createAreaDefinition(new ArrayList<>(List.of(area)));
        boolean inside = "areaInside".equals(mtLr.type())
                || ("areaEvent".equals(mtLr.type()) && "inside".equals(mtLr.occurrence()));
        OccurrenceInfo occurrence = inside
                ? OccurrenceInfo.multipleTimeEvent
                : OccurrenceInfo.oneTimeEvent;
        return pf.createAreaEventInfo(definition, occurrence, null);
    }

    /**
     * H-GMLC address: mandatory for a deferred MT-LR. TS 29.002 carries it as
     * {@code h-gmlc-Address [6] GSN-Address}, {@code OCTET STRING (SIZE (5..17))}: one
     * octet of address type (2 bits, 0 = IPv4 / 1 = IPv6) + address length (6 bits),
     * then the 4 or 16 address octets — an IP address of the H-GMLC, not an E.164
     * number (the SS7 return path for the later SLR is the {@code mlc-Number}).
     */
    private static GSNAddress hgmlc(MAPParameterFactory pf, Ss7Command.MtLrRequest mtLr)
            throws MAPException {
        if (mtLr == null || blank(mtLr.type())) {
            return null;
        }
        if (blank(mtLr.hgmlcAddress())) {
            throw new IllegalArgumentException(
                    "deferred MT-LR requires an H-GMLC IP address (hgmlcAddress) for the later report");
        }
        byte[] ip = ipLiteral(mtLr.hgmlcAddress().trim());
        return pf.createGSNAddress(
                ip.length == 4 ? GSNAddressAddressType.IPv4 : GSNAddressAddressType.IPv6, ip);
    }

    /** IPv4/IPv6 literal to octets; never resolves a host name (no DNS on the MAP path). */
    static byte[] ipLiteral(String text) {
        try {
            return java.net.InetAddress.ofLiteral(text).getAddress();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "hgmlcAddress must be an IPv4/IPv6 literal (GSN-Address), got: " + text, e);
        }
    }

    /** {@code SupportedGADShapes} bit set; null keeps the network default (ellipsoid point). */
    private static SupportedGADShapes gadShapes(MAPParameterFactory pf, String shape) {
        if (blank(shape)) {
            return null;
        }
        return switch (shape.trim()) {
            case "ellipsoidPoint" -> pf.createSupportedGADShapes(true, false, false, false, false, false, false);
            case "ellipsoidPointWithUncertainty" -> pf.createSupportedGADShapes(false, true, false, false, false, false, false);
            case "ellipsoidArc" -> pf.createSupportedGADShapes(false, false, false, false, false, false, true);
            default -> throw new IllegalArgumentException("Unsupported PSL GAD shape: " + shape);
        };
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private void replySlr(Ss7Command.MapSubscriberLocationReportResponse cmd) {
        Long localId = parseLocalId(cmd.dialogId());
        if (localId == null) {
            throw new IllegalArgumentException("Invalid MAP SLR dialog id: " + cmd.dialogId());
        }
        MAPDialog raw = provider.getMAPDialog(localId);
        if (!(raw instanceof MAPDialogLsm dialog)) {
            throw new IllegalStateException("No LCS MAP dialog for id=" + localId);
        }
        try {
            MAPParameterFactory pf = provider.getMAPParameterFactory();
            ISDNAddressString naEsrd = blank(cmd.naEsrd()) ? null : isdn(pf, cmd.naEsrd(), "SLR naEsrd");
            ISDNAddressString naEsrk = blank(cmd.naEsrk()) ? null : isdn(pf, cmd.naEsrk(), "SLR naEsrk");
            dialog.addSubscriberLocationReportResponse(
                    cmd.invokeId(), naEsrd, naEsrk, null, null, false, null,
                    cmd.lcsReferenceNumber());
            dialog.close(false);
            LOG.info("[ra-jss7] SLR response localDialog={} invokeId={}", localId, cmd.invokeId());
        } catch (MAPException | RuntimeException e) {
            LOG.error("[ra-jss7] SLR response failed id={}: {}", localId, e.toString());
            try {
                dialog.release();
            } catch (Throwable releaseFailure) {
                LOG.warn("[ra-jss7] release failed SLR dialog id={}: {}",
                        localId, releaseFailure.toString());
            } finally {
                forget(localId);
            }
            throw new IllegalStateException("MAP SLR response failed: " + e.getMessage(), e);
        }
    }

    private void prepare(MAPDialog dialog, Ss7Command command) {
        dialog.setNetworkId(networkId(command));
        DialogRoutePin.apply(dialog, preferredAsp(command), remotePc(command));
        remember(dialog.getLocalDialogId(), command.dialogId());
    }

    private void sendOnce(MAPDialog dialog, String correlation, String operation) throws MAPException {
        dialog.send();
        LOG.info("[ra-jss7] {} sent corr={} localDialog={}",
                operation, correlation, dialog.getLocalDialogId());
    }

    private void failUnsent(MAPDialog dialog, String correlation, String operation, Exception cause) {
        releaseUnsent(dialog, correlation);
        LOG.error("[ra-jss7] {} failed corr={}: {}", operation, correlation, cause.toString());
        throw new IllegalStateException("MAP " + operation + " failed: " + cause.getMessage(), cause);
    }

    private void releaseUnsent(MAPDialog dialog, String correlation) {
        if (dialog == null) {
            return;
        }
        Long localId = dialog.getLocalDialogId();
        try {
            dialog.release();
        } catch (Throwable t) {
            LOG.warn("[ra-jss7] release unsent GMLC dialog failed corr={} localDialog={}: {}",
                    correlation, localId, t.toString());
        } finally {
            forget(localId);
        }
    }

    private LCSQoS qos(MAPParameterFactory pf, Ss7Command.MapProvideSubscriberLocation cmd) {
        if (cmd.horizontalAccuracy() == null && cmd.verticalAccuracy() == null
                && blank(cmd.responseTimeCategory()) && blank(cmd.lcsQosClass())
                && !cmd.verticalCoordinateRequested() && !cmd.velocityRequested()) {
            return null;
        }
        ResponseTime responseTime = blank(cmd.responseTimeCategory()) ? null
                : pf.createResponseTime(enumValue(
                        ResponseTimeCategory.class, cmd.responseTimeCategory(),
                        "PSL responseTimeCategory"));
        LCSQoSClass qosClass = blank(cmd.lcsQosClass()) ? null
                : enumValue(LCSQoSClass.class, cmd.lcsQosClass(), "PSL lcsQosClass");
        return pf.createLCSQoS(
                cmd.horizontalAccuracy(), cmd.verticalAccuracy(),
                cmd.verticalCoordinateRequested(), responseTime, null,
                cmd.velocityRequested(), qosClass);
    }

    /**
     * TS 29.002 LCS-PrivacyCheck: {@code callSessionUnrelated} is mandatory and
     * {@code callSessionRelated} optional, so the IE is omitted unless at least
     * {@code callSessionUnrelated} is present. BER/DER encoding lives in jSS7's
     * {@code LCSPrivacyCheckImpl}; we only build the typed parameter here.
     */
    private LCSPrivacyCheck privacyCheck(MAPParameterFactory pf, Ss7Command.MapProvideSubscriberLocation cmd) {
        if (blank(cmd.callSessionUnrelated())) {
            return null;
        }
        PrivacyCheckRelatedAction unrelated = enumValue(
                PrivacyCheckRelatedAction.class, cmd.callSessionUnrelated(),
                "PSL callSessionUnrelated");
        PrivacyCheckRelatedAction related = blank(cmd.callSessionRelated()) ? null
                : enumValue(PrivacyCheckRelatedAction.class, cmd.callSessionRelated(),
                        "PSL callSessionRelated");
        return pf.createLCSPrivacyCheck(unrelated, related);
    }

    private SubscriberIdentity subscriberIdentity(
            MAPParameterFactory pf, String imsiValue, String msisdnValue, String operation) {
        boolean hasImsi = !blank(imsiValue);
        boolean hasMsisdn = !blank(msisdnValue);
        if (hasImsi == hasMsisdn) {
            throw new IllegalArgumentException(
                    operation + " requires exactly one of imsi or msisdn");
        }
        return hasImsi
                ? pf.createSubscriberIdentity(pf.createIMSI(digitsRequired(imsiValue, operation + " imsi")))
                : pf.createSubscriberIdentity(isdn(pf, msisdnValue, operation + " msisdn"));
    }

    private static void requireOneIdentity(IMSI imsi, ISDNAddressString msisdn, String operation) {
        if (imsi == null && msisdn == null) {
            throw new IllegalArgumentException(operation + " requires imsi or msisdn");
        }
    }

    private ISDNAddressString isdn(MAPParameterFactory pf, String value, String field) {
        return pf.createISDNAddressString(
                AddressNature.international_number, NumberingPlan.ISDN,
                digitsRequired(value, field));
    }

    private SccpAddress toSccp(Ss7Address address) {
        if (address == null) {
            throw new IllegalArgumentException("Ss7Address required");
        }
        String gtDigits = digitsRequired(address.globalTitle(), "SCCP globalTitle");
        if (address.subSystemNumber() <= 0) {
            throw new IllegalArgumentException("SCCP subSystemNumber must be positive");
        }
        NatureOfAddress nature = NatureOfAddress.valueOf(address.natureOfAddress());
        org.restcomm.protocols.ss7.indicator.NumberingPlan plan =
                org.restcomm.protocols.ss7.indicator.NumberingPlan.valueOf(address.numberingPlan());
        if (nature == null || plan == null) {
            throw new IllegalArgumentException("Unsupported SCCP addressing indicators");
        }
        GlobalTitle gt = sccpFactory.createGlobalTitle(
                gtDigits, address.translationType(), plan, null, nature);
        if (gt == null) {
            throw new IllegalArgumentException("Unable to create SCCP global title");
        }
        if (address.pointCode() > 0) {
            SccpAddress result = sccpFactory.createSccpAddress(
                    RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN,
                    gt, address.pointCode(), address.subSystemNumber());
            if (result == null) {
                throw new IllegalArgumentException("Unable to create SCCP DPC/SSN address");
            }
            return result;
        }
        SccpAddress result = sccpFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                gt, 0, address.subSystemNumber());
        if (result == null) {
            throw new IllegalArgumentException("Unable to create SCCP global-title address");
        }
        return result;
    }

    private void requireDialogFactory() {
        if (sccpFactory == null) {
            throw new IllegalStateException("SCCP parameter factory unavailable");
        }
    }

    private void remember(Long localId, String correlation) {
        if (localId == null) {
            throw new IllegalStateException("jSS7 created MAP dialog without local id");
        }
        if (blank(correlation)) {
            throw new IllegalArgumentException("MAP correlation dialogId required");
        }
        localToCorrelation.put(localId, correlation.trim());
    }

    private static MAPApplicationContext context(MAPApplicationContextName name) {
        return context(name, 3);
    }

    private static MAPApplicationContext context(MAPApplicationContextName name, int mapVersion) {
        MAPApplicationContextVersion version = switch (mapVersion) {
            case 2 -> MAPApplicationContextVersion.version2;
            case 3 -> MAPApplicationContextVersion.version3;
            default -> throw new IllegalArgumentException(
                    "MAP version must be 2 or 3, got " + mapVersion);
        };
        MAPApplicationContext context = MAPApplicationContext.getInstance(name, version);
        if (context == null) {
            throw new IllegalStateException(
                    "Unsupported MAP application context " + name + " v" + mapVersion);
        }
        return context;
    }

    private static int networkId(Ss7Command command) {
        return switch (command) {
            case Ss7Command.MapAtiRequest c -> c.networkId();
            case Ss7Command.MapSendRoutingInformation c -> c.networkId();
            case Ss7Command.MapProvideSubscriberInfo c -> c.networkId();
            case Ss7Command.MapSendRoutingInfoForLcs c -> c.networkId();
            case Ss7Command.MapProvideSubscriberLocation c -> c.networkId();
            default -> throw new IllegalArgumentException("Not a GMLC dialog-creating command");
        };
    }

    private static String preferredAsp(Ss7Command command) {
        return switch (command) {
            case Ss7Command.MapAtiRequest c -> c.preferredAspName();
            case Ss7Command.MapSendRoutingInformation c -> c.preferredAspName();
            case Ss7Command.MapProvideSubscriberInfo c -> c.preferredAspName();
            case Ss7Command.MapSendRoutingInfoForLcs c -> c.preferredAspName();
            case Ss7Command.MapProvideSubscriberLocation c -> c.preferredAspName();
            default -> null;
        };
    }

    private static int remotePc(Ss7Command command) {
        return switch (command) {
            case Ss7Command.MapAtiRequest c -> c.remotePc();
            case Ss7Command.MapSendRoutingInformation c -> c.remotePc();
            case Ss7Command.MapProvideSubscriberInfo c -> c.remotePc();
            case Ss7Command.MapSendRoutingInfoForLcs c -> c.remotePc();
            case Ss7Command.MapProvideSubscriberLocation c -> c.remotePc();
            default -> -1;
        };
    }

    private static Long parseLocalId(String dialogId) {
        if (blank(dialogId)) {
            return null;
        }
        try {
            return Long.parseLong(dialogId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String digitsRequired(String value, String field) {
        if (blank(value)) {
            throw new IllegalArgumentException(field + " required");
        }
        String candidate = value.trim();
        if (candidate.startsWith("+")) {
            candidate = candidate.substring(1);
        }
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException(field + " required");
        }
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(field + " must contain only digits");
            }
        }
        return candidate;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        if (blank(value)) {
            throw new IllegalArgumentException(field + " required");
        }
        try {
            return Enum.valueOf(type, value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + " has unsupported value: " + value, e);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean bytesPresent(byte[] value) {
        return value != null && value.length > 0;
    }
}
