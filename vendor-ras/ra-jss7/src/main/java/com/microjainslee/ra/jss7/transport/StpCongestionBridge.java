/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.transport;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * Feeds M3UA SCON / MTP-STATUS congestion into the shared SCTP AIMD controller
 * and counts MTP3 congestion/status events per affected DPC for admin visibility
 * (DESIGN §10.2 P1+P2). Transfer / pause / resume are ignored — those are
 * routing, not STP throttle.
 *
 * <p><b>Visibility limitation:</b> SCCP importance-based drops happen inside jSS7
 * {@code SccpRoutingControl.send()} ({@code msgImportance < restrictionLevel} →
 * drop + NETWORK_CONGESTION notice). jSS7 exposes no drop-counter hook there —
 * only a 1/s-throttled warn log — so per-OPC/per-importance drop counters cannot
 * be intercepted by this RA. We expose the inputs instead: MTP3 congestion event
 * counts here, SCCP restriction levels + blocking flag via admin status.</p>
 */
public final class StpCongestionBridge implements Mtp3UserPartListener {
    private final AdaptiveSendController controller;
    /** All MTP-STATUS primitives, per affected DPC. */
    private final ConcurrentHashMap<Integer, AtomicLong> statusEventsByDpc = new ConcurrentHashMap<>();
    /** MTP-STATUS with cause SignallingNetworkCongested, per affected DPC. */
    private final ConcurrentHashMap<Integer, AtomicLong> congestionEventsByDpc = new ConcurrentHashMap<>();

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
        if (msg == null) {
            return;
        }
        statusEventsByDpc
                .computeIfAbsent(msg.getAffectedDpc(), dpc -> new AtomicLong())
                .incrementAndGet();
        if (msg.getCause() != Mtp3StatusCause.SignallingNetworkCongested) {
            return;
        }
        congestionEventsByDpc
                .computeIfAbsent(msg.getAffectedDpc(), dpc -> new AtomicLong())
                .incrementAndGet();
        controller.noteSample(SctpCongestionSample.scon(msg.getAffectedDpc(), msg.getCongestionLevel()));
    }

    @Override
    public void onMtp3EndCongestionMessage(Mtp3EndCongestionPrimitive msg) {
        int dpc = msg == null ? 0 : msg.getAffectedDpc();
        controller.noteSample(SctpCongestionSample.scon(dpc, 0));
    }

    /** Snapshot: all MTP-STATUS events per affected DPC (sorted by DPC). */
    public Map<Integer, Long> statusEventsByDpc() {
        return snapshot(statusEventsByDpc);
    }

    /** Snapshot: SCON (SignallingNetworkCongested) status events per affected DPC. */
    public Map<Integer, Long> congestionEventsByDpc() {
        return snapshot(congestionEventsByDpc);
    }

    private static Map<Integer, Long> snapshot(ConcurrentHashMap<Integer, AtomicLong> counters) {
        Map<Integer, Long> out = new TreeMap<>();
        counters.forEach((dpc, count) -> out.put(dpc, count.get()));
        return out;
    }
}
