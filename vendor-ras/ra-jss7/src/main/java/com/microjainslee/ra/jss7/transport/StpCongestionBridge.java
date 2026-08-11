/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.transport;

import java.util.Objects;

import org.mobicents.protocols.sctp.spi.AdaptiveSendController;
import org.mobicents.protocols.sctp.spi.SctpCongestionSample;
import org.restcomm.protocols.ss7.mtp.Mtp3EndCongestionPrimitive;
import org.restcomm.protocols.ss7.mtp.Mtp3PausePrimitive;
import org.restcomm.protocols.ss7.mtp.Mtp3ResumePrimitive;
import org.restcomm.protocols.ss7.mtp.Mtp3StatusCause;
import org.restcomm.protocols.ss7.mtp.Mtp3StatusPrimitive;
import org.restcomm.protocols.ss7.mtp.Mtp3TransferPrimitive;
import org.restcomm.protocols.ss7.mtp.Mtp3UserPartListener;

/**
 * Feeds M3UA SCON / MTP-STATUS congestion into the shared SCTP AIMD controller.
 * Transfer / pause / resume are ignored — those are routing, not STP throttle.
 */
final class StpCongestionBridge implements Mtp3UserPartListener {
    private final AdaptiveSendController controller;

    StpCongestionBridge(AdaptiveSendController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    @Override
    public void onMtp3TransferMessage(Mtp3TransferPrimitive msg) {
        // dataplane — do not allocate or log
    }

    @Override
    public void onMtp3PauseMessage(Mtp3PausePrimitive msg) {
        // DUNA / destination down — not a rate signal
    }

    @Override
    public void onMtp3ResumeMessage(Mtp3ResumePrimitive msg) {
    }

    @Override
    public void onMtp3StatusMessage(Mtp3StatusPrimitive msg) {
        if (msg == null || msg.getCause() != Mtp3StatusCause.SignallingNetworkCongested) {
            return;
        }
        controller.noteSample(SctpCongestionSample.scon(msg.getAffectedDpc(), msg.getCongestionLevel()));
    }

    @Override
    public void onMtp3EndCongestionMessage(Mtp3EndCongestionPrimitive msg) {
        int dpc = msg == null ? 0 : msg.getAffectedDpc();
        controller.noteSample(SctpCongestionSample.scon(dpc, 0));
    }
}
