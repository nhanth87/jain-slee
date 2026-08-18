/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter;

/** Diameter RA config — camel-generic (any Diameter app). */
public final class DiameterRaConfig {
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
}
