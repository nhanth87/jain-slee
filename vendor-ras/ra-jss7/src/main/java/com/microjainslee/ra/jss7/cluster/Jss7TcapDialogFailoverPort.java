/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.cluster.RaDialogOwner;
import com.microjainslee.cluster.Ss7DialogClusterCaches;
import com.microjainslee.cluster.TcapDialogSnapshotPayload;
import com.microjainslee.cluster.TcapDialogSnapshotPayload.PortableSccpAddress;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.indicator.RoutingIndicator;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.sccp.parameter.SccpAddress;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TcapDialogSnapshot;
import org.restcomm.protocols.ss7.tcap.api.TcapMissingDialogResolver;
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.TRPseudoState;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Wired P2 adapter: jSS7 {@code exportDialog}/{@code importDialog} + ISPN
 * {@link TcapDialogSnapshotPayload} write-through.
 *
 * <p>Also implements {@link TcapMissingDialogResolver} so inbound CONTINUE for an
 * unknown DTID can rehydrate from cache before UnrecognizedTxID.
 */
public final class Jss7TcapDialogFailoverPort
        implements TcapDialogFailoverPort, TcapMissingDialogResolver {

    private static final Logger LOG = LogManager.getLogger(Jss7TcapDialogFailoverPort.class);

    private final Supplier<TCAPProvider> tcapProvider;
    private final Supplier<ParameterFactory> parameterFactory;
    private final Ss7DialogOwnershipTracker tracker;
    private final Ss7DialogClusterCaches clusterCaches; // nullable
    private final TcapFailoverMetrics metrics;

    public Jss7TcapDialogFailoverPort(
            Supplier<TCAPProvider> tcapProvider,
            Supplier<ParameterFactory> parameterFactory,
            Ss7DialogOwnershipTracker tracker,
            Ss7DialogClusterCaches clusterCaches) {
        this(tcapProvider, parameterFactory, tracker, clusterCaches, new TcapFailoverMetrics());
    }

    public Jss7TcapDialogFailoverPort(
            Supplier<TCAPProvider> tcapProvider,
            Supplier<ParameterFactory> parameterFactory,
            Ss7DialogOwnershipTracker tracker,
            Ss7DialogClusterCaches clusterCaches,
            TcapFailoverMetrics metrics) {
        this.tcapProvider = Objects.requireNonNull(tcapProvider, "tcapProvider");
        this.parameterFactory = Objects.requireNonNull(parameterFactory, "parameterFactory");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.clusterCaches = clusterCaches;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public TcapFailoverMetrics metrics() {
        return metrics;
    }

    @Override
    public Optional<TcapDialogSnapshotPayload> exportAndStore(long localOtid) {
        TCAPProvider provider = tcapProvider.get();
        if (provider == null) {
            metrics.exportFail();
            return Optional.empty();
        }
        TcapDialogSnapshot snap;
        try {
            snap = provider.exportDialog(localOtid);
        } catch (RuntimeException e) {
            LOG.warn("[ra-jss7] exportDialog({}) failed: {}", localOtid, e.toString());
            metrics.exportFail();
            return Optional.empty();
        }
        if (snap == null) {
            metrics.exportFail();
            return Optional.empty();
        }
        TcapDialogSnapshotPayload payload = toPayload(String.valueOf(localOtid), snap);
        if (clusterCaches != null) {
            clusterCaches.putSnapshot(payload);
        }
        metrics.exportOk();
        return Optional.of(payload);
    }

    @Override
    public boolean importPayload(TcapDialogSnapshotPayload payload) {
        if (payload == null) {
            metrics.importFail();
            return false;
        }
        TCAPProvider provider = tcapProvider.get();
        ParameterFactory pf = parameterFactory.get();
        if (provider == null || pf == null) {
            metrics.importFail();
            return false;
        }
        try {
            TcapDialogSnapshot snap = toJss7Snapshot(payload, pf);
            provider.importDialog(snap);
            claimOwnershipAfterImport(payload.dialogKey(), payload.localOtid());
            metrics.importOk();
            return true;
        } catch (Exception e) {
            LOG.warn("[ra-jss7] importDialog({}) failed: {}", payload.localOtid(), e.toString());
            metrics.importFail();
            return false;
        }
    }

    @Override
    public boolean tryTakeover(long localOtid) {
        TCAPProvider provider = tcapProvider.get();
        if (provider == null) {
            metrics.takeoverFail();
            return false;
        }
        // Already present locally — success without CAS churn.
        if (provider.exportDialog(localOtid) != null) {
            metrics.takeoverOk();
            return true;
        }
        TcapDialogSnapshotPayload payload = null;
        if (clusterCaches != null) {
            payload = clusterCaches.getSnapshot(String.valueOf(localOtid));
            if (payload == null) {
                // Meta may key by dialog id string equal to otid.
                payload = clusterCaches.getSnapshot(Long.toString(localOtid));
            }
        }
        if (payload == null) {
            LOG.debug("[ra-jss7] tryTakeover({}): no snapshot in cache", localOtid);
            metrics.takeoverFail();
            return false;
        }
        boolean ok = importPayload(payload);
        if (ok) {
            metrics.takeoverOk();
        } else {
            metrics.takeoverFail();
        }
        return ok;
    }

    /**
     * jSS7 CONTINUE-miss hook — load cache snapshot for {@code importDialog}.
     * jSS7 calls {@code resolve} then {@code importDialog} in the same stack;
     * claim ownership here (same path as {@link #importPayload}/{@link #tryTakeover})
     * so sticky outbound becomes {@code SEND_LOCAL} on the survivor.
     */
    @Override
    public TcapDialogSnapshot resolve(long localOtid) {
        metrics.continueMiss();
        if (clusterCaches == null) {
            metrics.continueResolveFail();
            return null;
        }
        TcapDialogSnapshotPayload payload = clusterCaches.getSnapshot(String.valueOf(localOtid));
        if (payload == null) {
            metrics.continueResolveFail();
            return null;
        }
        ParameterFactory pf = parameterFactory.get();
        if (pf == null) {
            metrics.continueResolveFail();
            return null;
        }
        try {
            TcapDialogSnapshot snap = toJss7Snapshot(payload, pf);
            // Ownership must land on the survivor before CONTINUE processing continues;
            // otherwise sticky router still sees the dead owner and REJECT/FORWARD.
            claimOwnershipAfterImport(payload.dialogKey(), payload.localOtid());
            LOG.info("[ra-jss7] CONTINUE miss: resolving snapshot for otid={} (ownership claimed)",
                    localOtid);
            return snap;
        } catch (RuntimeException e) {
            LOG.warn("[ra-jss7] CONTINUE miss resolve({}) failed: {}", localOtid, e.toString());
            metrics.continueResolveFail();
            return null;
        }
    }

    private void claimOwnershipAfterImport(String dialogId, long localOtid) {
        long now = System.currentTimeMillis();
        Optional<RaDialogOwner> existing = tracker.lookupOwner(dialogId);
        if (existing.isPresent()) {
            RaDialogOwner owner = existing.get();
            if (tracker.localNodeId().equals(owner.ownerNodeId())) {
                tracker.onDialogTouched(dialogId, "Active", null, 0, 0);
                return;
            }
            if (clusterCaches != null) {
                boolean claimed = clusterCaches.tryClaimOwnership(
                        owner, tracker.localNodeId(), tracker.raName(), now);
                if (!claimed) {
                    LOG.warn("[ra-jss7] ownership CAS lost for dialog={} expectedOwner={}",
                            dialogId, owner.ownerNodeId());
                }
            }
        }
        // Refresh local tracker view (open or touch).
        tracker.onDialogOpened(dialogId, localOtid, null, 0, 0, "Active", null);
    }

    static TcapDialogSnapshotPayload toPayload(String dialogKey, TcapDialogSnapshot snap) {
        return new TcapDialogSnapshotPayload(
                dialogKey,
                snap.getLocalOtid(),
                snap.getRemoteOtid(),
                toPortable(snap.getLocalAddress()),
                toPortable(snap.getRemoteAddress()),
                snap.getState() == null ? "Idle" : snap.getState().name(),
                snap.getApplicationContextOid(),
                snap.getIdleDeadlineNanos(),
                snap.getNetworkId(),
                snap.getLocalSsn(),
                snap.getRemotePc(),
                snap.getSeqControl(),
                snap.isDpSentInBegin(),
                snap.getInvokeIdTaken(),
                System.currentTimeMillis());
    }

    static TcapDialogSnapshot toJss7Snapshot(TcapDialogSnapshotPayload payload, ParameterFactory pf) {
        SccpAddress local = toSccp(payload.localAddress(), pf);
        SccpAddress remote = toSccp(payload.remoteAddress(), pf);
        TRPseudoState state;
        try {
            state = payload.trState() == null ? TRPseudoState.Idle
                    : TRPseudoState.valueOf(payload.trState());
        } catch (IllegalArgumentException e) {
            state = TRPseudoState.Active;
        }
        return new TcapDialogSnapshot(
                payload.localOtid(),
                payload.remoteOtid(),
                local,
                remote,
                state,
                payload.applicationContextOid(),
                payload.idleDeadlineNanos(),
                payload.networkId(),
                payload.localSsn(),
                payload.remotePc(),
                payload.seqControl(),
                payload.dpSentInBegin(),
                payload.invokeIdTaken());
    }

    static PortableSccpAddress toPortable(SccpAddress addr) {
        if (addr == null) {
            return null;
        }
        String ri = addr.getAddressIndicator() != null
                && addr.getAddressIndicator().getRoutingIndicator() != null
                ? addr.getAddressIndicator().getRoutingIndicator().name()
                : "ROUTING_BASED_ON_DPC_AND_SSN";
        String gt = addr.getGlobalTitle() != null ? addr.getGlobalTitle().getDigits() : null;
        return new PortableSccpAddress(ri, addr.getSignalingPointCode(), addr.getSubsystemNumber(), gt);
    }

    static SccpAddress toSccp(PortableSccpAddress portable, ParameterFactory pf) {
        if (portable == null) {
            throw new IllegalArgumentException("local/remote address required for import");
        }
        RoutingIndicator ri;
        try {
            ri = RoutingIndicator.valueOf(portable.routingIndicator());
        } catch (RuntimeException e) {
            ri = RoutingIndicator.ROUTING_BASED_ON_DPC_AND_SSN;
        }
        org.restcomm.protocols.ss7.sccp.parameter.GlobalTitle gt = null;
        if (portable.globalTitleDigits() != null && !portable.globalTitleDigits().isBlank()) {
            gt = pf.createGlobalTitle(portable.globalTitleDigits());
        }
        return pf.createSccpAddress(ri, gt, portable.pointCode(), portable.subsystemNumber());
    }
}
