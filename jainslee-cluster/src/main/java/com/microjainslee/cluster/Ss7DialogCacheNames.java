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

    /**
     * SCTP local endpoint lease (N–N): key {@code ip:port} → {@link SctpEndpointLease}.
     * ISPN CAS + generation fences dual-bind after view change (no ZooKeeper).
     */
    public static final String SCTP_ENDPOINT_LEASE = "sctp-endpoint-lease";

    /**
     * Cluster-wide N–N peer-route LB counters / affinity pins for <b>new</b> NI/GTT
     * sessions (any of N candidate PC/ASP pairs). Not A-A pair-only and not A-P.
     * Mid-dialog sticky ASP lives on the jSS7 dialog ({@code preferredAspName}).
     *
     * @see Ss7PeerRouteAffinity
     */
    public static final String SS7_PEER_ROUTE_LB = "ss7-peer-route-lb";

    private Ss7DialogCacheNames() {
    }
}
