/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.TcapDialogSnapshotPayload;

import java.util.Optional;

/**
 * RA-side TCAP CONTINUE takeover seam (ADR 0001 P2).
 *
 * <p>Wired adapter calls jSS7 {@code TCAPProvider.exportDialog}/{@code importDialog}.
 * Default {@link #unsupported()} keeps sticky P1-only behaviour.
 *
 * <p><b>Not production HA:</b> multi-ASP routing, invoke/MAP state, and timer
 * completeness remain open even when this port is wired.
 */
public interface TcapDialogFailoverPort {

    /**
     * Export live dialog into ISPN-safe payload (and optionally write-through).
     *
     * @return empty when dialog missing or port unsupported
     */
    Optional<TcapDialogSnapshotPayload> exportAndStore(long localOtid);

    /**
     * Import a portable payload into this node's TCAP {@code dialogs} map.
     *
     * @return {@code true} when import succeeded
     */
    boolean importPayload(TcapDialogSnapshotPayload payload);

    /**
     * CONTINUE-miss / explicit failover: load snapshot from cluster cache,
     * {@code importDialog}, and CAS ownership to this node when possible.
     *
     * @return {@code true} when dialog is present locally after the call
     */
    boolean tryTakeover(long localOtid);

    /** @return empty until jSS7 export/import is wired into this RA */
    default Optional<Object> exportDialogSnapshot(long localOtid) {
        return exportAndStore(localOtid).map(p -> (Object) p);
    }

    /** @return false until jSS7 import is wired into this RA */
    default boolean importDialogSnapshot(Object snapshot) {
        if (snapshot instanceof TcapDialogSnapshotPayload payload) {
            return importPayload(payload);
        }
        return false;
    }

    /**
     * Default no-op port — sticky P1 path only.
     */
    static TcapDialogFailoverPort unsupported() {
        return new TcapDialogFailoverPort() {
            @Override
            public Optional<TcapDialogSnapshotPayload> exportAndStore(long localOtid) {
                return Optional.empty();
            }

            @Override
            public boolean importPayload(TcapDialogSnapshotPayload payload) {
                return false;
            }

            @Override
            public boolean tryTakeover(long localOtid) {
                return false;
            }
        };
    }
}
