/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Diameter RA config — camel-generic (any Diameter app). */
public final class DiameterRaConfig {

    /** Diameter application ids by the short names accepted in {@link #parsePeers}. */
    public static final long APP_S6A = 16_777_251L;
    public static final long APP_CX = 16_777_216L;
    public static final long APP_GX = 16_777_238L;
    public static final long APP_RX = 16_777_236L;
    public static final long APP_CREDIT_CONTROL = 4L;

    /**
     * One Diameter peer = one link. {@code role} "server" accepts the peer's connection on
     * {@link #port()}, "client" dials it; {@code applicationIds} (may be empty) route
     * outbound requests to this peer when no Destination-Host matches.
     */
    public record Peer(String host, int port, String role, String destinationHost,
                       String destinationRealm, Set<Long> applicationIds) {
        public Peer {
            role = role == null || role.isBlank() ? "client" : role.trim();
            applicationIds = applicationIds == null ? Set.of() : Set.copyOf(applicationIds);
        }

        public boolean server() {
            return !"client".equalsIgnoreCase(role);
        }
    }

    private String host = "0.0.0.0";
    private int port = 3868;
    private String realm = "mobicents.org";
    private String originHost = "server.mobicents.org";
    private String productName = "micro-jainslee-diameter";
    private long vendorId = 0;
    private boolean tcpEnabled = true;
    private boolean sctpEnabled = false;
    private String peerHost = "127.0.0.1";
    private int peerPort = 3868;
    private String destinationHost = "peer.epc.lab";
    private String destinationRealm = "epc.lab";
    /** "server" = accept inbound Diameter peer (listen); "client" = dial peer + initiate CER. */
    private String peerRole = "server";
    /** Tw-style silence limit for peer-ready (0 = no expiry after CER/CEA). Default 30s. */
    private long watchdogTimeoutMs = 30_000L;
    /** Tx timer for outbound requests (RFC 6733 §7 / RFC 4006 Tx); 0 = no timeout event. */
    private long requestTimeoutMs = 0L;
    /**
     * Corsac WorkerPool poll interval. Corsac queues every inbound message on a per-session worker's
     * local queue, which the worker only checks between blocking polls of the shared queue — the
     * mobius default of 100 ms held each Diameter message ≈ 50 ms on average (Gx CCR in the PCRF,
     * CCA in the PGW-C). 1 ms keeps per-session FIFO and costs one wake-up per worker per ms.
     */
    private long workerPollMs = 1L;
    /**
     * Re-resolve peers configured by DNS name every this many ms while their link is not OPEN; 0 = never.
     * Corsac resolves a peer once: a server-role link only accepts the configured client IP, a client link
     * keeps dialling the old one. On Kubernetes a pod gets a new IP on every restart.
     */
    private long peerRefreshMs = 10_000L;
    /** Several peers (one link each); empty = the single legacy peer below. */
    private List<Peer> peers = List.of();

    public DiameterRaConfig() {}

    // --- getters / setters ---
    public String host() { return host; }
    public void setHost(String v) { host = v; }
    public int port() { return port; }
    public void setPort(int v) { port = v; }
    public String realm() { return realm; }
    public void setRealm(String v) { realm = v; }
    public String originHost() { return originHost; }
    public void setOriginHost(String v) { originHost = v; }
    public String productName() { return productName; }
    public void setProductName(String v) { productName = v; }
    public long vendorId() { return vendorId; }
    public void setVendorId(long v) { vendorId = v; }
    public boolean tcpEnabled() { return tcpEnabled; }
    public void setTcpEnabled(boolean v) { tcpEnabled = v; }
    public boolean sctpEnabled() { return sctpEnabled; }
    public void setSctpEnabled(boolean v) { sctpEnabled = v; }
    public String peerHost() { return peerHost; }
    public void setPeerHost(String v) { peerHost = v; }
    public int peerPort() { return peerPort; }
    public void setPeerPort(int v) { peerPort = v; }
    public String destinationHost() { return destinationHost; }
    public void setDestinationHost(String v) { destinationHost = v; }
    public String destinationRealm() { return destinationRealm; }
    public void setDestinationRealm(String v) { destinationRealm = v; }
    public String peerRole() { return peerRole; }
    public void setPeerRole(String v) { peerRole = v; }
    public long watchdogTimeoutMs() { return watchdogTimeoutMs; }
    public void setWatchdogTimeoutMs(long v) { watchdogTimeoutMs = v; }
    public long requestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(long v) { requestTimeoutMs = Math.max(0L, v); }
    public long workerPollMs() { return workerPollMs; }
    public void setWorkerPollMs(long v) { workerPollMs = Math.max(1L, v); }
    public long peerRefreshMs() { return peerRefreshMs; }
    public void setPeerRefreshMs(long v) { peerRefreshMs = Math.max(0L, v); }
    public List<Peer> peers() { return peers; }
    public void setPeers(List<Peer> v) { peers = v == null ? List.of() : List.copyOf(v); }

    /** The configured peers, or the single legacy peer (peerHost/peerPort/peerRole/destination*). */
    public List<Peer> effectivePeers() {
        if (!peers.isEmpty()) {
            return peers;
        }
        // A server peer's port 0 = any source port (the peer dials from an ephemeral port).
        boolean client = "client".equalsIgnoreCase(peerRole);
        int p = peerPort > 0 ? peerPort : (client ? port : 0);
        return List.of(new Peer(peerHost, p, peerRole, destinationHost, destinationRealm, Set.of()));
    }

    /**
     * Parse {@code host:port:role[:destinationHost[:destinationRealm[:app+app]]]} entries
     * separated by {@code ;} — apps by id or name (s6a, cx, gx, rx, cc). Example:
     * {@code mme:3868:client:mme.epc.lab:epc.lab:s6a;icscf:3868:client:icscf.ims.lab:ims.lab:cx}.
     */
    public static List<Peer> parsePeers(String spec) {
        List<Peer> out = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String entry : spec.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] f = entry.trim().split(":", -1);
            if (f.length < 2) {
                throw new IllegalArgumentException("diameter peer needs host:port — " + entry);
            }
            Set<Long> apps = new LinkedHashSet<>();
            if (f.length > 5 && !f[5].isBlank()) {
                for (String a : f[5].split("\\+")) {
                    apps.add(applicationId(a.trim()));
                }
            }
            out.add(new Peer(f[0].trim(), Integer.parseInt(f[1].trim()),
                    f.length > 2 ? f[2].trim() : "client",
                    f.length > 3 && !f[3].isBlank() ? f[3].trim() : null,
                    f.length > 4 && !f[4].isBlank() ? f[4].trim() : null,
                    apps));
        }
        return out;
    }

    private static long applicationId(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "s6a" -> APP_S6A;
            case "cx", "cxdx" -> APP_CX;
            case "gx" -> APP_GX;
            case "rx" -> APP_RX;
            case "cc", "gy", "ro" -> APP_CREDIT_CONTROL;
            default -> Long.parseLong(name);
        };
    }
}
