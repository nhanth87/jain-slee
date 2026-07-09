/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.jss7.collab.CapProtocolAdapter;
import com.microjainslee.ra.jss7.collab.MapProtocolAdapter;
import com.microjainslee.ra.jss7.collab.Ss7EventPublisher;
import com.microjainslee.ra.jss7.collab.Ss7ProtocolAdapter;
import com.microjainslee.ra.jss7.collab.Ss7TcapListener;
import com.microjainslee.ra.jss7.command.Ss7Command;
import com.microjainslee.ra.jss7.event.Ss7Event;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.restcomm.protocols.ss7.config.Ss7Config;
import org.restcomm.protocols.ss7.config.Ss7ConfigLoader;
import org.restcomm.protocols.ss7.config.Ss7Stack;
import org.restcomm.protocols.ss7.config.Ss7StackBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * jSS7 Resource Adaptor — a thin, vendor-neutral bridge between the jSS7 stack
 * and the SLEE event bus.
 *
 * <p>The stack itself (SCTP → M3UA → SCCP → TCAP → MAP/CAP) is <b>not</b> wired
 * here: {@code raActive()} loads the single-file {@link Ss7Config} and asks
 * {@link Ss7StackBuilder} (in the jSS7 {@code ss7-config} module) to compile and
 * start it, then holds only the returned {@link Ss7Stack} providers. That keeps
 * all per-layer jSS7 knowledge out of the RA.</p>
 *
 * <p>Inbound: protocol adapters ({@link Ss7TcapListener}, {@link MapProtocolAdapter},
 * {@link CapProtocolAdapter}) register listeners and publish typed events through
 * {@link #publish(Long, SleeEvent)}. Outbound: SBB commands go to the adapters
 * first, then fall back to {@link Ss7OutboundHandler} for the generic TCAP path.
 * Dialogs are keyed by the jSS7-native {@code Long} local dialog id.</p>
 */
public final class Ss7ResourceAdaptor
        implements AutoCloseable, Ss7EventPublisher, Ss7OutboundHandler.Ss7EventPublisherBridge {

    private static final Logger LOG = LogManager.getLogger(Ss7ResourceAdaptor.class);

    private volatile RaBootstrapPort bootstrap;
    private volatile String configPath = System.getProperty("ra.jss7.config", "ss7.json");
    private volatile Ss7Stack stack;
    private volatile Ss7OutboundHandler outbound;

    private final List<Ss7ProtocolAdapter> adapters = new ArrayList<>();
    private final Map<Long, MutableSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final IdleSweeper sweeper = new IdleSweeper();
    private int idleTimeoutSeconds = 300;

    // ── configuration ────────────────────────────────────────
    public void setBootstrapPort(RaBootstrapPort bp) { this.bootstrap = bp; }
    public RaBootstrapPort bootstrap() { return bootstrap; }
    /** Path to the single-file SS7 JSON config (default: {@code -Dra.jss7.config} or {@code ss7.json}). */
    public void setConfigPath(String path) { this.configPath = path; }
    public void setIdleTimeoutSeconds(int s) { this.idleTimeoutSeconds = s; }
    public Ss7Stack stack() { return stack; }

    // ── lifecycle ────────────────────────────────────────────
    public void raActive() {
        if (!active.compareAndSet(false, true)) return;
        try {
            Ss7Config cfg = Ss7ConfigLoader.load(Path.of(configPath));
            this.stack = Ss7StackBuilder.build(cfg);          // compiled + started
            this.outbound = new Ss7OutboundHandler(stack, sessions, this);

            adapters.clear();
            adapters.add(new Ss7TcapListener());
            if (cfg.protocols().map()) adapters.add(new MapProtocolAdapter());
            if (cfg.protocols().cap()) adapters.add(new CapProtocolAdapter());
            for (Ss7ProtocolAdapter a : adapters) {
                a.attach(stack, this);
            }

            sweeper.start(idleTimeoutSeconds);
            LOG.info("jSS7 RA activated (config={}, adapters={}, idleTimeout={}s)",
                    configPath, adapters.size(), idleTimeoutSeconds);
        } catch (RuntimeException e) {
            active.set(false);
            LOG.error("jSS7 RA activation failed", e);
            throw new IllegalStateException("jSS7 RA activation failed", e);
        }
    }

    public void raInactive() {
        if (!active.compareAndSet(true, false)) return;
        sweeper.stop();
        for (Ss7ProtocolAdapter a : adapters) {
            try { a.detach(); } catch (RuntimeException e) { LOG.warn("detach {} failed", a.protocol(), e); }
        }
        adapters.clear();
        outbound = null;
        if (stack != null) { stack.stop(); stack = null; }
        sessions.values().forEach(this::endActivity);
        sessions.clear();
        LOG.info("jSS7 RA deactivated");
    }

    public boolean isActive() { return active.get(); }

    // ── inbound: jSS7 → SLEE (Ss7EventPublisher + outbound bridge) ────────
    @Override
    public void publish(Long dialogId, SleeEvent event) {
        if (dialogId == null) { LOG.warn("dropping {} — null dialogId", event.getClass().getSimpleName()); return; }
        if (!active.get() || bootstrap == null) {
            LOG.warn("RA not active — dropping {} on {}", event.getClass().getSimpleName(), dialogId);
            return;
        }
        MutableSession s = sessions.computeIfAbsent(dialogId,
                id -> new MutableSession(id, bootstrap.createActivityHandle(String.valueOf(id))));
        s.touch();
        bootstrap.fireEvent(event, s.activityHandle, null);
        LOG.debug("Fired {} on dialog={}", event.getClass().getSimpleName(), dialogId);

        if (event instanceof Ss7Event.TcapEnd || event instanceof Ss7Event.TcapAbort) {
            forceEndSession(dialogId, sessions.get(dialogId));
        }
    }

    // ── outbound: SBB → jSS7 ─────────────────────────────────
    public void sendOutbound(Ss7Command cmd) {
        if (!active.get()) {
            LOG.warn("RA not active — dropping {}", cmd.getClass().getSimpleName());
            return;
        }
        // protocol adapters get first refusal (MAP/CAP typed commands)
        for (Ss7ProtocolAdapter a : adapters) {
            if (a.sendOutbound(cmd)) { touchSession(cmd.dialogId()); return; }
        }
        // generic TCAP dialog handling
        if (outbound != null) outbound.send(cmd);
    }

    // ── Ss7OutboundHandler.Ss7EventPublisherBridge ───────────
    @Override
    public ActivityHandle createActivityHandle(String id) {
        return bootstrap == null ? null : bootstrap.createActivityHandle(id);
    }

    @Override
    public void touchSession(Long did) {
        if (did == null) return;
        MutableSession s = sessions.get(did);
        if (s != null) s.touch();
    }

    // ── session management ────────────────────────────────────
    @Override
    public void forceEndSession(Long did, MutableSession s) {
        if (s == null) return;
        sessions.remove(did);
        endActivity(s);
        LOG.debug("Ended session {}", did);
    }

    private void endActivity(MutableSession s) {
        if (bootstrap != null && s.activityHandle != null) {
            bootstrap.endActivity(s.activityHandle);
        }
    }

    private void sweepIdle() {
        long cutoff = System.currentTimeMillis() - (idleTimeoutSeconds * 1000L);
        sessions.entrySet().removeIf(e -> {
            if (e.getValue().lastActivity < cutoff) {
                forceEndSession(e.getKey(), e.getValue());
                return true;
            }
            return false;
        });
    }

    @Override public void close() { raInactive(); }

    // ── idle sweeper (virtual-thread scheduler) ──────────────
    private final class IdleSweeper {
        private volatile Thread thread;
        private volatile boolean running;

        void start(int idleSeconds) {
            running = true;
            long periodMs = Math.max(1, idleSeconds / 2) * 1000L;
            thread = Thread.ofVirtual().name("ra-jss7-sweeper").start(() -> {
                while (running) {
                    try {
                        Thread.sleep(periodMs);
                        sweepIdle();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (RuntimeException e) {
                        LOG.warn("idle sweep failed", e);
                    }
                }
            });
        }

        void stop() {
            running = false;
            Thread t = thread;
            if (t != null) t.interrupt();
        }
    }

    // ── per-dialog session (keyed by jSS7 Long dialog id) ────
    public static final class MutableSession {
        final Long sessionId;
        final ActivityHandle activityHandle;
        /** The bound jSS7 {@code Dialog} (set by the outbound handler for BEGINs). */
        volatile Object jss7Dialog;
        final long createdAt;
        volatile long lastActivity;

        MutableSession(Long sid, ActivityHandle h) {
            this.sessionId = sid;
            this.activityHandle = h;
            this.createdAt = System.currentTimeMillis();
            this.lastActivity = this.createdAt;
        }

        void touch() { this.lastActivity = System.currentTimeMillis(); }
    }
}
