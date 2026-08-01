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
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextVersion;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.MAPSmsTpduParameterFactory;
import org.restcomm.protocols.ss7.map.api.primitives.AddressNature;
import org.restcomm.protocols.ss7.map.api.primitives.AddressString;
import org.restcomm.protocols.ss7.map.api.primitives.IMSI;
import org.restcomm.protocols.ss7.map.api.primitives.ISDNAddressString;
import org.restcomm.protocols.ss7.map.api.primitives.NumberingPlan;
import org.restcomm.protocols.ss7.map.api.service.sms.MAPDialogSms;
import org.restcomm.protocols.ss7.map.api.service.sms.SM_RP_DA;
import org.restcomm.protocols.ss7.map.api.service.sms.SM_RP_OA;
import org.restcomm.protocols.ss7.map.api.service.sms.SmsSignalInfo;
import org.restcomm.protocols.ss7.map.api.smstpdu.AbsoluteTimeStamp;
import org.restcomm.protocols.ss7.map.api.smstpdu.AddressField;
import org.restcomm.protocols.ss7.map.api.smstpdu.DataCodingScheme;
import org.restcomm.protocols.ss7.map.api.smstpdu.NumberingPlanIdentification;
import org.restcomm.protocols.ss7.map.api.smstpdu.ProtocolIdentifier;
import org.restcomm.protocols.ss7.map.api.smstpdu.SmsDeliverTpdu;
import org.restcomm.protocols.ss7.map.api.smstpdu.TypeOfNumber;
import org.restcomm.protocols.ss7.map.api.smstpdu.UserData;
import org.restcomm.protocols.ss7.map.api.smstpdu.UserDataHeader;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound MAP SMS (SRI-SM + MT-ForwardSM) for OTA SMSC-GW.
 * Keeps correlation dialogId ↔ local MAP dialog id for event republish.
 */
final class MapSmsOutbound {

    private static final Logger LOG = LogManager.getLogger(MapSmsOutbound.class);

    private final MAPProvider provider;
    private final ParameterFactory sccpFactory;
    private final Map<Long, String> localToCorrelation = new ConcurrentHashMap<>();

    MapSmsOutbound(MAPProvider provider, Ss7Stack stack) {
        this.provider = provider;
        this.sccpFactory = stack.sccpProvider() == null
                ? null
                : stack.sccpProvider().getParameterFactory();
    }

    boolean send(OutboundCommand command) {
        if (provider == null || sccpFactory == null) {
            return false;
        }
        return switch (command) {
            case Ss7Command.MapSendRoutingInfoForSm sri -> {
                sendSri(sri);
                yield true;
            }
            case Ss7Command.MapMtForwardSm mt -> {
                sendMt(mt);
                yield true;
            }
            default -> false;
        };
    }

    /** Rewrite jSS7 local dialog id to SBB correlation id when known. */
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

    private void sendSri(Ss7Command.MapSendRoutingInfoForSm cmd) {
        try {
            MAPApplicationContext ac = MAPApplicationContext.getInstance(
                    MAPApplicationContextName.shortMsgGatewayContext,
                    MAPApplicationContextVersion.version3);
            SccpAddress dest = toSccp(cmd.targetAddress());
            SccpAddress orig = toSccp(cmd.localAddress());
            MAPDialogSms dialog = provider.getMAPServiceSms()
                    .createNewDialog(ac, orig, null, dest, null);
            dialog.setNetworkId(cmd.networkId());
            remember(dialog.getLocalDialogId(), cmd.dialogId());

            MAPParameterFactory pf = provider.getMAPParameterFactory();
            ISDNAddressString msisdn = pf.createISDNAddressString(
                    AddressNature.international_number, NumberingPlan.ISDN, digits(cmd.msisdn()));
            AddressString sc = pf.createAddressString(
                    AddressNature.international_number, NumberingPlan.ISDN,
                    digits(cmd.serviceCentreAddress()));

            dialog.addSendRoutingInfoForSMRequest(
                    msisdn, true, sc, null, false,
                    null, null, null, false, null, false, false, null, null, false);
            dialog.send();
            LOG.info("[ra-jss7] SRI-SM sent corr={} localDialog={} msisdn={}",
                    cmd.dialogId(), dialog.getLocalDialogId(), cmd.msisdn());
        } catch (MAPException | RuntimeException e) {
            LOG.error("[ra-jss7] SRI-SM failed corr={}: {}", cmd.dialogId(), e.toString());
            throw new IllegalStateException("MAP SRI-SM failed: " + e.getMessage(), e);
        }
    }

    private void sendMt(Ss7Command.MapMtForwardSm cmd) {
        try {
            MAPApplicationContext ac = MAPApplicationContext.getInstance(
                    MAPApplicationContextName.shortMsgMTRelayContext,
                    MAPApplicationContextVersion.version3);
            SccpAddress dest = toSccp(cmd.targetAddress());
            SccpAddress orig = toSccp(cmd.localAddress());
            MAPDialogSms dialog = provider.getMAPServiceSms()
                    .createNewDialog(ac, orig, null, dest, null);
            dialog.setNetworkId(cmd.networkId());
            remember(dialog.getLocalDialogId(), cmd.dialogId());

            MAPParameterFactory pf = provider.getMAPParameterFactory();
            MAPSmsTpduParameterFactory tpdu = provider.getMAPSmsTpduParameterFactory();

            SM_RP_DA da;
            byte[] lmsiBytes = cmd.lmsi();
            if (lmsiBytes != null && lmsiBytes.length > 0) {
                da = pf.createSM_RP_DA(pf.createLMSI(lmsiBytes));
            } else {
                IMSI imsi = pf.createIMSI(digits(cmd.imsi()));
                da = pf.createSM_RP_DA(imsi);
            }
            AddressString sc = pf.createAddressString(
                    AddressNature.international_number, NumberingPlan.ISDN,
                    digits(cmd.scAddress()));
            SM_RP_OA oa = pf.createSM_RP_OA_ServiceCentreAddressOA(sc);

            SmsSignalInfo si = buildSignalInfo(tpdu, pf, cmd);
            dialog.addMtForwardShortMessageRequest(
                    da, oa, si, false, null, null, null, false, null, null, null, null);
            dialog.send();
            LOG.info("[ra-jss7] MT-ForwardSM sent corr={} localDialog={} imsi={} lmsiLen={}",
                    cmd.dialogId(), dialog.getLocalDialogId(), cmd.imsi(),
                    lmsiBytes == null ? 0 : lmsiBytes.length);
        } catch (MAPException | RuntimeException e) {
            LOG.error("[ra-jss7] MT-ForwardSM failed corr={}: {}", cmd.dialogId(), e.toString());
            throw new IllegalStateException("MAP MT-ForwardSM failed: " + e.getMessage(), e);
        }
    }

    private SmsSignalInfo buildSignalInfo(MAPSmsTpduParameterFactory tpdu,
                                          MAPParameterFactory pf,
                                          Ss7Command.MapMtForwardSm cmd) throws MAPException {
        byte[] tpUd = cmd.tpUd() == null ? new byte[0] : cmd.tpUd();
        DataCodingScheme dcs = tpdu.createDataCodingScheme(cmd.dataCoding());
        ProtocolIdentifier pid = tpdu.createProtocolIdentifier(cmd.protocolId());

        UserDataHeader udh = null;
        byte[] payload = tpUd;
        if (cmd.udhi() && tpUd.length > 0) {
            int udhl = tpUd[0] & 0xFF;
            if (udhl > 0 && tpUd.length >= udhl + 1) {
                // UserDataHeaderImpl expects UDHL-prefixed octets (TS 23.040).
                // Passing IE body only (without UDHL) mis-parses concat IEI 0x00 as
                // UDHL=0 → empty header → UDHI cleared on the wire → sim cannot merge.
                udh = tpdu.createUserDataHeader(Arrays.copyOfRange(tpUd, 0, udhl + 1));
                payload = Arrays.copyOfRange(tpUd, udhl + 1, tpUd.length);
            }
        }

        // 8-bit binary body as ISO-8859-1 string carrier (jSS7 UserData text path)
        String body = new String(payload, StandardCharsets.ISO_8859_1);
        UserData ud = tpdu.createUserData(body, dcs, udh, StandardCharsets.ISO_8859_1);

        LocalDateTime now = LocalDateTime.now();
        AbsoluteTimeStamp ts = tpdu.createAbsoluteTimeStamp(
                now.getYear() % 100, now.getMonthValue(), now.getDayOfMonth(),
                now.getHour(), now.getMinute(), now.getSecond(), 0);

        AddressField oa = tpdu.createAddressField(
                TypeOfNumber.Alphanumeric, NumberingPlanIdentification.Unknown, "DIGICOM");

        SmsDeliverTpdu deliver = tpdu.createSmsDeliverTpdu(
                false, false, false, false, oa, pid, ts, ud);
        return pf.createSmsSignalInfo(deliver, StandardCharsets.ISO_8859_1);
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

    private static String digits(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }
}
