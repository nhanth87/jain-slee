/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    /** M3UA AS traffic mode — shared SLS loadshare across ASPs (DESIGN §2 / §10.2 P4 default). */
    public static final String TRAFFIC_MODE_LOADSHARE = "loadshare";
    /** M3UA AS traffic mode — active-standby only (DESIGN §10.2 P4). */
    public static final String TRAFFIC_MODE_OVERRIDE = "override";

    private static final Logger LOG = LogManager.getLogger(Ss7RaConfig.class);

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
    /**
     * Cluster-wide local SCTP endpoints as {@code ip:port}. Empty → use
     * {@link #hostIp}:{@link #hostPort} only. Each RA node binds exactly one
     * entry (see {@link #sctpEndpointIndex()}).
     */
    private java.util.List<String> sctpLocalEndpoints = java.util.List.of();
    /**
     * Index into {@link #sctpLocalEndpoints()} for this node's steady-state bind
     * (0-based). Ignored when the endpoint list is empty.
     */
    private int sctpEndpointIndex = 0;

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

    // ── congestion / overload control (DESIGN §10.2 P1+P2+P4) ──
    /**
     * Maps to jSS7 {@code SccpStack.setCongControl_blockingOutgoingSccpMessages}.
     * When true, {@code SccpRoutingControl} drops outgoing SCCP messages whose
     * importance is below the remote SPC's current restriction level and answers
     * with NETWORK_CONGESTION / SUBSYSTEM_CONGESTION — the academically validated
     * STP behavior "drop low-importance first, alert, never queue unboundedly"
     * (P1/P2, DESIGN §10.2). Default false = jSS7 behavior unchanged.
     */
    private boolean congestionControlBlockingOutgoingSccpMessages = false;
    /**
     * Baseline SCCP restriction level applied to every remote SPC at stack start.
     * {@code 0} = unrestricted (default); {@code 1..8} block outgoing messages with
     * importance below the level, but only when
     * {@link #congestionControlBlockingOutgoingSccpMessages()} is true. Peer TFC
     * raises the level at runtime regardless — this is only the startup baseline.
     */
    private int defaultRestrictionLevel = 0;
    /**
     * M3UA AS traffic mode for the AS created by the flat-config path:
     * {@link #TRAFFIC_MODE_LOADSHARE} (default) or {@link #TRAFFIC_MODE_OVERRIDE}.
     * {@code broadcast} is rejected at set time (DESIGN §10.2/P4: never broadcast
     * on transit links).
     */
    private String defaultTrafficMode = TRAFFIC_MODE_LOADSHARE;

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
    public java.util.List<String> sctpLocalEndpoints() { return sctpLocalEndpoints; }
    public int sctpEndpointIndex()       { return sctpEndpointIndex; }
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
    public boolean congestionControlBlockingOutgoingSccpMessages() { return congestionControlBlockingOutgoingSccpMessages; }
    public int defaultRestrictionLevel() { return defaultRestrictionLevel; }
    public String defaultTrafficMode()   { return defaultTrafficMode; }

    // ── fluent setters ────────────────────────────────────────
    public Ss7RaConfig stackName(String v)            { this.stackName = v; return this; }
    public Ss7RaConfig hostIp(String v)               { this.hostIp = v; return this; }
    public Ss7RaConfig hostPort(int v)                { this.hostPort = v; return this; }
    public Ss7RaConfig peerIp(String v)               { this.peerIp = v; return this; }
    public Ss7RaConfig peerPort(int v)                { this.peerPort = v; return this; }
    public Ss7RaConfig associationName(String v)      { this.associationName = v; return this; }
    public Ss7RaConfig ipChannelType(String v)        { this.ipChannelType = v; return this; }
    public Ss7RaConfig sctpWorkerThreads(int v)       { this.sctpWorkerThreads = v; return this; }
    public Ss7RaConfig sctpLocalEndpoints(java.util.List<String> v) {
        this.sctpLocalEndpoints = v == null ? java.util.List.of() : java.util.List.copyOf(v);
        return this;
    }
    public Ss7RaConfig sctpEndpointIndex(int v)       { this.sctpEndpointIndex = v; return this; }
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
    public Ss7RaConfig congestionControlBlockingOutgoingSccpMessages(boolean v) {
        this.congestionControlBlockingOutgoingSccpMessages = v;
        return this;
    }

    /** @throws IllegalArgumentException outside {@code 0..8} (0 = unrestricted) */
    public Ss7RaConfig defaultRestrictionLevel(int v) {
        if (v < 0 || v > 8) {
            throw new IllegalArgumentException(
                    "defaultRestrictionLevel must be 0..8 (0 = unrestricted), got " + v);
        }
        this.defaultRestrictionLevel = v;
        return this;
    }

    /**
     * Accepts {@code loadshare} | {@code override} (case-insensitive, normalized to
     * lower case). {@code broadcast} and any other value are rejected.
     *
     * @throws IllegalArgumentException for broadcast ("broadcast forbidden on transit
     *         links — DESIGN §10.2/P4") or unknown values
     */
    public Ss7RaConfig defaultTrafficMode(String v) {
        this.defaultTrafficMode = normalizeTrafficMode(v);
        return this;
    }

    private static String normalizeTrafficMode(String v) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(
                    "defaultTrafficMode must be loadshare|override, got blank");
        }
        String mode = v.trim().toLowerCase(java.util.Locale.ROOT);
        if ("broadcast".equals(mode)) {
            throw new IllegalArgumentException(
                    "broadcast forbidden on transit links — DESIGN §10.2/P4");
        }
        if (!TRAFFIC_MODE_LOADSHARE.equals(mode) && !TRAFFIC_MODE_OVERRIDE.equals(mode)) {
            throw new IllegalArgumentException(
                    "defaultTrafficMode must be loadshare|override, got '" + v + "'");
        }
        return mode;
    }

    /**
     * DESIGN §10.2/P4 guardrail: {@code override} traffic mode is meant for
     * active-standby fabrics only — under an active-active fabric it concentrates
     * all SLS traffic on one ASP. Logs a WARN (and returns true) when override is
     * configured against a non-active-standby HA mode.
     *
     * @param haMode effective fabric mode (null counts as ACTIVE_ACTIVE default)
     * @return true when the warning was emitted
     */
    public boolean warnIfOverrideTrafficMode(StpTransitProfile.HaMode haMode) {
        StpTransitProfile.HaMode effective =
                haMode == null ? StpTransitProfile.HaMode.ACTIVE_ACTIVE : haMode;
        if (TRAFFIC_MODE_OVERRIDE.equals(defaultTrafficMode)
                && effective != StpTransitProfile.HaMode.ACTIVE_STANDBY) {
            LOG.warn("[ra-jss7] defaultTrafficMode=override with haMode={} — override is for "
                    + "active-standby only (DESIGN §10.2/P4); active-active should use loadshare",
                    effective);
            return true;
        }
        return false;
    }

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

    /**
     * Local SCTP bind for this node: {@code sctpLocalEndpoints[sctpEndpointIndex]}
     * or {@code hostIp:hostPort} when the list is empty.
     */
    public String resolvedLocalEndpoint() {
        if (sctpLocalEndpoints == null || sctpLocalEndpoints.isEmpty()) {
            return hostIp + ":" + hostPort;
        }
        if (sctpEndpointIndex < 0 || sctpEndpointIndex >= sctpLocalEndpoints.size()) {
            throw new IllegalArgumentException(
                    "sctpEndpointIndex=" + sctpEndpointIndex
                            + " out of range for sctpLocalEndpoints size="
                            + sctpLocalEndpoints.size());
        }
        return sctpLocalEndpoints.get(sctpEndpointIndex);
    }

    /** All cluster local endpoints (singleton list when only hostIp/hostPort set). */
    public java.util.List<String> allLocalEndpoints() {
        if (sctpLocalEndpoints == null || sctpLocalEndpoints.isEmpty()) {
            return java.util.List.of(hostIp + ":" + hostPort);
        }
        return sctpLocalEndpoints;
    }

    @Override
    public String toString() {
        return "Ss7RaConfig{" + stackName + " local=" + resolvedLocalEndpoint()
                + " -> " + peerIp + ":" + peerPort + " opc=" + originatingPointCode
                + " dpc=" + destinationPointCode + " ssn=" + localSsn
                + " endpoints=" + allLocalEndpoints()
                + " otid=[" + dialogIdRangeStart + "," + dialogIdRangeEnd + "]"
                + " map=" + mapEnabled + " cap=" + capEnabled
                + " congBlock=" + congestionControlBlockingOutgoingSccpMessages
                + " restrictionLevel=" + defaultRestrictionLevel
                + " trafficMode=" + defaultTrafficMode + "}";
    }
}
