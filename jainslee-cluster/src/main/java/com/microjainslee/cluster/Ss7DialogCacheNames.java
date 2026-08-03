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

/**
 * Infinispan cache names for SS7 / TCAP dialog affinity (P0 skeleton).
 *
 * <p>See {@code docs/adr/0001-ss7-ra-nn-tcap-failover.md}. Live RA write-through
 * is P1; CONTINUE rehydrate is P2.
 */
public final class Ss7DialogCacheNames {

    /** OTID / address / state metadata keyed by dialog key. */
    public static final String TCAP_DIALOG_META = "tcap-dialog-meta";

    /** Peer-side index: {@code remotePc:remoteOtid} → local dialog key. */
    public static final String TCAP_DIALOG_BY_REMOTE = "tcap-dialog-by-remote";

    /** Sticky outbound fence: SLEE {@code dialogId} → {@link RaDialogOwner}. */
    public static final String RA_DIALOG_OWNER = "ra-dialog-owner";

    /**
     * Portable TCAP snapshot for CONTINUE takeover (P2): dialog key →
     * {@link TcapDialogSnapshotPayload}. Not a live jSS7 {@code DialogImpl}.
     */
    public static final String TCAP_DIALOG_SNAPSHOT = "tcap-dialog-snapshot";

    /**
     * Sticky outbound command bus (P1): envelopes targeted at an owner node.
     * Consumed by the RA on {@code targetNodeId} — same {@link ClusterManager}
     * fabric as meta/owner (never a second cluster).
     */
    public static final String RA_STICKY_COMMANDS = "ra-jss7-sticky-cmd";

    private Ss7DialogCacheNames() {
    }
}
