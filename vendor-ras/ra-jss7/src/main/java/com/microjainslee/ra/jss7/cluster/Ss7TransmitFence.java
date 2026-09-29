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

import java.util.Objects;
import java.util.function.ToLongFunction;

import com.microjainslee.cluster.RaDialogLeaseCaches;
import com.microjainslee.ra.jss7.command.Ss7Command;

/**
 * ADR 0007 D3 / D12 — the last check before an SS7 command reaches the wire.
 *
 * <ul>
 *   <li><b>Dialog-creating command</b> → claim the app activity id cluster-wide
 *       first. A client retry of the same activity on another node loses the
 *       claim while the first node is alive: one MAP transaction towards the HLR,
 *       not two. Once that node has left the view the retry takes the claim over.</li>
 *   <li><b>Any other command</b> on a known TCAP dialog → the dialog lease fence:
 *       this incarnation still owns the OTID's lease, it is unexpired by our own
 *       clock, and the lease cache is available. On the minority side of a
 *       partition the cache is unavailable, so a zombie that still holds its SCTP
 *       association does not transmit.</li>
 * </ul>
 *
 * Two keys, two meanings: {@code activity:<id>} is idempotency for the app,
 * {@code otid:<n>} is ownership of the TCAP transaction.
 */
public final class Ss7TransmitFence {

    public static final String ACTIVITY_PREFIX = "activity:";
    public static final String OTID_PREFIX = "otid:";

    private final RaDialogLeaseCaches leases;
    private final ToLongFunction<String> otidOf;

    /**
     * @param otidOf RA dialog id → local OTID, {@code 0} when unknown
     */
    public Ss7TransmitFence(RaDialogLeaseCaches leases, ToLongFunction<String> otidOf) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.otidOf = Objects.requireNonNull(otidOf, "otidOf");
    }

    public static String dialogLeaseKey(long otid) {
        return OTID_PREFIX + otid;
    }

    public static String activityClaimKey(String dialogId) {
        return ACTIVITY_PREFIX + dialogId;
    }

    /** @return {@code null} to transmit, else why not */
    public String check(Ss7Command cmd) {
        String dialogId = cmd.dialogId();
        if (StickyRaCommandRouter.isDialogCreating(cmd)) {
            // A claim held by a node that left the view is taken over: the retry
            // must make progress once the first attempt's node is gone. At that
            // point the first BEGIN may already have reached the HLR — after a
            // crash the guarantee is at-least-once, never two concurrent senders.
            String key = activityClaimKey(dialogId);
            return leases.tryClaim(key) || leases.takeOver(key)
                    ? null
                    : "activity " + dialogId + " already claimed by a live node (or lease cache unavailable)";
        }
        long otid = otidOf.applyAsLong(dialogId);
        if (otid <= 0) {
            return null;                       // not a TCAP dialog we know: the adapters decide
        }
        return leases.mayTransmit(dialogLeaseKey(otid))
                ? null
                : "FENCE: dialog " + dialogId + " (otid " + otid + ") not owned by this incarnation";
    }
}
