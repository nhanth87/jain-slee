/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.map.MAPDialogImpl;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.Dialog;

/**
 * Pin N–N sticky ASP + remote PC onto a freshly created MAP dialog's TCAP dialog
 * before the first TC-BEGIN / PayloadData (bypass mid-dialog SLS flip).
 */
final class DialogRoutePin {

    private static final Logger LOG = LogManager.getLogger(DialogRoutePin.class);

    private DialogRoutePin() {
    }

    static void apply(MAPDialog mapDialog, String preferredAspName, int remotePc) {
        if (mapDialog == null) {
            return;
        }
        boolean hasAsp = preferredAspName != null && !preferredAspName.isBlank();
        boolean hasPc = remotePc >= 0;
        if (!hasAsp && !hasPc) {
            return;
        }
        if (!(mapDialog instanceof MAPDialogImpl impl)) {
            LOG.warn("[ra-jss7] cannot pin preferredAsp/remotePc — MAPDialog is not MAPDialogImpl ({})",
                    mapDialog.getClass().getName());
            return;
        }
        Dialog tcap = impl.getTcapDialog();
        if (tcap == null) {
            return;
        }
        if (hasAsp) {
            tcap.setPreferredAspName(preferredAspName.trim());
        }
        if (hasPc) {
            tcap.setRemotePc(remotePc);
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("[ra-jss7] dialog route pin localId={} preferredAsp={} remotePc={}",
                    mapDialog.getLocalDialogId(), preferredAspName, remotePc);
        }
    }
}
