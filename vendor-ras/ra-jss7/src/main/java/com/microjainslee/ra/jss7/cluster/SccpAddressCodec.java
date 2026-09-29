/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.TcapDialogSnapshotPayload.PortableSccpAddress;

import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.NumberingPlan;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle0001;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle0010;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle0011;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle0100;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;

/**
 * The one place a jSS7 {@link SccpAddress} is flattened to / rebuilt from its
 * allow-list-clean {@link PortableSccpAddress} (ADR 0007 P, D11).
 *
 * <p>
 * Used by both the takeover snapshot and the cross-node PDU forward. Every GT
 * field is kept: after a takeover the survivor answers on the stored remote
 * address, and after a forwarded first CONTINUE the owner stores the calling
 * party as the remote address — a digits-only GT would route the reply on a
 * different translation.
 */
public final class SccpAddressCodec {

    private SccpAddressCodec() {
    }

    public static PortableSccpAddress toPortable(SccpAddress address) {
        if (address == null) {
            return null;
        }
        String ri = address.getAddressIndicator() != null && address.getAddressIndicator().getRoutingIndicator() != null
                ? address.getAddressIndicator().getRoutingIndicator().name()
                : RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN.name();
        int pc = address.getSignalingPointCode();
        int ssn = address.getSubsystemNumber();
        boolean translated = address.isTranslated();
        GlobalTitle gt = address.getGlobalTitle();
        if (gt instanceof GlobalTitle0100 g) {
            return new PortableSccpAddress(ri, pc, ssn, g.getDigits(), 4, g.getTranslationType(),
                    g.getNumberingPlan().getValue(), g.getEncodingScheme().getSchemeCode(),
                    g.getNatureOfAddress().getValue(), translated);
        }
        if (gt instanceof GlobalTitle0011 g) {
            return new PortableSccpAddress(ri, pc, ssn, g.getDigits(), 3, g.getTranslationType(),
                    g.getNumberingPlan().getValue(), g.getEncodingScheme().getSchemeCode(), -1, translated);
        }
        if (gt instanceof GlobalTitle0010 g) {
            return new PortableSccpAddress(ri, pc, ssn, g.getDigits(), 2, g.getTranslationType(), -1, -1, -1,
                    translated);
        }
        if (gt instanceof GlobalTitle0001 g) {
            return new PortableSccpAddress(ri, pc, ssn, g.getDigits(), 1, 0, -1, -1,
                    g.getNatureOfAddress().getValue(), translated);
        }
        if (gt != null) {
            throw new IllegalArgumentException("unsupported global title type: " + gt.getClass().getName());
        }
        return new PortableSccpAddress(ri, pc, ssn, null, 0, 0, -1, -1, -1, translated);
    }

    public static SccpAddress toSccp(PortableSccpAddress portable, ParameterFactory factory) {
        if (portable == null) {
            throw new IllegalArgumentException("SCCP address required");
        }
        RoutingIndicator ri;
        try {
            ri = RoutingIndicator.valueOf(portable.routingIndicator());
        } catch (RuntimeException e) {
            ri = RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN;
        }
        String digits = portable.globalTitleDigits();
        GlobalTitle gt = switch (portable.gtIndicator()) {
            case 0 -> null;
            // Legacy digits-only payload written before ADR 0007 P: best effort.
            case -1 -> digits == null || digits.isBlank() ? null : factory.createGlobalTitle(digits);
            case 1 -> factory.createGlobalTitle(digits, NatureOfAddress.valueOf(portable.natureOfAddress()));
            case 2 -> factory.createGlobalTitle(digits, portable.translationType());
            case 3 -> factory.createGlobalTitle(digits, portable.translationType(),
                    NumberingPlan.valueOf(portable.numberingPlan()),
                    factory.createEncodingScheme((byte) portable.encodingSchemeCode()));
            case 4 -> factory.createGlobalTitle(digits, portable.translationType(),
                    NumberingPlan.valueOf(portable.numberingPlan()),
                    factory.createEncodingScheme((byte) portable.encodingSchemeCode()),
                    NatureOfAddress.valueOf(portable.natureOfAddress()));
            default -> throw new IllegalArgumentException("unsupported GTI " + portable.gtIndicator());
        };
        SccpAddress address = factory.createSccpAddress(ri, gt, portable.pointCode(), portable.subsystemNumber());
        address.setTranslated(portable.translated());
        return address;
    }
}
