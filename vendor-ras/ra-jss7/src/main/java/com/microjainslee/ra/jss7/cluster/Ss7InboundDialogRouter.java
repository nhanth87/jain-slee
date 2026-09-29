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

import java.io.Serializable;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import com.microjainslee.cluster.ClusterManager;
import com.microjainslee.cluster.ClusterUnicast;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;
import org.restcomm.protocols.ss7.tcap.api.TcapForeignPdu;
import org.restcomm.protocols.ss7.tcap.api.TcapInboundDialogRouter;

/**
 * ADR 0007 D11 — symmetric active/active for the TCAP plane.
 *
 * <p>
 * Every node runs its own SS7 stack as one ASP of the same AS and owns a
 * disjoint OTID range. The STP load-shares, so a CONTINUE / END / ABORT may
 * arrive on a node that does not own the dialog. jSS7 then calls
 * {@link #routeForeign}: this router names the owner from the DTID and hands it
 * the raw PDU. The owner injects it with {@link TCAPProvider#processForeignPdu},
 * so the dialog, its pending invokes, the MAP state and the SBB all stay where
 * they are.
 *
 * <pre>
 *  STP ──CONTINUE(DTID=owned by A)──▶ node B
 *                                      │ dialogs.get(DTID) == null
 *                                      │ routeForeign → ranges: A, A in view
 *                                      └──unicast(PDU bytes + SCCP addrs)──▶ node A
 *                                                                             │ processForeignPdu
 *                                                                             ▼
 *                                                          A's dialog → MAP → SBB → HTTP reply
 * </pre>
 *
 * <p>
 * Decision table on a local DTID miss:
 * <ul>
 *   <li>no range covers the DTID, or it is ours → {@code false}: default handling</li>
 *   <li>owner not in the cluster view → {@code false}: the missing-dialog
 *       resolver may take the dialog over</li>
 *   <li>owner present → forward, {@code true}</li>
 * </ul>
 * A forward that fails definitively (owner left, RA stopped) is re-processed
 * locally as foreign, which lands on the resolver — never routed again. A
 * forward that <b>times out</b> is not re-processed: it may have been delivered,
 * and processing it twice would answer the peer twice.
 */
public final class Ss7InboundDialogRouter implements TcapInboundDialogRouter {

    private static final Logger LOG = LogManager.getLogger(Ss7InboundDialogRouter.class);

    private final String raName;
    private final ClusterManager clusterManager;
    private final ClusterUnicast unicast;
    private final Supplier<TCAPProvider> tcapProvider;
    private final Supplier<ParameterFactory> parameterFactory;
    private final TcapFailoverMetrics metrics;
    private final Cache<String, OtidRange> ranges;
    private final String topic;
    private volatile OtidRange localRange;

    public Ss7InboundDialogRouter(String raName, ClusterManager clusterManager, ClusterUnicast unicast,
            Supplier<TCAPProvider> tcapProvider, Supplier<ParameterFactory> parameterFactory,
            TcapFailoverMetrics metrics) {
        this.raName = Objects.requireNonNull(raName, "raName");
        this.clusterManager = Objects.requireNonNull(clusterManager, "clusterManager");
        this.unicast = Objects.requireNonNull(unicast, "unicast");
        this.tcapProvider = Objects.requireNonNull(tcapProvider, "tcapProvider");
        this.parameterFactory = Objects.requireNonNull(parameterFactory, "parameterFactory");
        this.metrics = metrics != null ? metrics : new TcapFailoverMetrics();
        this.ranges = clusterManager.getCache(rangeCacheName(raName), CacheMode.REPL_SYNC, true);
        this.topic = "ra/" + raName + "/tcap-foreign-pdu";
    }

    public static String rangeCacheName(String raName) {
        return "ra-" + raName + "-otid-ranges";
    }

    /**
     * Publish this node's OTID range, start receiving forwarded PDUs and hook
     * the TCAP provider.
     *
     * @throws IllegalStateException when the range overlaps another node's —
     *         routing by DTID would then deliver to the wrong node, so the RA
     *         must not start in cluster mode
     */
    public void start(long rangeStart, long rangeEnd) {
        OtidRange mine = new OtidRange(clusterManager.getNodeId(), rangeStart, rangeEnd);
        for (OtidRange other : ranges.values()) {
            if (!other.nodeId().equals(mine.nodeId()) && other.overlaps(mine)) {
                throw new IllegalStateException("[" + raName + "] OTID range " + mine + " overlaps " + other
                        + " — every node needs a disjoint dialogIdRangeStart/End (ADR 0007 D11)");
            }
        }
        ranges.put(mine.nodeId(), mine);
        this.localRange = mine;
        unicast.register(topic, this::onForeign);
        TCAPProvider provider = tcapProvider.get();
        if (provider == null) {
            throw new IllegalStateException("[" + raName + "] TCAP provider not available");
        }
        provider.setInboundDialogRouter(this);
        LOG.info("[{}] inbound dialog router started node={} range=[{}, {}]",
                raName, mine.nodeId(), rangeStart, rangeEnd);
    }

    /**
     * Stop routing. The range entry is left in place on purpose: while this node
     * is gone its DTIDs must resolve to an absent owner (→ takeover), not to
     * "nobody" (→ UnrecognizedTxID).
     */
    public void stop() {
        TCAPProvider provider = tcapProvider.get();
        if (provider != null && provider.getInboundDialogRouter() == this) {
            provider.setInboundDialogRouter(null);
        }
        unicast.unregister(topic);
        LOG.info("[{}] inbound dialog router stopped", raName);
    }

    /** @return the node owning {@code otid}, or {@code null} when no range covers it. */
    public String ownerOf(long otid) {
        OtidRange mine = localRange;
        if (mine != null && mine.contains(otid)) {
            return mine.nodeId();
        }
        for (OtidRange range : ranges.values()) {
            if (range.contains(otid)) {
                return range.nodeId();
            }
        }
        return null;
    }

    @Override
    public boolean routeForeign(long localOtid, TcapForeignPdu pdu) {
        String owner = ownerOf(localOtid);
        String self = clusterManager.getNodeId();
        if (owner == null || owner.equals(self)) {
            return false;
        }
        if (!clusterManager.isNodePresent(owner)) {
            metrics.foreignOwnerAbsent();
            LOG.info("[{}] DTID {} owned by absent node={} — falling through to takeover", raName, localOtid, owner);
            return false;
        }
        ForeignTcapPduPayload payload;
        try {
            payload = ForeignTcapPduPayload.of(localOtid, self, raName, pdu);
        } catch (RuntimeException e) {
            LOG.warn("[{}] DTID {} cannot be made portable ({}) — handling locally", raName, localOtid, e.toString());
            return false;
        }
        metrics.foreignForwarded();
        unicast.send(owner, topic, payload).whenComplete((delivered, error) -> {
            if (error == null && Boolean.TRUE.equals(delivered)) {
                return;
            }
            metrics.foreignSendFail();
            if (error != null && isTimeout(error)) {
                LOG.warn("[{}] forward of DTID {} to node={} timed out — not re-processed (may have been delivered)",
                        raName, localOtid, owner);
                return;
            }
            LOG.warn("[{}] forward of DTID {} to node={} failed ({}) — re-processing locally", raName, localOtid,
                    owner, error != null ? error.toString() : "no receiver");
            processLocally(pdu);
        });
        return true;
    }

    private void onForeign(Serializable message) {
        if (!(message instanceof ForeignTcapPduPayload payload)) {
            LOG.warn("[{}] unexpected foreign payload {}", raName, message == null ? "null" : message.getClass());
            return;
        }
        TCAPProvider provider = tcapProvider.get();
        ParameterFactory factory = parameterFactory.get();
        if (provider == null || factory == null) {
            LOG.warn("[{}] foreign PDU for DTID {} from node={} dropped: stack not running",
                    raName, payload.localOtid(), payload.sourceNodeId());
            return;
        }
        metrics.foreignReceived();
        provider.processForeignPdu(payload.toPdu(factory));
    }

    private void processLocally(TcapForeignPdu pdu) {
        TCAPProvider provider = tcapProvider.get();
        if (provider != null) {
            provider.processForeignPdu(pdu);
        }
    }

    private static boolean isTimeout(Throwable error) {
        Throwable t = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        for (; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException
                    || t instanceof org.infinispan.commons.TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** @return this node's published range, or {@code null} before {@link #start}. */
    public OtidRange localRange() {
        return localRange;
    }
}
