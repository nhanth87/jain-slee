/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

/**
 * Configuration for the jSS7 protocol stack bootstrapped by
 * {@link com.microjainslee.ra.jss7.transport.Ss7Stack}.
 *
 * <p>Mutable POJO with fluent setters so the RA (or a deployment descriptor)
 * can populate it before {@code raActive()}. Defaults describe a single
 * IPSP-client association suitable for a local loopback test; override for
 * real deployments.</p>
 *
 * <p>For multi-node OTID partitioning set {@link #dialogIdRangeStart()} /
 * {@link #dialogIdRangeEnd()} to non-overlapping ranges (both {@code > 0},
 * start {@code <} end). {@code 0,0} keeps jSS7 stack defaults. See
 * {@code docs/adr/0001-ss7-ra-nn-tcap-failover.md}.</p>
 */
public final class Ss7RaConfig {

    // ── identity ──────────────────────────────────────────────
    private String stackName = "ra-jss7";

    // ── SCTP ──────────────────────────────────────────────────
    private String hostIp = "127.0.0.1";
    private int hostPort = 2905;
    private String peerIp = "127.0.0.1";
    private int peerPort = 2906;
    private String associationName = "ra-jss7-assoc";
    /** "SCTP" or "TCP" (falls back to TCP when native SCTP is unavailable). */
    private String ipChannelType = "SCTP";
    private int sctpWorkerThreads = 16;

    // ── M3UA ──────────────────────────────────────────────────
    private long routingContext = 100;
    private long networkAppearance = 102;
    private int originatingPointCode = 1;
    private int destinationPointCode = 2;
    private int serviceIndicator = 3;   // SCCP
    /** true → IPSP CLIENT exchange, false → AS/ASP (SGW) mode. */
    private boolean ipspClient = true;
    private int deliveryMessageThreadCount = 1;

    // ── SCCP ──────────────────────────────────────────────────
    private int networkIndicator = 2;   // national
    private int remoteSsn = 8;           // peer MAP/CAP SSN
    private int localSsn = 8;            // our SSN

    // ── TCAP ──────────────────────────────────────────────────
    private long dialogIdleTimeoutMs = 300_000;
    private long invokeTimeoutMs = 120_000;
    private int maxDialogs = 5000;
    /**
     * Inclusive OTID range start. {@code 0} with {@link #dialogIdRangeEnd}{@code 0}
     * means use jSS7 defaults. Multi-RA: partition non-overlapping ranges.
     */
    private long dialogIdRangeStart = 0;
    /** Inclusive OTID range end (see {@link #dialogIdRangeStart}). */
    private long dialogIdRangeEnd = 0;

    // ── protocol toggles ──────────────────────────────────────
    private boolean mapEnabled = true;
    private boolean capEnabled = true;

    public Ss7RaConfig() { }

    // ── getters ───────────────────────────────────────────────
    public String stackName()            { return stackName; }
    public String hostIp()               { return hostIp; }
    public int hostPort()                { return hostPort; }
    public String peerIp()               { return peerIp; }
    public int peerPort()                { return peerPort; }
    public String associationName()      { return associationName; }
    public String ipChannelType()        { return ipChannelType; }
    public int sctpWorkerThreads()       { return sctpWorkerThreads; }
    public long routingContext()         { return routingContext; }
    public long networkAppearance()      { return networkAppearance; }
    public int originatingPointCode()    { return originatingPointCode; }
    public int destinationPointCode()    { return destinationPointCode; }
    public int serviceIndicator()        { return serviceIndicator; }
    public boolean ipspClient()          { return ipspClient; }
    public int deliveryMessageThreadCount() { return deliveryMessageThreadCount; }
    public int networkIndicator()        { return networkIndicator; }
    public int remoteSsn()               { return remoteSsn; }
    public int localSsn()                { return localSsn; }
    public long dialogIdleTimeoutMs()    { return dialogIdleTimeoutMs; }
    public long invokeTimeoutMs()        { return invokeTimeoutMs; }
    public int maxDialogs()              { return maxDialogs; }
    public long dialogIdRangeStart()     { return dialogIdRangeStart; }
    public long dialogIdRangeEnd()       { return dialogIdRangeEnd; }
    public boolean mapEnabled()          { return mapEnabled; }
    public boolean capEnabled()          { return capEnabled; }

    // ── fluent setters ────────────────────────────────────────
    public Ss7RaConfig stackName(String v)            { this.stackName = v; return this; }
    public Ss7RaConfig hostIp(String v)               { this.hostIp = v; return this; }
    public Ss7RaConfig hostPort(int v)                { this.hostPort = v; return this; }
    public Ss7RaConfig peerIp(String v)               { this.peerIp = v; return this; }
    public Ss7RaConfig peerPort(int v)                { this.peerPort = v; return this; }
    public Ss7RaConfig associationName(String v)      { this.associationName = v; return this; }
    public Ss7RaConfig ipChannelType(String v)        { this.ipChannelType = v; return this; }
    public Ss7RaConfig sctpWorkerThreads(int v)       { this.sctpWorkerThreads = v; return this; }
    public Ss7RaConfig routingContext(long v)         { this.routingContext = v; return this; }
    public Ss7RaConfig networkAppearance(long v)      { this.networkAppearance = v; return this; }
    public Ss7RaConfig originatingPointCode(int v)    { this.originatingPointCode = v; return this; }
    public Ss7RaConfig destinationPointCode(int v)    { this.destinationPointCode = v; return this; }
    public Ss7RaConfig serviceIndicator(int v)        { this.serviceIndicator = v; return this; }
    public Ss7RaConfig ipspClient(boolean v)          { this.ipspClient = v; return this; }
    public Ss7RaConfig deliveryMessageThreadCount(int v) { this.deliveryMessageThreadCount = v; return this; }
    public Ss7RaConfig networkIndicator(int v)        { this.networkIndicator = v; return this; }
    public Ss7RaConfig remoteSsn(int v)               { this.remoteSsn = v; return this; }
    public Ss7RaConfig localSsn(int v)                { this.localSsn = v; return this; }
    public Ss7RaConfig dialogIdleTimeoutMs(long v)    { this.dialogIdleTimeoutMs = v; return this; }
    public Ss7RaConfig invokeTimeoutMs(long v)        { this.invokeTimeoutMs = v; return this; }
    public Ss7RaConfig maxDialogs(int v)              { this.maxDialogs = v; return this; }
    public Ss7RaConfig dialogIdRangeStart(long v)     { this.dialogIdRangeStart = v; return this; }
    public Ss7RaConfig dialogIdRangeEnd(long v)       { this.dialogIdRangeEnd = v; return this; }
    public Ss7RaConfig mapEnabled(boolean v)          { this.mapEnabled = v; return this; }
    public Ss7RaConfig capEnabled(boolean v)          { this.capEnabled = v; return this; }

    /**
     * Validate OTID range: both 0 (defaults) or {@code start > 0 && end > start}.
     *
     * @throws IllegalArgumentException when the range is inconsistent
     */
    public void validateDialogIdRange() {
        if (dialogIdRangeStart == 0 && dialogIdRangeEnd == 0) {
            return;
        }
        if (dialogIdRangeStart <= 0 || dialogIdRangeEnd <= dialogIdRangeStart) {
            throw new IllegalArgumentException(
                    "dialogIdRange must be both 0 (defaults) or start>0 and end>start; got ["
                            + dialogIdRangeStart + ", " + dialogIdRangeEnd + "]");
        }
    }

    @Override
    public String toString() {
        return "Ss7RaConfig{" + stackName + " " + hostIp + ":" + hostPort
                + " -> " + peerIp + ":" + peerPort + " opc=" + originatingPointCode
                + " dpc=" + destinationPointCode + " ssn=" + localSsn
                + " otid=[" + dialogIdRangeStart + "," + dialogIdRangeEnd + "]"
                + " map=" + mapEnabled + " cap=" + capEnabled + "}";
    }
}
