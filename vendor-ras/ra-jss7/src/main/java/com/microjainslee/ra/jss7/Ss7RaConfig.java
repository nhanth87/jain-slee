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
 * <h3>System property overrides</h3>
 * <p>Every field can be overridden via {@code -D} system properties at startup.
 * Properties are read once at construction time via {@link #fromSystemProperties()}.
 * This enables production tuning without code changes:</p>
 * <pre>{@code
 *   -Dra.jss7.delivery-threads=16   // M3UA message delivery threads (default: CPUs)
 *   -Dra.jss7.sctp-worker-threads=16 // SCTP I/O worker threads (default: CPUs)
 *   -Dra.jss7.max-dialogs=20000      // TCAP max concurrent dialogs
 *   -Dra.jss7.dialog-idle-timeout=60000 // TCAP idle timeout (ms)
 * }</pre>
 *
 * <h3>Thread model</h3>
 * <p>The SS7 stack uses two thread pools:</p>
 * <ul>
 *   <li><b>SCTP worker threads</b> ({@code sctpWorkerThreads}) — I/O thread pool
 *       for SCTP/TCP transport layer. Default: {@code Runtime.availableProcessors()}.</li>
 *   <li><b>M3UA delivery threads</b> ({@code deliveryMessageThreadCount}) — message
 *       processing pool for M3UA → SCCP → TCAP pipeline. Default: same as SCTP workers.
 *       <b>This is the primary bottleneck.</b> Must be &ge; SCTP workers to avoid
 *       serialization through a single thread.</li>
 * </ul>
 */
public final class Ss7RaConfig {

    /** System property prefix for all config overrides. */
    public static final String PROP_PREFIX = "ra.jss7.";

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
	
    // ── identity ──────────────────────────────────────────────
    private String stackName = prop("stack-name", "ra-jss7");
	
    // ── SCTP ──────────────────────────────────────────────────
    private String hostIp = prop("host-ip", "127.0.0.1");
    private int hostPort = propInt("host-port", 2905);
    private String peerIp = prop("peer-ip", "127.0.0.1");
    private int peerPort = propInt("peer-port", 2906);
    private String associationName = prop("association-name", "ra-jss7-assoc");
    /** "SCTP" or "TCP" (falls back to TCP when native SCTP is unavailable). */
    private String ipChannelType = prop("channel-type", "SCTP");
    private int sctpWorkerThreads = propInt("sctp-worker-threads", CPU_COUNT);
	
    // ── M3UA ──────────────────────────────────────────────────
    private long routingContext = propLong("routing-context", 100);
    private long networkAppearance = propLong("network-appearance", 102);
    private int originatingPointCode = propInt("opc", 1);
    private int destinationPointCode = propInt("dpc", 2);
    private int serviceIndicator = propInt("service-indicator", 3);   // SCCP
    /** true → IPSP CLIENT exchange, false → AS/ASP (SGW) mode. */
    private boolean ipspClient = propBool("ipsp-client", true);
    /** M3UA delivery threads — MUST be &ge; sctpWorkerThreads to avoid bottleneck. */
    private int deliveryMessageThreadCount = propInt("delivery-threads", CPU_COUNT);

    // ── SCCP ──────────────────────────────────────────────────
    private int networkIndicator = 2;   // national
    private int remoteSsn = 8;           // peer MAP/CAP SSN
    private int localSsn = 8;            // our SSN

    // ── TCAP ──────────────────────────────────────────────────
    private long dialogIdleTimeoutMs = 300_000;
    private long invokeTimeoutMs = 120_000;
    private int maxDialogs = 5000;

    // ── protocol toggles ──────────────────────────────────────
    private boolean mapEnabled = true;
    private boolean capEnabled = true;

    public Ss7RaConfig() { }

    /**
     * Factory: read all config from system properties, falling back to defaults.
     * <pre>{@code
     *   Ss7RaConfig cfg = Ss7RaConfig.fromSystemProperties();
     * }</pre>
     */
    public static Ss7RaConfig fromSystemProperties() {
        return new Ss7RaConfig();
    }

    // ── system property helpers ──────────────────────────────
    private static String prop(String key, String dflt) {
        String v = System.getProperty(PROP_PREFIX + key);
        return (v == null || v.isBlank()) ? dflt : v;
    }
    private static int propInt(String key, int dflt) {
        String v = System.getProperty(PROP_PREFIX + key);
        if (v == null || v.isBlank()) return dflt;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }
    private static long propLong(String key, long dflt) {
        String v = System.getProperty(PROP_PREFIX + key);
        if (v == null || v.isBlank()) return dflt;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }
    private static boolean propBool(String key, boolean dflt) {
        String v = System.getProperty(PROP_PREFIX + key);
        if (v == null || v.isBlank()) return dflt;
        return Boolean.parseBoolean(v.trim());
    }
	
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
    public Ss7RaConfig mapEnabled(boolean v)          { this.mapEnabled = v; return this; }
    public Ss7RaConfig capEnabled(boolean v)          { this.capEnabled = v; return this; }

    @Override
    public String toString() {
        return "Ss7RaConfig{"
                + stackName + " " + hostIp + ":" + hostPort
                + " -> " + peerIp + ":" + peerPort
                + " opc=" + originatingPointCode
                + " dpc=" + destinationPointCode
                + " ssn=" + localSsn
                + " sctpWorkers=" + sctpWorkerThreads
                + " m3uaDelivery=" + deliveryMessageThreadCount
                + " map=" + mapEnabled + " cap=" + capEnabled
                + " maxDialogs=" + maxDialogs
                + "}";
    }

    /** Returns a one-line summary suitable for logging. */
    public String toSummary() {
        return String.format("[%s] sctp:%d m3ua:%d dialogs:%d map:%s cap:%s",
                stackName, sctpWorkerThreads, deliveryMessageThreadCount,
                maxDialogs, mapEnabled, capEnabled);
    }
}
