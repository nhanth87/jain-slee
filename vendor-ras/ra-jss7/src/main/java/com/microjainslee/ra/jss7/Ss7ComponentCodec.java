/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import com.microjainslee.ra.jss7.component.Ss7TcapComponent;

import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.NumberingPlan;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.BCDEvenEncodingScheme;
import org.restcomm.protocols.ss7.sccp.impl.parameter.SccpAddressImpl;
import org.restcomm.protocols.ss7.sccp.parameter.EncodingScheme;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.ComponentPrimitiveFactory;
import org.restcomm.protocols.ss7.tcap.asn.comp.Component;
import org.restcomm.protocols.ss7.tcap.asn.comp.ErrorCode;
import org.restcomm.protocols.ss7.tcap.asn.comp.Invoke;
import org.restcomm.protocols.ss7.tcap.asn.comp.OperationCode;
import org.restcomm.protocols.ss7.tcap.asn.comp.Parameter;
import org.restcomm.protocols.ss7.tcap.asn.comp.Problem;
import org.restcomm.protocols.ss7.tcap.asn.comp.ProblemType;
import org.restcomm.protocols.ss7.tcap.asn.comp.Reject;
import org.restcomm.protocols.ss7.tcap.asn.comp.Return;
import org.restcomm.protocols.ss7.tcap.asn.comp.ReturnError;
import org.restcomm.protocols.ss7.tcap.asn.comp.ReturnResult;
import org.restcomm.protocols.ss7.tcap.asn.comp.ReturnResultLast;

/**
 * Stateless codec — converts between micro-jainslee domain types
 * ({@link Ss7Address}, {@link Ss7TcapComponent}) and jSS7 wire types
 * ({@link SccpAddress}, {@link Component}).
 *
 * <p>Uses the jSS7 j25 {@link ComponentPrimitiveFactory} for all component
 * creation. No reflection, no deprecated constructors, no
 * {@code OperationCodeImpl}.</p>
 */
final class Ss7ComponentCodec {

    private Ss7ComponentCodec() { }

    // ── Ss7Address → SccpAddress ──────────────────────────────

    static SccpAddress toSccpAddress(Ss7Address a) {
        if (a == null) return null;
        var fact = new org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl();
        EncodingScheme ec = new BCDEvenEncodingScheme();
        NumberingPlan np = switch (a.numberingPlan()) {
            case 1 -> NumberingPlan.ISDN_TELEPHONY;
            case 2 -> NumberingPlan.GENERIC;
            case 5 -> NumberingPlan.DATA;
            default -> NumberingPlan.ISDN_TELEPHONY;
        };
        NatureOfAddress noa = switch (a.natureOfAddress()) {
            case 1 -> NatureOfAddress.SUBSCRIBER;
            case 3 -> NatureOfAddress.NATIONAL;
            case 4 -> NatureOfAddress.INTERNATIONAL;
            default -> NatureOfAddress.INTERNATIONAL;
        };
        GlobalTitle gt = fact.createGlobalTitle(
                a.globalTitle() == null ? "" : a.globalTitle(),
                a.translationType(), np, ec, noa);
        return new SccpAddressImpl(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                gt, a.pointCode(), a.subSystemNumber());
    }

    // ── Ss7TcapComponent → jSS7 Component ─────────────────────

    static Component toJss7Component(Ss7TcapComponent c, ComponentPrimitiveFactory cpf) {
        return switch (c) {
            case Ss7TcapComponent.Invoke inv -> encodeInvoke(inv, cpf);
            case Ss7TcapComponent.ReturnResult rr -> encodeReturnResult(rr, cpf);
            case Ss7TcapComponent.ReturnError re -> encodeReturnError(re, cpf);
            case Ss7TcapComponent.Reject rj -> encodeReject(rj, cpf);
        };
    }

    // ── private encode helpers ─────────────────────────────────

    private static Invoke encodeInvoke(Ss7TcapComponent.Invoke inv, ComponentPrimitiveFactory cpf) {
        Invoke jInv = cpf.createTCInvokeRequest();
        jInv.setInvokeId(inv.invokeId());
        OperationCode op = cpf.createOperationCode();
        op.setLocalOperationCode((long) inv.operationCode());
        jInv.setOperationCode(op);
        if (inv.parameter() != null && inv.parameter().length > 0) {
            Parameter p = cpf.createParameter();
            p.setData(inv.parameter());
            jInv.setParameter(p);
        }
        jInv.setTimeout(inv.timeout());
        return jInv;
    }

    private static Component encodeReturnResult(Ss7TcapComponent.ReturnResult rr, ComponentPrimitiveFactory cpf) {
        if (rr.isLastComponent()) {
            ReturnResultLast jRrl = cpf.createTCResultLastRequest();
            jRrl.setInvokeId(rr.invokeId());
            setReturnOpAndParam(jRrl, rr.operationCode(), rr.parameter(), cpf);
            return jRrl;
        } else {
            ReturnResult jRr = cpf.createTCResultRequest();
            jRr.setInvokeId(rr.invokeId());
            setReturnOpAndParam(jRr, rr.operationCode(), rr.parameter(), cpf);
            return jRr;
        }
    }

    private static void setReturnOpAndParam(Return r, int opCode, byte[] param, ComponentPrimitiveFactory cpf) {
        OperationCode op = cpf.createOperationCode();
        op.setLocalOperationCode((long) opCode);
        r.setOperationCode(op);
        if (param != null && param.length > 0) {
            Parameter p = cpf.createParameter();
            p.setData(param);
            r.setParameter(p);
        }
    }

    private static ReturnError encodeReturnError(Ss7TcapComponent.ReturnError re, ComponentPrimitiveFactory cpf) {
        ReturnError jRe = cpf.createTCReturnErrorRequest();
        jRe.setInvokeId(re.invokeId());
        ErrorCode ec = cpf.createErrorCode();
        ec.setLocalErrorCode((long) re.errorCode());
        jRe.setErrorCode(ec);
        if (re.parameter() != null && re.parameter().length > 0) {
            Parameter p = cpf.createParameter();
            p.setData(re.parameter());
            jRe.setParameter(p);
        }
        return jRe;
    }

    private static Reject encodeReject(Ss7TcapComponent.Reject rj, ComponentPrimitiveFactory cpf) {
        Reject jRj = cpf.createTCRejectRequest();
        jRj.setInvokeId(rj.invokeId());
        Problem pb = cpf.createProblem(
                rj.isLocalOriginated() ? ProblemType.Invoke : ProblemType.ReturnResult);
        jRj.setProblem(pb);
        return jRj;
    }
}
