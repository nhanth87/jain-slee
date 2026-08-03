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
 * <p><b>Not production HA:</b> invoke objects, MAP dialogue state, and
 * multi-ASP routing are out of scope for this POJO.
 */
public final class TcapDialogSnapshotPayload implements Serializable {

    private static final long serialVersionUID = 1L;

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

    public long updatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    /**
     * Minimal PC/SSN (+ optional GT digits) address for rehydrate.
     * Routing indicator name matches jSS7 {@code RoutingIndicator.name()}.
     */
    public record PortableSccpAddress(
            String routingIndicator,
            int pointCode,
            int subsystemNumber,
            String globalTitleDigits
    ) implements Serializable {
        private static final long serialVersionUID = 1L;

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
                && Arrays.equals(invokeIdTaken, that.invokeIdTaken);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(dialogKey, localOtid, localAddress, remoteAddress, trState,
                idleDeadlineNanos, networkId, localSsn, remotePc, seqControl, dpSentInBegin,
                updatedAtEpochMs);
        result = 31 * result + Arrays.hashCode(remoteOtid);
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
