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
 * Outbound MAP USSD (MO reply on existing dialog + NI UnstructuredSS).
 */
final class MapUssdOutbound {

    private static final Logger LOG = LogManager.getLogger(MapUssdOutbound.class);

    private final MAPProvider provider;
    private final ParameterFactory sccpFactory;
    private final Map<Long, String> localToCorrelation = new ConcurrentHashMap<>();

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
        if (localDialogId != null) {
            localToCorrelation.remove(localDialogId);
        }
    }

    void clearAll() {
        localToCorrelation.clear();
    }

    private void replyMo(Ss7Command.MapProcessUnstructuredSsResponse cmd) {
        Long localId = parseLocalId(cmd.dialogId());
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

    private void sendNi(Ss7Command.MapUnstructuredSsRequest cmd) {
        MAPDialogSupplementary dialog = null;
        boolean sent = false;
        try {
            MAPApplicationContext ac = MAPApplicationContext.getInstance(
                    MAPApplicationContextName.networkUnstructuredSsContext,
                    MAPApplicationContextVersion.version2);
            SccpAddress dest = toSccp(cmd.targetAddress());
            SccpAddress orig = toSccp(cmd.localAddress());
            dialog = provider.getMAPServiceSupplementary()
                    .createNewDialog(ac, orig, null, dest, null);
            dialog.setNetworkId(cmd.networkId());
            remember(dialog.getLocalDialogId(), cmd.dialogId());

            CBSDataCodingScheme dcs = new CBSDataCodingSchemeImpl(cmd.dataCoding());
            USSDString ussd = new USSDStringImpl(clip(cmd.text()), dcs, null);
            if (cmd.notifyOnly()) {
                dialog.addUnstructuredSSNotifyRequest(dcs, ussd, null, null);
            } else {
                dialog.addUnstructuredSSRequest(dcs, ussd, null, null);
            }
            dialog.send();
            sent = true;
            LOG.info("[ra-jss7] USSD NI sent corr={} localDialog={} notify={} dcs=0x{}",
                    cmd.dialogId(), dialog.getLocalDialogId(), cmd.notifyOnly(),
                    Integer.toHexString(cmd.dataCoding()));
        } catch (MAPException | RuntimeException e) {
            if (!sent && dialog != null) {
                try {
                    dialog.release();
                } catch (Throwable t) {
                    LOG.warn("[ra-jss7] release unsent NI dialog: {}", t.toString());
                } finally {
                    forget(dialog.getLocalDialogId());
                }
            }
            LOG.error("[ra-jss7] USSD NI failed corr={}: {}", cmd.dialogId(), e.toString());
            throw new IllegalStateException("MAP USSD NI failed: " + e.getMessage(), e);
        }
    }

    private void abortDialog(Ss7Command.MapDialogAbort cmd) {
        Long localId = parseLocalId(cmd.dialogId());
        if (localId == null) {
            return;
        }
        MAPDialog d = provider.getMAPDialog(localId);
        if (d == null) {
            return;
        }
        try {
            d.abort(null);
        } catch (Exception e) {
            try {
                d.release();
            } catch (Exception ignored) {
            }
        } finally {
            forget(localId);
        }
    }

    private void remember(Long localId, String correlation) {
        if (localId != null && correlation != null) {
            localToCorrelation.put(localId, correlation);
        }
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
