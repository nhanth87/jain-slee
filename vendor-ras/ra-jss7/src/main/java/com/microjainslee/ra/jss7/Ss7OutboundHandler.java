/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.ra.jss7.Ss7ResourceAdaptor.MutableSession;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.component.Ss7TcapComponent;
import com.microjainslee.ra.jss7.event.Ss7Event;
import org.restcomm.protocols.ss7.config.Ss7Stack;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.ComponentPrimitiveFactory;
import org.restcomm.protocols.ss7.tcap.api.DialogPrimitiveFactory;
import org.restcomm.protocols.ss7.tcap.api.TCAPException;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TCAPSendException;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.Dialog;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.events.TCBeginRequest;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.events.TCContinueRequest;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.events.TCEndRequest;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.events.TCUniRequest;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.events.TCUserAbortRequest;
import org.restcomm.protocols.ss7.tcap.asn.comp.Component;

import java.util.Map;

/**
 * Outbound handler — translates {@link Ss7Command}s into jSS7 TCAP dialog
 * primitives and sends them via the stack.
 *
 * <p>Uses the jSS7 j25 {@link DialogPrimitiveFactory} and
 * {@link ComponentPrimitiveFactory} for all request/component creation.
 * Components are added to the {@link Dialog} via {@code dlg.sendComponent()}
 * <em>before</em> the dialog primitive is sent via {@code dlg.send(req)}.</p>
 */
final class Ss7OutboundHandler {

    private static final Logger LOG = LogManager.getLogger(Ss7OutboundHandler.class);

    private final Ss7Stack stack;
    private final Map<Long, MutableSession> sessions;
    private final Ss7EventPublisherBridge bridge;

    Ss7OutboundHandler(Ss7Stack stack, Map<Long, MutableSession> sessions, Ss7EventPublisherBridge bridge) {
        this.stack = stack;
        this.sessions = sessions;
        this.bridge = bridge;
    }

    // ── dispatch ───────────────────────────────────────────────

    void send(Ss7Command cmd) {
        try {
            switch (cmd) {
                case Ss7Command.TcapBegin b    -> doTcapBegin(b);
                case Ss7Command.TcapContinue c -> doTcapContinue(c);
                case Ss7Command.TcapEnd e      -> doTcapEnd(e);
                case Ss7Command.TcapAbort a    -> doTcapAbort(a);
                case Ss7Command.TcapUni u      -> doTcapUni(u);
            }
        } catch (TCAPException | TCAPSendException ex) {
            LOG.error("jSS7 outbound {} failed on dialog={}: {}",
                    cmd.getClass().getSimpleName(), cmd.dialogId(), ex.toString());
        }
        bridge.touchSession(cmd.dialogId());
    }

    // ── TCAP primitives ────────────────────────────────────────

    private void doTcapBegin(Ss7Command.TcapBegin b) throws TCAPException, TCAPSendException {
        TCAPProvider tp = stack.tcapProvider();
        SccpAddress local = Ss7ComponentCodec.toSccpAddress(b.localAddress());
        SccpAddress remote = Ss7ComponentCodec.toSccpAddress(b.targetAddress());
        Dialog dlg = tp.getNewDialog(local, remote);
        Long realId = dlg.getLocalDialogId();

        // Create session + bind dialog for subsequent Continue/End/Abort
        MutableSession s = sessions.computeIfAbsent(realId,
                id -> new MutableSession(id, bridge.createActivityHandle(String.valueOf(id))));
        s.jss7Dialog = dlg;

        DialogPrimitiveFactory dpf = tp.getDialogPrimitiveFactory();
        TCBeginRequest req = dpf.createBegin(dlg);
        req.setOriginatingAddress(local);
        req.setDestinationAddress(remote);

        if (b.applicationContext() > 0) {
            req.setApplicationContextName(
                    dpf.createApplicationContextName(new long[] { b.applicationContext() }));
        }

        ComponentPrimitiveFactory cpf = tp.getComponentPrimitiveFactory();
        for (Ss7TcapComponent c : b.components()) {
            dlg.sendComponent(Ss7ComponentCodec.toJss7Component(c, cpf));
        }
        dlg.send(req);
        LOG.info("TCAP BEGIN sent: did={} target={}", realId, b.targetAddress());

        // Echo event so SBB learns the real jSS7-assigned dialogId
        bridge.publish(realId, new Ss7Event.TcapBegin(realId, b.targetAddress(), b.localAddress(),
                b.applicationContext(), b.components(), false, b.networkId()));
    }

    private void doTcapContinue(Ss7Command.TcapContinue c) throws TCAPException, TCAPSendException {
        Long did = c.dialogId();
        Dialog dlg = findDialog(did);
        if (dlg == null) { LOG.warn("TCAP CONTINUE — no dialog {} (already ended?)", did); return; }

        TCAPProvider tp = stack.tcapProvider();
        DialogPrimitiveFactory dpf = tp.getDialogPrimitiveFactory();
        TCContinueRequest req = dpf.createContinue(dlg);

        ComponentPrimitiveFactory cpf = tp.getComponentPrimitiveFactory();
        for (Ss7TcapComponent comp : c.components()) {
            dlg.sendComponent(Ss7ComponentCodec.toJss7Component(comp, cpf));
        }
        dlg.send(req);
        LOG.debug("TCAP CONTINUE sent: did={}", did);
    }

    private void doTcapEnd(Ss7Command.TcapEnd e) throws TCAPException, TCAPSendException {
        Long did = e.dialogId();
        Dialog dlg = findDialog(did);
        if (dlg == null) { LOG.warn("TCAP END — no dialog {} (already ended?)", did); return; }

        TCAPProvider tp = stack.tcapProvider();
        DialogPrimitiveFactory dpf = tp.getDialogPrimitiveFactory();
        TCEndRequest req = dpf.createEnd(dlg);

        ComponentPrimitiveFactory cpf = tp.getComponentPrimitiveFactory();
        for (Ss7TcapComponent comp : e.components()) {
            dlg.sendComponent(Ss7ComponentCodec.toJss7Component(comp, cpf));
        }
        dlg.send(req);
        LOG.info("TCAP END sent: did={}", did);
        dlg.release();
        bridge.forceEndSession(did, sessions.get(did));
    }

    private void doTcapAbort(Ss7Command.TcapAbort a) throws TCAPException, TCAPSendException {
        Long did = a.dialogId();
        Dialog dlg = findDialog(did);
        if (dlg == null) { LOG.warn("TCAP ABORT — no dialog {} (already ended?)", did); return; }

        DialogPrimitiveFactory dpf = stack.tcapProvider().getDialogPrimitiveFactory();
        TCUserAbortRequest req = dpf.createUAbort(dlg);
        dlg.send(req);
        LOG.info("TCAP ABORT sent: did={} reason={}", did, a.abortReason());
        dlg.release();
        bridge.forceEndSession(did, sessions.get(did));
    }

    private void doTcapUni(Ss7Command.TcapUni u) throws TCAPException, TCAPSendException {
        TCAPProvider tp = stack.tcapProvider();
        SccpAddress local = Ss7ComponentCodec.toSccpAddress(u.localAddress());
        SccpAddress remote = Ss7ComponentCodec.toSccpAddress(u.targetAddress());
        Dialog dlg = tp.getNewUnstructuredDialog(local, remote);
        Long did = dlg.getLocalDialogId();

        DialogPrimitiveFactory dpf = tp.getDialogPrimitiveFactory();
        TCUniRequest req = dpf.createUni(dlg);

        ComponentPrimitiveFactory cpf = tp.getComponentPrimitiveFactory();
        for (Ss7TcapComponent c : u.components()) {
            dlg.sendComponent(Ss7ComponentCodec.toJss7Component(c, cpf));
        }
        dlg.send(req);
        LOG.info("TCAP UNI sent: did={} target={}", did, u.targetAddress());
        dlg.release();
    }

    // ── dialog lookup ──────────────────────────────────────────

    private Dialog findDialog(Long dialogId) {
        MutableSession s = sessions.get(dialogId);
        if (s == null || s.jss7Dialog == null) return null;
        if (s.jss7Dialog instanceof Dialog d) return d;
        return null;
    }

    // ── bridge interface ───────────────────────────────────────

    /**
     * Callbacks needed by the outbound handler to interact with the SLEE
     * container and session map. Implemented by {@link Ss7ResourceAdaptor}.
     */
    interface Ss7EventPublisherBridge {
        void publish(Long dialogId, com.microjainslee.api.SleeEvent event);
        ActivityHandle createActivityHandle(String id);
        void forceEndSession(Long did, MutableSession s);
        void touchSession(Long did);
    }
}
