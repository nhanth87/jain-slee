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
import org.restcomm.protocols.ss7.tcap.api.tc.dialog.Dialog;
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
 *
 * <p>Pending-invoke policy (grilling): if snapshot {@code invokeIdTaken} has any
 * bit set, CONTINUE resume is refused (return null from {@link #resolve});
 * explicit {@link #importPayload} still recreates TCAP with invoke-id table then
 * aborts ownership claim for resume (dialog must start via new BEGIN).
 */
public final class Jss7TcapDialogFailoverPort
        implements TcapDialogFailoverPort, TcapMissingDialogResolver {

    private static final Logger LOG = LogManager.getLogger(Jss7TcapDialogFailoverPort.class);

    private final Supplier<TCAPProvider> tcapProvider;
    private final Supplier<ParameterFactory> parameterFactory;
    private final Ss7DialogOwnershipTracker tracker;
    private final Ss7DialogClusterCaches clusterCaches; // nullable
    private final TcapFailoverMetrics metrics;
    private final MapDialogRehydrator mapRehydrator; // nullable
    /**
     * ADR 0007 D3 — must win the dialog lease before importing. {@code null} =
     * unclustered / legacy: import unconditionally.
     */
    private volatile java.util.function.LongPredicate takeoverGuard;

    public Jss7TcapDialogFailoverPort(
            Supplier<TCAPProvider> tcapProvider,
            Supplier<ParameterFactory> parameterFactory,
            Ss7DialogOwnershipTracker tracker,
            Ss7DialogClusterCaches clusterCaches) {
        this(tcapProvider, parameterFactory, tracker, clusterCaches, new TcapFailoverMetrics(), null);
    }

    public Jss7TcapDialogFailoverPort(
            Supplier<TCAPProvider> tcapProvider,
            Supplier<ParameterFactory> parameterFactory,
            Ss7DialogOwnershipTracker tracker,
            Ss7DialogClusterCaches clusterCaches,
            TcapFailoverMetrics metrics) {
        this(tcapProvider, parameterFactory, tracker, clusterCaches, metrics, null);
    }

    public Jss7TcapDialogFailoverPort(
            Supplier<TCAPProvider> tcapProvider,
            Supplier<ParameterFactory> parameterFactory,
            Ss7DialogOwnershipTracker tracker,
            Ss7DialogClusterCaches clusterCaches,
            TcapFailoverMetrics metrics,
            MapDialogRehydrator mapRehydrator) {
        this.tcapProvider = Objects.requireNonNull(tcapProvider, "tcapProvider");
        this.parameterFactory = Objects.requireNonNull(parameterFactory, "parameterFactory");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.clusterCaches = clusterCaches;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.mapRehydrator = mapRehydrator;
    }

    /**
     * Install the lease check a takeover must pass. It is called with the local
     * OTID and must return {@code true} only when this node now owns the dialog
     * lease (CAS won, previous owner gone from the view).
     */
    public void setTakeoverGuard(java.util.function.LongPredicate guard) {
        this.takeoverGuard = guard;
    }

    private boolean takeoverAllowed(long localOtid) {
        java.util.function.LongPredicate guard = this.takeoverGuard;
        if (guard == null || guard.test(localOtid)) {
            return true;
        }
        LOG.warn("[ra-jss7] takeover of otid={} refused by the dialog lease (owner alive or partition minority)",
                localOtid);
        return false;
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
        // Pending invokes that were NOT captured (legacy payload): recreate TCAP with
        // the invokeId table, then refuse resume. Captured ones (jSS7 M1) resume.
        if (payload.hasUnrestorablePendingInvokes()) {
            metrics.pendingInvokeAbort();
            LOG.warn("[ra-jss7] importPayload({}): pending invoke-ids — recreate+abort policy (no CONTINUE resume)",
                    payload.localOtid());
            boolean imported = doImport(payload, false, false);
            if (imported && clusterCaches != null) {
                clusterCaches.removeSnapshot(payload.dialogKey());
            }
            abortImportedDialog(payload.localOtid());
            metrics.importFail();
            return false;
        }
        return doImport(payload, true, true);
    }

    private boolean doImport(TcapDialogSnapshotPayload payload, boolean claimAndRehydrate, boolean countOk) {
        TCAPProvider provider = tcapProvider.get();
        ParameterFactory pf = parameterFactory.get();
        if (provider == null || pf == null) {
            if (countOk) {
                metrics.importFail();
            }
            return false;
        }
        try {
            TcapDialogSnapshot snap = toJss7Snapshot(payload, pf);
            Dialog imported = provider.importDialog(snap);
            if (claimAndRehydrate) {
                claimOwnershipAfterImport(payload.dialogKey(), payload.localOtid());
                if (mapRehydrator != null) {
                    mapRehydrator.rehydrate(imported);
                }
            }
            if (countOk) {
                metrics.importOk();
            }
            return true;
        } catch (Exception e) {
            LOG.warn("[ra-jss7] importDialog({}) failed: {}", payload.localOtid(), e.toString());
            if (countOk) {
                metrics.importFail();
            }
            return false;
        }
    }

    private void abortImportedDialog(long localOtid) {
        TCAPProvider provider = tcapProvider.get();
        if (provider == null) {
            return;
        }
        try {
            // Best-effort: drop snapshot path; live dialog may be released via stack APIs later.
            // Prefer UnrecognizedTxID / new BEGIN over half-resumed invokes.
            TcapDialogSnapshot still = provider.exportDialog(localOtid);
            if (still != null) {
                LOG.info("[ra-jss7] pending-invoke abort: dialog otid={} present after import — "
                        + "operator/peer must open new BEGIN (invokeId table was restored then abandoned)",
                        localOtid);
            }
        } catch (RuntimeException e) {
            LOG.debug("[ra-jss7] abortImportedDialog({}): {}", localOtid, e.toString());
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
                payload = clusterCaches.getSnapshot(Long.toString(localOtid));
            }
        }
        if (payload == null) {
            LOG.debug("[ra-jss7] tryTakeover({}): no snapshot in cache", localOtid);
            metrics.takeoverFail();
            return false;
        }
        if (!takeoverAllowed(localOtid)) {
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
     * Pending invoke-ids → return null (UnrecognizedTxID / new BEGIN).
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
        if (payload.hasUnrestorablePendingInvokes()) {
            metrics.pendingInvokeAbort();
            metrics.continueResolveFail();
            LOG.warn("[ra-jss7] CONTINUE miss otid={}: pending invoke-ids — refuse resume (new BEGIN required)",
                    localOtid);
            return null;
        }
        ParameterFactory pf = parameterFactory.get();
        if (pf == null) {
            metrics.continueResolveFail();
            return null;
        }
        if (!takeoverAllowed(localOtid)) {
            metrics.continueResolveFail();
            return null;
        }
        try {
            TcapDialogSnapshot snap = toJss7Snapshot(payload, pf);
            claimOwnershipAfterImport(payload.dialogKey(), payload.localOtid());
            LOG.info("[ra-jss7] CONTINUE miss: resolving snapshot for otid={} (ownership claimed, invokeId restored)",
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
                0L,                                   // legacy nanoTime field: meaningless off-JVM
                snap.getNetworkId(),
                snap.getLocalSsn(),
                snap.getRemotePc(),
                snap.getSeqControl(),
                snap.isDpSentInBegin(),
                snap.getInvokeIdTaken(),
                System.currentTimeMillis(),
                snap.getPreferredAspName(),
                snap.getIdleDeadlineEpochMs(),
                toPortable(snap.getPendingInvokes()));
    }

    /** ADR 0007 Q — carry jSS7 M1 pending invokes across the cluster. */
    static TcapDialogSnapshotPayload.PendingInvokeState[] toPortable(TcapDialogSnapshot.PendingInvoke[] pending) {
        if (pending == null) {
            return new TcapDialogSnapshotPayload.PendingInvokeState[0];
        }
        TcapDialogSnapshotPayload.PendingInvokeState[] out =
                new TcapDialogSnapshotPayload.PendingInvokeState[pending.length];
        for (int i = 0; i < pending.length; i++) {
            TcapDialogSnapshot.PendingInvoke p = pending[i];
            out[i] = new TcapDialogSnapshotPayload.PendingInvokeState(p.getInvokeId(), p.getInvokeClass(),
                    p.getLocalOperationCode(), p.getInvokeTimeoutMs(), p.getRemainingMillis());
        }
        return out;
    }

    static TcapDialogSnapshot.PendingInvoke[] toJss7(TcapDialogSnapshotPayload.PendingInvokeState[] pending) {
        if (pending == null) {
            return null;
        }
        TcapDialogSnapshot.PendingInvoke[] out = new TcapDialogSnapshot.PendingInvoke[pending.length];
        for (int i = 0; i < pending.length; i++) {
            TcapDialogSnapshotPayload.PendingInvokeState p = pending[i];
            out[i] = new TcapDialogSnapshot.PendingInvoke(p.invokeId(), p.invokeClass(), p.localOperationCode(),
                    p.invokeTimeoutMs(), p.remainingMillis());
        }
        return out;
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
                // ADR 0007 Q: wall clock (jSS7 M2). 0 = legacy payload → fresh idle window.
                payload.idleDeadlineEpochMs(),
                payload.networkId(),
                payload.localSsn(),
                payload.remotePc(),
                payload.seqControl(),
                payload.dpSentInBegin(),
                payload.invokeIdTaken(),
                payload.preferredAspName(),
                toJss7(payload.pendingInvokes()));
    }

    static PortableSccpAddress toPortable(SccpAddress addr) {
        return SccpAddressCodec.toPortable(addr);
    }

    static SccpAddress toSccp(PortableSccpAddress portable, ParameterFactory pf) {
        if (portable == null) {
            throw new IllegalArgumentException("local/remote address required for import");
        }
        return SccpAddressCodec.toSccp(portable, pf);
    }
}
