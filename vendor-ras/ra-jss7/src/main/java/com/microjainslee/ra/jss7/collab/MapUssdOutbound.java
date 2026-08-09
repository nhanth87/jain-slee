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
import org.restcomm.protocols.ss7.map.api.datacoding.CBSDataCodingScheme;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.primitives.USSDString;
import org.restcomm.protocols.ss7.map.api.service.supplementary.MAPDialogSupplementary;
import org.restcomm.protocols.ss7.map.datacoding.CBSDataCodingSchemeImpl;
import org.restcomm.protocols.ss7.map.primitives.USSDStringImpl;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound MAP USSD (MO reply + NI UnstructuredSS + MAP2MAP processUnstructured hop + continue/close).
 */
final class MapUssdOutbound {

    private static final Logger LOG = LogManager.getLogger(MapUssdOutbound.class);

    private final MAPProvider provider;
    private final ParameterFactory sccpFactory;
    private final Map<Long, String> localToCorrelation = new ConcurrentHashMap<>();
    private final Map<String, Long> correlationToLocal = new ConcurrentHashMap<>();

    MapUssdOutbound(MAPProvider provider, Ss7Stack stack) {
        this(provider, stack.sccpProvider() == null
                ? null
                : stack.sccpProvider().getParameterFactory());
    }

    MapUssdOutbound(MAPProvider provider, ParameterFactory sccpFactory) {
        this.provider = provider;
        this.sccpFactory = sccpFactory;
    }

    boolean send(OutboundCommand command) {
        if (provider == null) {
            return false;
        }
        return switch (command) {
            case Ss7Command.MapProcessUnstructuredSsResponse r -> {
                replyMo(r);
                yield true;
            }
            case Ss7Command.MapUnstructuredSsRequest ni -> {
                if (sccpFactory == null) {
                    yield false;
                }
                sendNi(ni);
                yield true;
            }
            case Ss7Command.MapUnstructuredSsContinue cont -> {
                continueNi(cont);
                yield true;
            }
            case Ss7Command.MapDialogClose close -> {
                closeDialog(close);
                yield true;
            }
            case Ss7Command.MapDialogAbort abort -> {
                abortDialog(abort);
                yield true;
            }
            default -> false;
        };
    }

    String correlate(Long localDialogId, String fallback) {
        if (localDialogId == null) {
            return fallback;
        }
        String corr = localToCorrelation.get(localDialogId);
        return corr != null ? corr : fallback;
    }

    void forget(Long localDialogId) {
        if (localDialogId == null) {
            return;
        }
        String corr = localToCorrelation.remove(localDialogId);
        if (corr != null) {
            correlationToLocal.remove(corr, localDialogId);
        }
    }

    void clearAll() {
        localToCorrelation.clear();
        correlationToLocal.clear();
    }

    private void replyMo(Ss7Command.MapProcessUnstructuredSsResponse cmd) {
        Long localId = resolveLocalId(cmd.dialogId());
        if (localId == null) {
            throw new IllegalArgumentException("Invalid MAP dialog id: " + cmd.dialogId());
        }
        MAPDialog raw = provider.getMAPDialog(localId);
        if (!(raw instanceof MAPDialogSupplementary supp)) {
            throw new IllegalStateException("No supplementary MAP dialog for id=" + localId);
        }
        try {
            CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(cmd.dataCoding());
            USSDString ussd = new USSDStringImpl(clip(cmd.text()), dcs, null);
            if (cmd.endDialog()) {
                supp.addProcessUnstructuredSSResponse(cmd.invokeId(), dcs, ussd);
                supp.close(false);
            } else {
                supp.addUnstructuredSSRequest(dcs, ussd, null, null);
                supp.send();
            }
            LOG.info("[ra-jss7] USSD MO reply localDialog={} end={} dcs=0x{} textLen={}",
                    localId, cmd.endDialog(), Integer.toHexString(cmd.dataCoding()),
                    clip(cmd.text()).length());
        } catch (MAPException | RuntimeException e) {
            LOG.error("[ra-jss7] USSD MO reply failed id={}: {}", localId, e.toString());
            throw new IllegalStateException("MAP USSD MO reply failed: " + e.getMessage(), e);
        }
    }

    /**
     * NI UnstructuredSS (op 60) or MAP2MAP Case 2 processUnstructured hop (op 59).
     */
    private void sendNi(Ss7Command.MapUnstructuredSsRequest cmd) {
        MAPDialogSupplementary dialog = null;
        boolean sent = false;
        try {
            MAPApplicationContext ac = MAPApplicationContext.getInstance(
                    MAPApplicationContextName.networkUnstructuredSsContext,
                    MAPApplicationContextVersion.version2);
            SccpAddress dest = toSccp(cmd.targetAddress());
            SccpAddress orig = toSccp(cmd.localAddress());
            MAPParameterFactory pf = provider.getMAPParameterFactory();
            AddressString destRef;
            if (cmd.processUnstructured()) {
                // Ethio Brook MAP2MAP: destReference = MSISDN (ISDN).
                destRef = msisdnDestReference(pf, cmd.msisdn());
            } else {
                // Classic NI: destReference = IMSI (land_mobile) when known.
                destRef = imsiDestReference(pf, cmd.imsi());
            }
            AddressString origRef = gtOrigReference(pf, cmd.localAddress());
            dialog = provider.getMAPServiceSupplementary()
                    .createNewDialog(ac, orig, origRef, dest, destRef);
            dialog.setNetworkId(cmd.networkId());
            DialogRoutePin.apply(dialog, cmd.preferredAspName(), cmd.remotePc());
            remember(dialog.getLocalDialogId(), cmd.dialogId());

            CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(cmd.dataCoding());
            USSDString ussd = new USSDStringImpl(clip(cmd.text()), dcs, null);
            ISDNAddressString msisdn = msisdnAddress(pf, cmd.msisdn());
            if (cmd.processUnstructured()) {
                dialog.addProcessUnstructuredSSRequest(dcs, ussd, null, msisdn);
            } else if (cmd.notifyOnly()) {
                dialog.addUnstructuredSSNotifyRequest(dcs, ussd, null, msisdn);
            } else {
                dialog.addUnstructuredSSRequest(dcs, ussd, null, msisdn);
            }
            dialog.send();
            sent = true;
            if (cmd.processUnstructured()) {
                LOG.info("[ra-jss7] USSD MAP2MAP hop sent corr={} localDialog={} hopGt={} msisdn={} dcs=0x{}",
                        cmd.dialogId(), dialog.getLocalDialogId(),
                        cmd.targetAddress() == null ? "" : cmd.targetAddress().globalTitle(),
                        msisdn == null ? "" : msisdn.getAddress(),
                        Integer.toHexString(cmd.dataCoding()));
            } else {
                LOG.info("[ra-jss7] USSD NI sent corr={} localDialog={} notify={} mscGt={} imsi={} msisdn={} dcs=0x{}",
                        cmd.dialogId(), dialog.getLocalDialogId(), cmd.notifyOnly(),
                        cmd.targetAddress() == null ? "" : cmd.targetAddress().globalTitle(),
                        cmd.imsi() == null ? "" : cmd.imsi(),
                        msisdn == null ? "" : msisdn.getAddress(),
                        Integer.toHexString(cmd.dataCoding()));
            }
        } catch (MAPException | RuntimeException e) {
            if (!sent && dialog != null) {
                try {
                    dialog.release();
                } catch (Throwable t) {
                    LOG.warn("[ra-jss7] release unsent USSD dialog: {}", t.toString());
                } finally {
                    forget(dialog.getLocalDialogId());
                }
            }
            LOG.error("[ra-jss7] USSD outbound failed corr={}: {}", cmd.dialogId(), e.toString());
            throw new IllegalStateException("MAP USSD outbound failed: " + e.getMessage(), e);
        }
    }

    /**
     * Same-dialog NI continue — classic {@code pushToDevice} on existing MAP dialog:
     * add Request/Notify then {@code send()} — never {@code createNewDialog}.
     */
    private void continueNi(Ss7Command.MapUnstructuredSsContinue cmd) {
        Long localId = resolveLocalId(cmd.dialogId());
        if (localId == null) {
            throw new IllegalStateException(
                    "No MAP dialog for NI continue corr=" + cmd.dialogId());
        }
        MAPDialog raw = provider.getMAPDialog(localId);
        if (!(raw instanceof MAPDialogSupplementary supp)) {
            throw new IllegalStateException(
                    "No supplementary MAP dialog for NI continue id=" + localId
                            + " corr=" + cmd.dialogId());
        }
        try {
            CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(cmd.dataCoding());
            USSDString ussd = new USSDStringImpl(clip(cmd.text()), dcs, null);
            if (cmd.notifyOnly()) {
                supp.addUnstructuredSSNotifyRequest(dcs, ussd, null, null);
            } else {
                supp.addUnstructuredSSRequest(dcs, ussd, null, null);
            }
            supp.send();
            LOG.info("[ra-jss7] USSD NI continue corr={} localDialog={} notify={} dcs=0x{} textLen={}",
                    cmd.dialogId(), localId, cmd.notifyOnly(),
                    Integer.toHexString(cmd.dataCoding()), clip(cmd.text()).length());
        } catch (MAPException | RuntimeException e) {
            LOG.error("[ra-jss7] USSD NI continue failed corr={}: {}", cmd.dialogId(), e.toString());
            throw new IllegalStateException("MAP USSD NI continue failed: " + e.getMessage(), e);
        }
    }

    private void closeDialog(Ss7Command.MapDialogClose cmd) {
        Long localId = resolveLocalId(cmd.dialogId());
        if (localId == null) {
            return;
        }
        MAPDialog d = provider.getMAPDialog(localId);
        if (d == null) {
            forget(localId);
            return;
        }
        try {
            d.close(cmd.prearrangedEnd());
            LOG.info("[ra-jss7] USSD MAP close corr={} localDialog={} prearrangedEnd={}",
                    cmd.dialogId(), localId, cmd.prearrangedEnd());
        } catch (Exception e) {
            LOG.warn("[ra-jss7] MAP close failed id={}: {}", localId, e.toString());
            try {
                d.release();
            } catch (Exception ignored) {
            }
        } finally {
            forget(localId);
        }
    }

    /** Classic getTargetReference — IMSI as land_mobile AddressString (TS 29.002). */
    private static AddressString imsiDestReference(MAPParameterFactory pf, String imsi) {
        if (pf == null || imsi == null || imsi.isBlank()) {
            return null;
        }
        String digits = digitsOnly(imsi);
        if (digits.isEmpty()) {
            return null;
        }
        return pf.createAddressString(
                AddressNature.international_number, NumberingPlan.land_mobile, digits);
    }

    /** Ethio MAP2MAP hop open destReference — subscriber MSISDN as ISDN AddressString. */
    private static AddressString msisdnDestReference(MAPParameterFactory pf, String msisdn) {
        if (pf == null || msisdn == null || msisdn.isBlank()) {
            return null;
        }
        String digits = digitsOnly(msisdn);
        if (digits.isEmpty()) {
            return null;
        }
        return pf.createAddressString(
                AddressNature.international_number, NumberingPlan.ISDN, digits);
    }

    /** Classic getUssdGwReference — local USSD GT as ISDN AddressString. */
    private static AddressString gtOrigReference(MAPParameterFactory pf, Ss7Address local) {
        if (pf == null || local == null || local.globalTitle() == null || local.globalTitle().isBlank()) {
            return null;
        }
        String digits = digitsOnly(local.globalTitle());
        if (digits.isEmpty()) {
            return null;
        }
        return pf.createAddressString(
                AddressNature.international_number, NumberingPlan.ISDN, digits);
    }

    /** Subscriber MSISDN for UnstructuredSS-Request/Notify component (ISDN E.164). */
    private static ISDNAddressString msisdnAddress(MAPParameterFactory pf, String msisdn) {
        if (pf == null || msisdn == null || msisdn.isBlank()) {
            return null;
        }
        String digits = digitsOnly(msisdn);
        if (digits.isEmpty()) {
            return null;
        }
        return pf.createISDNAddressString(
                AddressNature.international_number, NumberingPlan.ISDN, digits);
    }

    private static String digitsOnly(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }

    private void abortDialog(Ss7Command.MapDialogAbort cmd) {
        Long localId = resolveLocalId(cmd.dialogId());
        if (localId == null) {
            return;
        }
        MAPDialog d = provider.getMAPDialog(localId);
        if (d == null) {
            forget(localId);
            return;
        }
        try {
            d.abort(null);
            LOG.info("[ra-jss7] USSD MAP abort corr={} localDialog={}", cmd.dialogId(), localId);
        } catch (Exception e) {
            try {
                d.release();
            } catch (Exception ignored) {
            }
        } finally {
            forget(localId);
        }
    }

    /**
     * Resolve jSS7 local dialog id: decimal string, or correlation reverse-map (UUID corr).
     */
    Long resolveLocalId(String dialogId) {
        Long parsed = parseLocalId(dialogId);
        if (parsed != null) {
            return parsed;
        }
        if (dialogId == null || dialogId.isBlank()) {
            return null;
        }
        return correlationToLocal.get(dialogId.trim());
    }

    private void remember(Long localId, String correlation) {
        if (localId == null || correlation == null) {
            return;
        }
        Long prev = correlationToLocal.put(correlation, localId);
        if (prev != null && !prev.equals(localId)) {
            localToCorrelation.remove(prev, correlation);
        }
        localToCorrelation.put(localId, correlation);
    }

    private SccpAddress toSccp(Ss7Address a) {
        if (a == null) {
            throw new IllegalArgumentException("Ss7Address required");
        }
        NatureOfAddress na = NatureOfAddress.valueOf(a.natureOfAddress());
        if (na == null) {
            na = NatureOfAddress.INTERNATIONAL;
        }
        org.restcomm.protocols.ss7.indicator.NumberingPlan np =
                org.restcomm.protocols.ss7.indicator.NumberingPlan.valueOf(a.numberingPlan());
        if (np == null) {
            np = org.restcomm.protocols.ss7.indicator.NumberingPlan.ISDN_TELEPHONY;
        }
        GlobalTitle gt = sccpFactory.createGlobalTitle(
                a.globalTitle(), a.translationType(), np, null, na);
        int pc = a.pointCode();
        if (pc > 0) {
            return sccpFactory.createSccpAddress(
                    RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN, gt, pc, a.subSystemNumber());
        }
        return sccpFactory.createSccpAddress(
                RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE, gt, 0, a.subSystemNumber());
    }

    private static Long parseLocalId(String dialogId) {
        if (dialogId == null || dialogId.isBlank() || "?".equals(dialogId)) {
            return null;
        }
        try {
            return Long.parseLong(dialogId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String clip(String text) {
        String t = text == null ? "" : text;
        return t.length() > 160 ? t.substring(0, 160) : t;
    }
}
