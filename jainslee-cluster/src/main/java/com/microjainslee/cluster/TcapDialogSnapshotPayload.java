/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Portable TCAP dialog snapshot for {@link Ss7DialogCacheNames#TCAP_DIALOG_SNAPSHOT}.
 *
 * <p>Mirrors jSS7 {@code TcapDialogSnapshot} fields without {@code org.restcomm.*}
 * types so values stay inside {@link MarshallingAllowList}. The RA converts
 * to/from the live jSS7 snapshot for {@code exportDialog}/{@code importDialog}.
 *
 * <p><b>Not production HA:</b> invoke objects and MAP dialogue state are out of
 * scope for this POJO. Preferred ASP is carried for N–N sticky dialog continuity
 * after CONTINUE import (see jSS7 {@code preferredAspName}).
 */
public final class TcapDialogSnapshotPayload implements Serializable {

    private static final long serialVersionUID = 3L;

    private final String dialogKey;
    private final long localOtid;
    private final byte[] remoteOtid;
    private final PortableSccpAddress localAddress;
    private final PortableSccpAddress remoteAddress;
    private final String trState;
    private final long[] applicationContextOid;
    private final long idleDeadlineNanos;
    private final int networkId;
    private final int localSsn;
    private final int remotePc;
    private final int seqControl;
    private final boolean dpSentInBegin;
    private final boolean[] invokeIdTaken;
    private final long updatedAtEpochMs;
    private final String preferredAspName;
    /** ADR 0007 Q — wall-clock idle deadline (jSS7 M2); 0 = unknown (legacy payload). */
    private final long idleDeadlineEpochMs;
    /** ADR 0007 Q — outstanding operations (jSS7 M1); {@code null} = not captured (legacy). */
    private final PendingInvokeState[] pendingInvokes;

    public TcapDialogSnapshotPayload(
            String dialogKey,
            long localOtid,
            byte[] remoteOtid,
            PortableSccpAddress localAddress,
            PortableSccpAddress remoteAddress,
            String trState,
            long[] applicationContextOid,
            long idleDeadlineNanos,
            int networkId,
            int localSsn,
            int remotePc,
            int seqControl,
            boolean dpSentInBegin,
            boolean[] invokeIdTaken,
            long updatedAtEpochMs) {
        this(dialogKey, localOtid, remoteOtid, localAddress, remoteAddress, trState, applicationContextOid,
                idleDeadlineNanos, networkId, localSsn, remotePc, seqControl, dpSentInBegin, invokeIdTaken,
                updatedAtEpochMs, null);
    }

    public TcapDialogSnapshotPayload(
            String dialogKey,
            long localOtid,
            byte[] remoteOtid,
            PortableSccpAddress localAddress,
            PortableSccpAddress remoteAddress,
            String trState,
            long[] applicationContextOid,
            long idleDeadlineNanos,
            int networkId,
            int localSsn,
            int remotePc,
            int seqControl,
            boolean dpSentInBegin,
            boolean[] invokeIdTaken,
            long updatedAtEpochMs,
            String preferredAspName) {
        this(dialogKey, localOtid, remoteOtid, localAddress, remoteAddress, trState, applicationContextOid,
                idleDeadlineNanos, networkId, localSsn, remotePc, seqControl, dpSentInBegin, invokeIdTaken,
                updatedAtEpochMs, preferredAspName, 0L, null);
    }

    /**
     * ADR 0007 Q — full-fidelity snapshot: wall-clock idle deadline and the
     * outstanding invokes, so a takeover resumes a dialog that has an operation
     * in flight instead of refusing it.
     */
    public TcapDialogSnapshotPayload(
            String dialogKey,
            long localOtid,
            byte[] remoteOtid,
            PortableSccpAddress localAddress,
            PortableSccpAddress remoteAddress,
            String trState,
            long[] applicationContextOid,
            long idleDeadlineNanos,
            int networkId,
            int localSsn,
            int remotePc,
            int seqControl,
            boolean dpSentInBegin,
            boolean[] invokeIdTaken,
            long updatedAtEpochMs,
            String preferredAspName,
            long idleDeadlineEpochMs,
            PendingInvokeState[] pendingInvokes) {
        this.dialogKey = Objects.requireNonNull(dialogKey, "dialogKey");
        this.localOtid = localOtid;
        this.remoteOtid = remoteOtid == null ? null : remoteOtid.clone();
        this.localAddress = localAddress;
        this.remoteAddress = remoteAddress;
        this.trState = trState;
        this.applicationContextOid = applicationContextOid == null ? null
                : Arrays.copyOf(applicationContextOid, applicationContextOid.length);
        this.idleDeadlineNanos = idleDeadlineNanos;
        this.networkId = networkId;
        this.localSsn = localSsn;
        this.remotePc = remotePc;
        this.seqControl = seqControl;
        this.dpSentInBegin = dpSentInBegin;
        this.invokeIdTaken = invokeIdTaken == null ? null
                : Arrays.copyOf(invokeIdTaken, invokeIdTaken.length);
        this.updatedAtEpochMs = updatedAtEpochMs;
        this.preferredAspName = preferredAspName;
        this.idleDeadlineEpochMs = idleDeadlineEpochMs;
        this.pendingInvokes = pendingInvokes == null ? null : pendingInvokes.clone();
    }

    /** Wall-clock idle deadline; 0 when the payload predates ADR 0007 Q. */
    public long idleDeadlineEpochMs() {
        return idleDeadlineEpochMs;
    }

    /** Outstanding operations, or {@code null} when the payload did not capture them. */
    public PendingInvokeState[] pendingInvokes() {
        return pendingInvokes == null ? null : pendingInvokes.clone();
    }

    /**
     * {@code true} when invoke ids are taken but the operations themselves were
     * not captured. Only then must a takeover refuse to resume: with the
     * operations restored, a ReturnResult matches instead of being Rejected.
     */
    public boolean hasUnrestorablePendingInvokes() {
        if (!hasPendingInvokes()) {
            return false;
        }
        int taken = 0;
        for (boolean t : invokeIdTaken) {
            if (t) {
                taken++;
            }
        }
        return pendingInvokes == null || pendingInvokes.length < taken;
    }

    /**
     * Allow-list-clean mirror of jSS7 {@code TcapDialogSnapshot.PendingInvoke}.
     */
    public record PendingInvokeState(int invokeId, int invokeClass, Long localOperationCode, long invokeTimeoutMs,
            long remainingMillis) implements Serializable {
        private static final long serialVersionUID = 1L;
    }

    public String dialogKey() {
        return dialogKey;
    }

    public long localOtid() {
        return localOtid;
    }

    public byte[] remoteOtid() {
        return remoteOtid == null ? null : remoteOtid.clone();
    }

    public PortableSccpAddress localAddress() {
        return localAddress;
    }

    public PortableSccpAddress remoteAddress() {
        return remoteAddress;
    }

    public String trState() {
        return trState;
    }

    public long[] applicationContextOid() {
        return applicationContextOid == null ? null
                : Arrays.copyOf(applicationContextOid, applicationContextOid.length);
    }

    public long idleDeadlineNanos() {
        return idleDeadlineNanos;
    }

    public int networkId() {
        return networkId;
    }

    public int localSsn() {
        return localSsn;
    }

    public int remotePc() {
        return remotePc;
    }

    public int seqControl() {
        return seqControl;
    }

    public boolean dpSentInBegin() {
        return dpSentInBegin;
    }

    public boolean[] invokeIdTaken() {
        return invokeIdTaken == null ? null : Arrays.copyOf(invokeIdTaken, invokeIdTaken.length);
    }

    /**
     * Outstanding TCAP invokes (invoke-id bits taken). After import, operation
     * objects are empty — any taken bit means pending-invoke abort policy.
     */
    public boolean hasPendingInvokes() {
        if (invokeIdTaken == null) {
            return false;
        }
        for (boolean taken : invokeIdTaken) {
            if (taken) {
                return true;
            }
        }
        return false;
    }

    public long updatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    /** Sticky M3UA ASP for N–N dialog-bound PayloadData; null = SLS among ACTIVE of N. */
    public String preferredAspName() {
        return preferredAspName;
    }

    /**
     * SCCP address for rehydrate and cross-node PDU forwarding.
     * Routing indicator name matches jSS7 {@code RoutingIndicator.name()}.
     *
     * <p>
     * ADR 0007 P — the GT is kept in full (indicator, TT, NP, encoding scheme,
     * NAI). The earlier digits-only form rebuilt a different GT after takeover,
     * so replies from the survivor were routed on the wrong translation.
     *
     * @param gtIndicator Q.713 GTI 1..4, 0 = no GT, -1 = legacy digits-only
     * @param numberingPlan / encodingSchemeCode / natureOfAddress: -1 when the GTI has none
     */
    public record PortableSccpAddress(
            String routingIndicator,
            int pointCode,
            int subsystemNumber,
            String globalTitleDigits,
            int gtIndicator,
            int translationType,
            int numberingPlan,
            int encodingSchemeCode,
            int natureOfAddress,
            boolean translated
    ) implements Serializable {
        private static final long serialVersionUID = 2L;

        /** Legacy digits-only address. */
        public PortableSccpAddress(String routingIndicator, int pointCode, int subsystemNumber,
                String globalTitleDigits) {
            this(routingIndicator, pointCode, subsystemNumber, globalTitleDigits,
                    globalTitleDigits == null ? 0 : -1, 0, -1, -1, -1, false);
        }

        public PortableSccpAddress {
            routingIndicator = routingIndicator == null
                    ? "ROUTING_BASED_ON_DPC_AND_SSN"
                    : routingIndicator;
        }

        public static PortableSccpAddress pcSsn(int pc, int ssn) {
            return new PortableSccpAddress("ROUTING_BASED_ON_DPC_AND_SSN", pc, ssn, null);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TcapDialogSnapshotPayload that)) {
            return false;
        }
        return localOtid == that.localOtid
                && idleDeadlineNanos == that.idleDeadlineNanos
                && networkId == that.networkId
                && localSsn == that.localSsn
                && remotePc == that.remotePc
                && seqControl == that.seqControl
                && dpSentInBegin == that.dpSentInBegin
                && updatedAtEpochMs == that.updatedAtEpochMs
                && dialogKey.equals(that.dialogKey)
                && Arrays.equals(remoteOtid, that.remoteOtid)
                && Objects.equals(localAddress, that.localAddress)
                && Objects.equals(remoteAddress, that.remoteAddress)
                && Objects.equals(trState, that.trState)
                && Arrays.equals(applicationContextOid, that.applicationContextOid)
                && Arrays.equals(invokeIdTaken, that.invokeIdTaken)
                && Objects.equals(preferredAspName, that.preferredAspName)
                && idleDeadlineEpochMs == that.idleDeadlineEpochMs
                && Arrays.equals(pendingInvokes, that.pendingInvokes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(dialogKey, localOtid, localAddress, remoteAddress, trState,
                idleDeadlineNanos, networkId, localSsn, remotePc, seqControl, dpSentInBegin,
                updatedAtEpochMs, preferredAspName, idleDeadlineEpochMs);
        result = 31 * result + Arrays.hashCode(remoteOtid);
        result = 31 * result + Arrays.hashCode(pendingInvokes);
        result = 31 * result + Arrays.hashCode(applicationContextOid);
        result = 31 * result + Arrays.hashCode(invokeIdTaken);
        return result;
    }

    @Override
    public String toString() {
        return "TcapDialogSnapshotPayload[dialogKey=" + dialogKey
                + ", localOtid=" + localOtid
                + ", trState=" + trState
                + ']';
    }
}
