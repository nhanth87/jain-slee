/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.command.Ss7Command;
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
import org.restcomm.protocols.ss7.map.api.service.callhandling.MAPDialogCallHandling;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSClientID;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSClientType;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSPriority;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSQoS;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSQoSClass;
import org.restcomm.protocols.ss7.map.api.service.lsm.LocationEstimateType;
import org.restcomm.protocols.ss7.map.api.service.lsm.LocationType;
import org.restcomm.protocols.ss7.map.api.service.lsm.MAPDialogLsm;
import org.restcomm.protocols.ss7.map.api.service.lsm.ResponseTime;
import org.restcomm.protocols.ss7.map.api.service.lsm.ResponseTimeCategory;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.DomainType;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.RequestedInfo;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;

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
            RequestedInfo requested = requestedInfo(pf, cmd.requestedDomain(),
                    cmd.requestLocationInformation(), cmd.requestSubscriberState(),
                    cmd.requestCurrentLocation(), cmd.requestImei(), cmd.requestMsClassmark(),
                    cmd.requestMnpInfo(), cmd.requestEpsLocationInformation());
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
            dialog.addSendRoutingInformationRequest(
                    isdn(pf, cmd.msisdn(), "SRI msisdn"), null, null, null);
            sendOnce(dialog, cmd.dialogId(), "SRI");
        } catch (MAPException | RuntimeException e) {
            failUnsent(dialog, cmd.dialogId(), "SRI", e);
        }
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
            RequestedInfo requested = requestedInfo(pf, cmd.requestedDomain(),
                    cmd.requestLocationInformation(), cmd.requestSubscriberState(),
                    cmd.requestCurrentLocation(), cmd.requestImei(), cmd.requestMsClassmark(),
                    cmd.requestMnpInfo(), cmd.requestEpsLocationInformation());
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

            LocationType locationType = pf.createLocationType(
                    enumValue(LocationEstimateType.class, cmd.locationEstimateType(),
                            "PSL locationEstimateType"), null);
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

            dialog.addProvideSubscriberLocationRequest(
                    locationType, mlc, client, cmd.privacyOverride(), imsi, msisdn, lmsi, imei,
                    priority, qos, null, null, cmd.lcsReferenceNumber(), cmd.lcsServiceTypeId(),
                    null, null, null, null, false, null, null);
            sendOnce(dialog, cmd.dialogId(), "PSL");
        } catch (MAPException | RuntimeException e) {
            failUnsent(dialog, cmd.dialogId(), "PSL", e);
        }
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

    private RequestedInfo requestedInfo(
            MAPParameterFactory pf,
            String domain,
            boolean location,
            boolean state,
            boolean current,
            boolean imei,
            boolean classmark,
            boolean mnp,
            boolean eps) {
        DomainType requestedDomain = enumValue(DomainType.class, domain, "requestedDomain");
        return pf.createRequestedInfo(
                location, state, null, current, requestedDomain, imei, classmark, mnp, eps);
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
