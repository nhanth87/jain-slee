/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */
package com.microjainslee.ra.jss7.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicInteger;

import org.mobicents.protocols.sctp.spi.AdaptiveSendController;
import org.mobicents.protocols.sctp.spi.AdaptiveSendPolicy;
import org.mobicents.protocols.sctp.spi.SctpCongestionSource;
import org.restcomm.protocols.ss7.mtp.Mtp3EndCongestionPrimitive;
import org.restcomm.protocols.ss7.mtp.Mtp3StatusCause;
import org.restcomm.protocols.ss7.mtp.Mtp3StatusPrimitive;

public class StpCongestionBridgeTest {

    @Test
    public void sconLevel3CutsAndEndCongestionClearsSource() {
        AdaptiveSendController ctl = new AdaptiveSendController(
                new AdaptiveSendPolicy(1000, 10, 2_000L, 1_000L, 50, true, 0.50, 0.25, 0.10));
        StpCongestionBridge bridge = new StpCongestionBridge(ctl);

        bridge.onMtp3StatusMessage(new Mtp3StatusPrimitive(
                1, Mtp3StatusCause.SignallingNetworkCongested, 3, 0));
        assertEquals(3, ctl.snapshot().level());
        assertEquals(SctpCongestionSource.M3UA_SCON, ctl.snapshot().source());
        assertEquals(1, ctl.latestSample().affectedDpc());
        assertFalse(ctl.canAdmitNewWork());
        assertFalse(ctl.tryAcquire(1));
        assertTrue(ctl.tryAcquire(0));

        bridge.onMtp3EndCongestionMessage(new Mtp3EndCongestionPrimitive(1));
        assertEquals(0, ctl.snapshot().level());
        assertTrue(ctl.canAdmitNewWork());
    }

    @Test
    public void nonCongestionStatusIsIgnored() {
        AdaptiveSendController ctl = new AdaptiveSendController(
                new AdaptiveSendPolicy(1000, 10, 2_000L, 1_000L, 50, true, 0.50, 0.25, 0.10));
        StpCongestionBridge bridge = new StpCongestionBridge(ctl);
        bridge.onMtp3StatusMessage(new Mtp3StatusPrimitive(
                1, Mtp3StatusCause.UserPartUnavailability_Unknown, 3, 3));
        assertEquals(0, ctl.snapshot().level());
    }

    @Test
    public void repeatedSconFeedsRealtimeDpcToController() {
        AdaptiveSendController ctl = new AdaptiveSendController(
                new AdaptiveSendPolicy(1000, 10, 2_000L, 1_000L, 50, true, 0.50, 0.25, 0.10));
        AtomicInteger samples = new AtomicInteger();
        ctl.addSink(sample -> samples.incrementAndGet());
        StpCongestionBridge bridge = new StpCongestionBridge(ctl);
        bridge.onMtp3StatusMessage(new Mtp3StatusPrimitive(
                404, Mtp3StatusCause.SignallingNetworkCongested, 1, 0));
        bridge.onMtp3StatusMessage(new Mtp3StatusPrimitive(
                404, Mtp3StatusCause.SignallingNetworkCongested, 1, 0));
        assertEquals(2, samples.get());
        assertEquals(404, ctl.latestSample().affectedDpc());
        assertEquals(1, ctl.latestSample().level());
        assertEquals(SctpCongestionSource.M3UA_SCON, ctl.latestSample().source());
    }

    @Test
    public void countsStatusAndCongestionEventsPerAffectedDpc() {
        AdaptiveSendController ctl = new AdaptiveSendController(
                new AdaptiveSendPolicy(1000, 10, 2_000L, 1_000L, 50, true, 0.50, 0.25, 0.10));
        StpCongestionBridge bridge = new StpCongestionBridge(ctl);

        bridge.onMtp3StatusMessage(new Mtp3StatusPrimitive(
                300, Mtp3StatusCause.SignallingNetworkCongested, 2, 0));
        bridge.onMtp3StatusMessage(new Mtp3StatusPrimitive(
                300, Mtp3StatusCause.SignallingNetworkCongested, 3, 0));
        bridge.onMtp3StatusMessage(new Mtp3StatusPrimitive(
                301, Mtp3StatusCause.UserPartUnavailability_Unknown, 0, 301));

        // SCON counts only the congestion-cause statuses, per affected DPC
        assertEquals(Long.valueOf(2), bridge.congestionEventsByDpc().get(300));
        assertTrue(!bridge.congestionEventsByDpc().containsKey(301));
        // status counts cover every MTP-STATUS cause
        assertEquals(Long.valueOf(2), bridge.statusEventsByDpc().get(300));
        assertEquals(Long.valueOf(1), bridge.statusEventsByDpc().get(301));
        // snapshots are independent copies — later events do not mutate them
        java.util.Map<Integer, Long> before = bridge.congestionEventsByDpc();
        bridge.onMtp3StatusMessage(new Mtp3StatusPrimitive(
                300, Mtp3StatusCause.SignallingNetworkCongested, 1, 0));
        assertEquals(Long.valueOf(2), before.get(300));
        assertEquals(Long.valueOf(3), bridge.congestionEventsByDpc().get(300));
    }

    @Test
    public void emptyBridgeExposesEmptySnapshots() {
        AdaptiveSendController ctl = new AdaptiveSendController(
                new AdaptiveSendPolicy(1000, 10, 2_000L, 1_000L, 50, true, 0.50, 0.25, 0.10));
        StpCongestionBridge bridge = new StpCongestionBridge(ctl);
        assertTrue(bridge.statusEventsByDpc().isEmpty());
        assertTrue(bridge.congestionEventsByDpc().isEmpty());
    }
}
