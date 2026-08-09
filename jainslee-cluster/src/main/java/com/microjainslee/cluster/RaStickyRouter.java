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

import java.util.Objects;
import java.util.Optional;

/**
 * Sticky outbound decision for any RA activity id (ADR 0002).
 */
public final class RaStickyRouter {

    public enum Action {
        SEND_LOCAL,
        FORWARD_REMOTE,
        REJECT
    }

    public record Decision(Action action, RaDialogOwner owner, String reason) {
        public Decision {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private final RaOwnershipTracker tracker;

    public RaStickyRouter(RaOwnershipTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    /**
     * @param activityCreating when true and no owner yet → SEND_LOCAL (claim)
     * @param routeReady       protocol-honest ready (never LISTEN/isActive alone)
     */
    public Decision decide(String activityId, boolean activityCreating, boolean routeReady) {
        Optional<RaDialogOwner> ownerOpt = tracker.lookupOwner(activityId);
        if (ownerOpt.isEmpty()) {
            if (activityCreating) {
                if (!routeReady) {
                    return new Decision(Action.REJECT, null,
                            "no owner yet and route not ready");
                }
                return new Decision(Action.SEND_LOCAL, null,
                        "activity-creating — claim ownership locally");
            }
            return new Decision(Action.REJECT, null,
                    "no activity owner — refuse nearest-RA send");
        }
        RaDialogOwner owner = ownerOpt.get();
        if (!tracker.localNodeId().equals(owner.ownerNodeId())) {
            return new Decision(Action.FORWARD_REMOTE, owner,
                    "owner is remote node=" + owner.ownerNodeId());
        }
        if (!routeReady) {
            return new Decision(Action.REJECT, owner, "local owner but route not ready");
        }
        return new Decision(Action.SEND_LOCAL, owner, "local owner + route ready");
    }
}
