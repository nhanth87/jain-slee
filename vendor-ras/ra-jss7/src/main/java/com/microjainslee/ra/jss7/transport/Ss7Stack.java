/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.transport;

import com.microjainslee.ra.jss7.Ss7RaConfig;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.cap.api.CAPProvider;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7StackBuilder;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.sccp.SccpProvider;
import org.restcomm.protocols.ss7.tcap.api.TCAPProvider;

import java.util.List;

/**
 * Bootstraps and owns the full RestComm jSS7 protocol stack for the RA:
 * <pre>SCTP (Netty) → M3UA → SCCP (+ext) → TCAP → MAP / CAP</pre>
 *
 * <p>Delegates the actual bootstrap to jSS7's {@code ss7-config} module
 * ({@link Ss7StackBuilder}) — the same JSON-config-driven compiler proven by
 * the jSS7 {@code map/load} test harness (USSD + MO/MT-SMS load tests). This
 * class only translates the RA's flat {@link Ss7RaConfig} into the neutral
 * {@link Ss7Config} model and re-exposes the provider accessors the listener
 * adapters / outbound sender use; it no longer hand-rolls the SCTP/M3UA/SCCP
 * wiring itself.</p>
 *
 * <p>{@link Ss7RaConfig} describes a single association / single AS / single
 * local point-code topology (one signalling relationship per RA instance),
 * always dialing out as an SCTP client — matching this RA's original
 * behavior. It also assumes a symmetric local/remote SSN (jSS7's typical
 * topology): {@link Ss7RaConfig#localSsn()} drives both the TCAP local SSN
 * and the auto-derived remote SSN at the peer point code. Deployments needing
 * asymmetric SSNs or a multi-link topology should build a richer
 * {@link Ss7Config} directly instead of going through {@link Ss7RaConfig}.</p>
 */
public final class Ss7Stack {

    private static final Logger LOG = LogManager.getLogger(Ss7Stack.class);

    private final Ss7RaConfig flatCfg;     // nullable when built from full Ss7Config
    private final Ss7Config fullCfg;       // nullable when built from flat Ss7RaConfig

    private org.restcomm.protocols.ss7.config.Ss7Stack delegate;
    private volatile boolean started;

    public Ss7Stack(Ss7RaConfig cfg) {
        this.flatCfg = cfg;
        this.fullCfg = null;
    }

    /** Multi-link / multi-AS topology — preferred production path. */
    public Ss7Stack(Ss7Config cfg) {
        this.flatCfg = null;
        this.fullCfg = cfg;
    }

    // ── provider accessors (used by listener adapters / outbound sender) ──
    public TCAPProvider tcapProvider() { return delegate.tcapProvider(); }
    public SccpProvider sccpProvider() { return delegate.sccpProvider(); }
    public MAPProvider mapProvider()   { return delegate.mapProvider(); }
    public CAPProvider capProvider()   { return delegate.capProvider(); }
    public boolean isStarted()         { return started; }
    public Ss7Config resolvedConfig()  { return fullCfg != null ? fullCfg : toSs7Config(flatCfg); }

    /** Underlying ss7-config stack — for admin status (SCTP/M3UA). Null if not started. */
    public org.restcomm.protocols.ss7.config.Ss7Stack underlying() {
        return delegate;
    }

    // ── lifecycle ─────────────────────────────────────────────
    public synchronized void start() throws Exception {
        if (started) return;
        Ss7Config built = fullCfg != null ? fullCfg : toSs7Config(flatCfg);
        LOG.info("[ra-jss7] bootstrapping jSS7 stack: {}",
                fullCfg != null ? "Ss7Config stackName=" + built.stackName() : flatCfg);
        delegate = Ss7StackBuilder.build(built);
        started = true;
        boolean map = built.protocols() != null && Boolean.TRUE.equals(built.protocols().map());
        boolean cap = built.protocols() != null && Boolean.TRUE.equals(built.protocols().cap());
        LOG.info("[ra-jss7] jSS7 stack STARTED (map={} cap={})", map, cap);
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        if (delegate != null) delegate.stop();
        LOG.info("[ra-jss7] jSS7 stack STOPPED");
    }

    // ── Ss7RaConfig -> Ss7Config translation ───────────────────
    private static Ss7Config toSs7Config(Ss7RaConfig cfg) {
        String linkName = cfg.associationName();

        var protocols = new Ss7Config.Protocols(cfg.mapEnabled(), cfg.capEnabled(), false);

        var link = new Ss7Config.Link(
                linkName,
                cfg.hostIp() + ":" + cfg.hostPort(),
                cfg.peerIp() + ":" + cfg.peerPort(),
                null,                                   // localSecondary (no multi-homing)
                cfg.ipChannelType().toLowerCase(),       // "sctp" | "tcp"
                "client",                                // this RA always dials out
                null,                                    // server name — n/a for type=client
                null,                                    // aspId — sequential default
                null);                                   // heartbeat — default false
        var sctp = new Ss7Config.Sctp(1000, cfg.sctpWorkerThreads(), 256, 256, List.of(link));

        var as = new Ss7Config.As(
                "AS1",
                "loadshare",
                cfg.ipspClient() ? "ipsp" : "as",
                cfg.ipspClient() ? "client" : null,
                "se",
                cfg.routingContext(),
                cfg.networkAppearance(),
                1,
                List.of(linkName));
        var route = new Ss7Config.Route(
                new Ss7Config.Dest(cfg.destinationPointCode(), cfg.originatingPointCode(), cfg.serviceIndicator()),
                "AS1");
        var m3ua = new Ss7Config.M3ua(0, cfg.deliveryMessageThreadCount(), List.of(as), List.of(route));

        var localPoint = new Ss7Config.LocalPoint(
                cfg.originatingPointCode(),
                networkIndicatorName(cfg.networkIndicator()),
                0,
                List.of(cfg.destinationPointCode()));
        var wildcard = new Ss7Config.Addr(null, null, "*", null, null, null, null);
        var toLocal = new Ss7Config.Addr(cfg.originatingPointCode(), null, null, null, null, null, null);
        var toRemote = new Ss7Config.Addr(cfg.destinationPointCode(), null, null, null, null, null, null);
        var ruleInbound = new Ss7Config.Rule("remote", 0, "K", wildcard, toLocal, null);
        var ruleOutbound = new Ss7Config.Rule("local", 0, "K", wildcard, toRemote, null);
        var sccp = new Ss7Config.Sccp(List.of(localPoint), List.of(ruleInbound, ruleOutbound));

        var tcap = new Ss7Config.Tcap(
                cfg.dialogIdleTimeoutMs(), cfg.invokeTimeoutMs(), cfg.maxDialogs(), 0, 0, false, false);

        String protocol = cfg.mapEnabled() ? "map" : (cfg.capEnabled() ? "cap" : "tcap");
        var service = new Ss7Config.Service("primary", cfg.localSsn(), protocol);

        return new Ss7Config(cfg.stackName(), protocols, sctp, m3ua, sccp, tcap, List.of(service));
    }

    /** MTP3 network indicator: 0=international, 1=spare, 2=national, 3=reserved. */
    private static String networkIndicatorName(int ni) {
        return switch (ni) {
            case 0 -> "international";
            case 1 -> "spare";
            case 3 -> "reserved";
            default -> "national";
        };
    }
}
