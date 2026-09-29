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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import com.microjainslee.cluster.MarshallingAllowList;
import com.microjainslee.cluster.TcapDialogSnapshotPayload;

import org.junit.Test;
import org.restcomm.protocols.ss7.indicator.NatureOfAddress;
import org.restcomm.protocols.ss7.indicator.NumberingPlan;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.impl.parameter.ParameterFactoryImpl;
import org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle0100;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.TcapDialogSnapshot;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.TRPseudoState;

/**
 * ADR 0007 P / Q — what the survivor imports must be what the owner had.
 *
 * <p>
 * Before this fix the cluster payload kept GT digits only, the nanoTime idle
 * deadline, and no pending invokes: a takeover answered on a different GT,
 * with a random idle window, and refused every dialog with an operation in
 * flight — so the jSS7 M1/M2 work never reached the cluster path.
 */
public class TakeoverSnapshotFidelityTest {

    private final ParameterFactory factory = new ParameterFactoryImpl();

    @Test
    public void snapshotSurvivesTheClusterHopIntact() throws Exception {
        SccpAddress local = gt4("251911000001", 8);
        SccpAddress remote = gt4("251922000009", 6);
        long deadline = System.currentTimeMillis() + 42_000L;
        TcapDialogSnapshot.PendingInvoke invoke = new TcapDialogSnapshot.PendingInvoke(3, 1, 45L, 30_000L, 12_345L);
        boolean[] taken = new boolean[256];
        taken[3 + 128] = true;
        TcapDialogSnapshot original = new TcapDialogSnapshot(7001L, new byte[] { 0, 0, 0x1b, 0x59 }, local, remote,
                TRPseudoState.Active, new long[] { 0, 4, 0, 0, 1, 0, 20, 3 }, deadline, 0, 8, 2002, 5, true, taken,
                "asp-a", new TcapDialogSnapshot.PendingInvoke[] { invoke });

        TcapDialogSnapshotPayload payload = Jss7TcapDialogFailoverPort.toPayload("gmlc-1", original);
        MarshallingAllowList.assertMarshallable("payload", payload);
        TcapDialogSnapshotPayload wire = roundTrip(payload);
        TcapDialogSnapshot back = Jss7TcapDialogFailoverPort.toJss7Snapshot(wire, factory);

        assertEquals("remote GT must keep TT/NP/ES/NAI (P)", remote, back.getRemoteAddress());
        GlobalTitle0100 gt = (GlobalTitle0100) back.getRemoteAddress().getGlobalTitle();
        assertEquals(NatureOfAddress.INTERNATIONAL, gt.getNatureOfAddress());
        assertEquals(NumberingPlan.ISDN_TELEPHONY, gt.getNumberingPlan());
        assertEquals(local, back.getLocalAddress());
        assertEquals("idle deadline must be wall clock (Q / jSS7 M2)", deadline, back.getIdleDeadlineEpochMs());
        assertNotNull("pending invokes must be carried (Q / jSS7 M1)", back.getPendingInvokes());
        assertEquals(1, back.getPendingInvokes().length);
        assertEquals(3, back.getPendingInvokes()[0].getInvokeId());
        assertEquals(Long.valueOf(45L), back.getPendingInvokes()[0].getLocalOperationCode());
        assertFalse("captured invokes are restorable — takeover must not refuse",
                wire.hasUnrestorablePendingInvokes());
        assertTrue(wire.hasPendingInvokes());
    }

    @Test
    public void legacyPayloadWithTakenInvokeIdsStillRefusesResume() {
        boolean[] taken = new boolean[256];
        taken[130] = true;
        TcapDialogSnapshotPayload legacy = new TcapDialogSnapshotPayload("d", 1L, null,
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(1, 8),
                TcapDialogSnapshotPayload.PortableSccpAddress.pcSsn(2, 8), "Active", null, 0L, 0, 8, 2, 0, false,
                taken, System.currentTimeMillis(), null);
        assertTrue("no operations captured → an import would Reject the real peer",
                legacy.hasUnrestorablePendingInvokes());
    }

    @Test
    public void legacyDigitsOnlyAddressStillDecodes() {
        TcapDialogSnapshotPayload.PortableSccpAddress legacy = new TcapDialogSnapshotPayload.PortableSccpAddress(
                "ROUTING_BASED_ON_GLOBAL_TITLE", 0, 6, "25191");
        SccpAddress a = SccpAddressCodec.toSccp(legacy, factory);
        assertEquals("25191", a.getGlobalTitle().getDigits());
    }

    private SccpAddress gt4(String digits, int ssn) {
        return factory.createSccpAddress(RoutingIndicator.ROUTING_BASED_ON_GLOBAL_TITLE,
                factory.createGlobalTitle(digits, 0, NumberingPlan.ISDN_TELEPHONY,
                        factory.createEncodingScheme((byte) 1), NatureOfAddress.INTERNATIONAL), 0, ssn);
    }

    private static TcapDialogSnapshotPayload roundTrip(TcapDialogSnapshotPayload payload) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(payload);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (TcapDialogSnapshotPayload) in.readObject();
        }
    }
}
