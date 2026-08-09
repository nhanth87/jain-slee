/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.Dialog;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * After TCAP {@code importDialog}, rehydrate a minimal MAP dialog wrapper
 * (no MAP invoke timers). CAP rehydrate is a later phase (grilling Q12=C).
 *
 * <p>jSS7 {@code MAPProviderImpl.onTCContinue} also auto-rehydrates when the
 * MAP wrapper is missing — this class covers explicit {@code tryTakeover}/import.
 */
public final class MapDialogRehydrator {

    private static final Logger LOG = LogManager.getLogger(MapDialogRehydrator.class);

    private final Supplier<MAPProvider> mapProvider;

    public MapDialogRehydrator(Supplier<MAPProvider> mapProvider) {
        this.mapProvider = Objects.requireNonNull(mapProvider, "mapProvider");
    }

    /**
     * @return {@code true} when MAP wrapper exists or was created; {@code false}
     *         when MAP is unavailable / ACN missing (TCAP-only path still ok)
     */
    public boolean rehydrate(Dialog tcapDialog) {
        if (tcapDialog == null) {
            return false;
        }
        MAPProvider map = mapProvider.get();
        if (map == null) {
            return false;
        }
        try {
            Long localId = tcapDialog.getLocalDialogId();
            MAPDialog existing = map.getMAPDialog(localId);
            if (existing != null) {
                return true;
            }
            map.rehydrateDialogFromTcap(tcapDialog);
            return true;
        } catch (MAPException e) {
            LOG.warn("[ra-jss7] MAP rehydrate failed otid={}: {}",
                    tcapDialog.getLocalDialogId(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            LOG.warn("[ra-jss7] MAP rehydrate failed otid={}: {}",
                    tcapDialog.getLocalDialogId(), e.toString());
            return false;
        }
    }
}
