/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationQuintuplet;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationSetList;
import org.restcomm.protocols.ss7.map.api.service.mobility.authentication.AuthenticationTriplet;

import java.util.ArrayList;

/**
 * MAP mobility-management answers on dialogs the network opened toward us (HLR/AuC face):
 * SendAuthenticationInfo responses and MAP errors. Encoding only — vectors come from the
 * application's AuC.
 */
final class MapMobilityOutbound {

    private static final Logger LOG = LogManager.getLogger(MapMobilityOutbound.class);

    private final MAPProvider provider;

    MapMobilityOutbound(MAPProvider provider) {
        this.provider = provider;
    }

    boolean send(OutboundCommand command) {
        if (provider == null) {
            return false;
        }
        return switch (command) {
            case Ss7Command.MapSendAuthenticationInfoResponse sai -> {
                replySai(sai);
                yield true;
            }
            case Ss7Command.MapReturnError err -> {
                returnError(err);
                yield true;
            }
            default -> false;
        };
    }

    private void replySai(Ss7Command.MapSendAuthenticationInfoResponse cmd) {
        long localId = localId(cmd.dialogId());
        MAPDialog raw = provider.getMAPDialog(localId);
        if (!(raw instanceof MAPDialogMobility mobility)) {
            throw new IllegalStateException("No mobility MAP dialog for id=" + localId);
        }
        try {
            MAPParameterFactory pf = provider.getMAPParameterFactory();
            AuthenticationSetList set;
            if (!cmd.quintuplets().isEmpty()) {
                ArrayList<AuthenticationQuintuplet> q = new ArrayList<>(cmd.quintuplets().size());
                for (Ss7Command.AuthQuintuplet v : cmd.quintuplets()) {
                    q.add(pf.createAuthenticationQuintuplet(v.rand(), v.xres(), v.ck(), v.ik(), v.autn()));
                }
                set = pf.createAuthenticationSetList(pf.createQuintupletList(q));
            } else {
                ArrayList<AuthenticationTriplet> t = new ArrayList<>(cmd.triplets().size());
                for (Ss7Command.AuthTriplet v : cmd.triplets()) {
                    t.add(pf.createAuthenticationTriplet(v.rand(), v.sres(), v.kc()));
                }
                set = pf.createAuthenticationSetList(pf.createTripletList(t));
            }
            mobility.addSendAuthenticationInfoResponse(cmd.invokeId(), set, null, null, null);
            mobility.close(false);
            LOG.debug("[ra-jss7] SAI response localDialog={} invokeId={} vectors={}",
                    localId, cmd.invokeId(),
                    cmd.quintuplets().isEmpty() ? cmd.triplets().size() : cmd.quintuplets().size());
        } catch (MAPException | RuntimeException e) {
            LOG.error("[ra-jss7] SAI response failed id={}: {}", localId, e.toString());
            throw new IllegalStateException("MAP SAI response failed: " + e.getMessage(), e);
        }
    }

    private void returnError(Ss7Command.MapReturnError cmd) {
        long localId = localId(cmd.dialogId());
        MAPDialog dialog = provider.getMAPDialog(localId);
        if (dialog == null) {
            throw new IllegalStateException("No MAP dialog for id=" + localId);
        }
        try {
            dialog.sendErrorComponent(cmd.invokeId(),
                    provider.getMAPErrorMessageFactory().createMAPErrorMessageExtensionContainer(
                            cmd.mapErrorCode(), null));
            dialog.close(false);
        } catch (MAPException | RuntimeException e) {
            LOG.error("[ra-jss7] MAP error {} failed id={}: {}", cmd.mapErrorCode(), localId, e.toString());
            throw new IllegalStateException("MAP return error failed: " + e.getMessage(), e);
        }
    }

    private static long localId(String dialogId) {
        try {
            return Long.parseLong(dialogId == null ? "" : dialogId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid MAP dialog id: " + dialogId, e);
        }
    }
}
