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

import com.microjainslee.cluster.RaDialogOwner;
import com.microjainslee.ra.jss7.command.Ss7Command;

import java.util.Objects;
import java.util.Optional;

/**
 * Sticky outbound decision for dialog-bound SS7 commands (ADR 0001 P1).
 *
 * <ul>
 *   <li>{@link Action#SEND_LOCAL} — this node owns the dialog (or creates it)
 *       and M3UA route is ready;</li>
 *   <li>{@link Action#FORWARD_REMOTE} — another node owns the dialog;</li>
 *   <li>{@link Action#REJECT} — missing owner for Continue/End, or local owner
 *       but route not ready (honest DOWN — never pretend link UP).</li>
 * </ul>
 */
public final class StickyRaCommandRouter {

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

    private final Ss7DialogOwnershipTracker tracker;

    public StickyRaCommandRouter(Ss7DialogOwnershipTracker tracker) {
        this.tracker = Objects.requireNonNull(tracker, "tracker");
    }

    /**
     * @param routeReady {@link com.microjainslee.ra.jss7.Ss7ResourceAdaptor#isM3uaRouteReady()}
     *                   — never pass {@code isActive()}/{@code isStarted()}
     */
    public Decision decide(Ss7Command command, boolean routeReady) {
        Objects.requireNonNull(command, "command");
        String dialogId = command.dialogId();
        Optional<RaDialogOwner> ownerOpt = tracker.lookupOwner(dialogId);

        if (ownerOpt.isEmpty()) {
            if (isDialogCreating(command)) {
                if (!routeReady) {
                    return new Decision(Action.REJECT, null,
                            "no owner yet and M3UA route not ready (isM3uaRouteReady=false)");
                }
                return new Decision(Action.SEND_LOCAL, null,
                        "dialog-creating command — claim ownership locally");
            }
            return new Decision(Action.REJECT, null,
                    "no dialog owner for Continue/End/Abort — refuse nearest-RA send");
        }

        RaDialogOwner owner = ownerOpt.get();
        if (!tracker.localNodeId().equals(owner.ownerNodeId())) {
            return new Decision(Action.FORWARD_REMOTE, owner,
                    "owner is remote node=" + owner.ownerNodeId());
        }
        if (!routeReady) {
            return new Decision(Action.REJECT, owner,
                    "local owner but M3UA route not ready (isM3uaRouteReady=false)");
        }
        return new Decision(Action.SEND_LOCAL, owner, "local owner + route ready");
    }

    /**
     * Commands that establish a new jSS7 dialog on this stack (may run without
     * a prior owner entry).
     */
    public static boolean isDialogCreating(Ss7Command command) {
        return command instanceof Ss7Command.TcapBegin
                || command instanceof Ss7Command.TcapUni
                || command instanceof Ss7Command.MapSendRoutingInfoForSm
                || command instanceof Ss7Command.MapMtForwardSm;
    }
}
