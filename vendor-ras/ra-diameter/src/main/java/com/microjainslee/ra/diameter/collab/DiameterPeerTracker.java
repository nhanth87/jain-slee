/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.collab;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Diameter peer plane state (RFC 6733 base protocol).
 *
 * <p><strong>Link-status truth:</strong> TCP accept / RA {@code isActive()} is
 * <em>not</em> peer UP. A peer is traffic-ready only after a successful
 * Capabilities-Exchange (CER/CEA with Result-Code 2001) and while the TCP
 * channel remains up. Device-Watchdog (DWR/DWA) refreshes liveness; Disconnect-Peer
 * (DPR/DPA) or channel close clears readiness immediately.</p>
 */
public final class DiameterPeerTracker {

    /** Diameter success Result-Code (DIAMETER_SUCCESS). */
    public static final long RESULT_SUCCESS = 2001L;

    /** Capabilities-Exchange command code (CER/CEA). */
    public static final int CMD_CAPABILITIES_EXCHANGE = 257;
    /** Device-Watchdog command code (DWR/DWA). */
    public static final int CMD_DEVICE_WATCHDOG = 280;
    /** Disconnect-Peer command code (DPR/DPA). */
    public static final int CMD_DISCONNECT_PEER = 282;

    /** What the RA should do after ingesting a base-protocol message. */
    public enum BaseAction {
        /** Not base protocol (or ignore); forward to application classifier. */
        NONE,
        /** Inbound CER — send CEA then {@link #markCapabilitiesExchanged(String)}. */
        ANSWER_CEA,
        /** Inbound DWR — send DWA (watchdog already refreshed). */
        ANSWER_DWA,
        /** Inbound DPR — send DPA; peer no longer ready. */
        ANSWER_DPA,
        /** Base answer consumed (CEA/DWA/DPA); do not fire to SBB. */
        CONSUMED
    }

    private final ConcurrentMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final long watchdogTimeoutNanos;

    /**
     * @param watchdogTimeoutMs max silence after last CE/DW/app activity before
     *        {@link #isPeerReady()} returns false; {@code <= 0} disables expiry
     *        (CE + TCP alone keep ready)
     */
    public DiameterPeerTracker(long watchdogTimeoutMs) {
        this.watchdogTimeoutNanos = watchdogTimeoutMs > 0
                ? watchdogTimeoutMs * 1_000_000L
                : 0L;
    }

    public void onTcpConnected(String peerId) {
        Objects.requireNonNull(peerId, "peerId");
        peers.compute(peerId, (id, existing) -> {
            Peer p = existing != null ? existing : new Peer();
            p.tcpUp = true;
            p.capabilitiesOk = false;
            p.lastActivityNanos = System.nanoTime();
            return p;
        });
    }

    public void onTcpDisconnected(String peerId) {
        if (peerId == null) return;
        peers.remove(peerId);
    }

    public void clear() {
        peers.clear();
    }

    /**
     * Ingest a Diameter message for peer-plane tracking.
     *
     * @param resultCode meaningful for answers (CEA); ignored for requests
     */
    public BaseAction onInbound(String peerId, int commandCode, boolean request, long resultCode) {
        if (peerId == null) return BaseAction.NONE;
        Peer p = peers.get(peerId);
        if (p == null || !p.tcpUp) return BaseAction.NONE;

        switch (commandCode) {
            case CMD_CAPABILITIES_EXCHANGE -> {
                if (request) {
                    return BaseAction.ANSWER_CEA;
                }
                if (resultCode == RESULT_SUCCESS) {
                    markOk(p);
                    return BaseAction.CONSUMED;
                }
                p.capabilitiesOk = false;
                return BaseAction.CONSUMED;
            }
            case CMD_DEVICE_WATCHDOG -> {
                touch(p);
                return request ? BaseAction.ANSWER_DWA : BaseAction.CONSUMED;
            }
            case CMD_DISCONNECT_PEER -> {
                p.capabilitiesOk = false;
                touch(p);
                return request ? BaseAction.ANSWER_DPA : BaseAction.CONSUMED;
            }
            default -> {
                if (p.capabilitiesOk) {
                    touch(p);
                }
                return BaseAction.NONE;
            }
        }
    }

    /** Call after a successful CEA (Result-Code 2001) was sent or received. */
    public void markCapabilitiesExchanged(String peerId) {
        Peer p = peers.get(peerId);
        if (p != null && p.tcpUp) {
            markOk(p);
        }
    }

    /** TCP channel open to at least one peer — still not CER/CEA ready. */
    public boolean isPeerConnected() {
        for (Peer p : peers.values()) {
            if (p.tcpUp) return true;
        }
        return false;
    }

    /**
     * At least one peer has completed CER/CEA successfully, TCP still up,
     * and (if configured) watchdog has not expired.
     */
    public boolean isPeerReady() {
        return isPeerReady(System.nanoTime());
    }

    boolean isPeerReady(long nowNanos) {
        for (Peer p : peers.values()) {
            if (!p.tcpUp || !p.capabilitiesOk) continue;
            if (watchdogTimeoutNanos > 0
                    && (nowNanos - p.lastActivityNanos) > watchdogTimeoutNanos) {
                continue;
            }
            return true;
        }
        return false;
    }

    public int peerCount() {
        return peers.size();
    }

    /** Short detail for status APIs / logs — never invents UP without CE. */
    public String detail() {
        if (peers.isEmpty()) {
            return "diameter:no-peer";
        }
        int tcp = 0;
        int ready = 0;
        for (Peer p : peers.values()) {
            if (p.tcpUp) tcp++;
            if (p.tcpUp && p.capabilitiesOk) ready++;
        }
        if (ready > 0) {
            return "diameter:peer-ready count=" + ready;
        }
        if (tcp > 0) {
            return "diameter:tcp-up awaiting-cer/cea count=" + tcp;
        }
        return "diameter:no-peer";
    }

    private static void markOk(Peer p) {
        p.capabilitiesOk = true;
        touch(p);
    }

    private static void touch(Peer p) {
        p.lastActivityNanos = System.nanoTime();
    }

    private static final class Peer {
        volatile boolean tcpUp;
        volatile boolean capabilitiesOk;
        volatile long lastActivityNanos;
    }
}
