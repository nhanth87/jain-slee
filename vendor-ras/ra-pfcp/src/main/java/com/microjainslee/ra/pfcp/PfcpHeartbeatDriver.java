package com.microjainslee.ra.pfcp;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Proactive PFCP heartbeat: per-associated-peer send loop + timeout → recovery. */
final class PfcpHeartbeatDriver {

    static final long DEFAULT_INTERVAL_MS = 30_000L;
    static final long DEFAULT_TIMEOUT_MS = 90_000L;

    interface Handler {
        void sendHeartbeat(InetSocketAddress peer);

        void heartbeatTimedOut(InetSocketAddress peer);
    }

    private final long intervalMs;
    private final long timeoutMs;
    private final Handler handler;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Tick> ticks = new ConcurrentHashMap<>();

    PfcpHeartbeatDriver(long intervalMs, long timeoutMs, Handler handler) {
        if (intervalMs <= 0) {
            throw new IllegalArgumentException("heartbeat interval must be > 0 ms");
        }
        if (timeoutMs < intervalMs) {
            throw new IllegalArgumentException("heartbeat timeout must be >= interval");
        }
        this.intervalMs = intervalMs;
        this.timeoutMs = timeoutMs;
        this.handler = handler;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pfcp-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    void associate(InetSocketAddress peer) {
        String k = key(peer);
        Tick prev = ticks.remove(k);
        if (prev != null) {
            prev.cancel();
        }
        Tick t = new Tick(peer);
        ticks.put(k, t);
        t.future = scheduler.scheduleAtFixedRate(() -> tick(k),
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        t.sent = true;
        t.answered = false;
        t.lastSent = System.nanoTime();
        handler.sendHeartbeat(peer);
    }

    void onResponse(InetSocketAddress peer) {
        Tick t = ticks.get(key(peer));
        if (t != null) {
            t.answered = true;
        }
    }

    void disassociate(InetSocketAddress peer) {
        Tick t = ticks.remove(key(peer));
        if (t != null) {
            t.cancel();
        }
    }

    void shutdown() {
        for (Tick t : ticks.values()) {
            t.cancel();
        }
        ticks.clear();
        scheduler.shutdownNow();
    }

    int size() {
        return ticks.size();
    }

    private void tick(String k) {
        Tick t = ticks.get(k);
        if (t == null) {
            return;
        }
        long now = System.nanoTime();
        if (t.sent && !t.answered) {
            if (now - t.lastSent >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                ticks.remove(k, t);
                t.cancel();
                handler.heartbeatTimedOut(t.peer);
            }
            return; // waiting for a response
        }
        t.sent = true;
        t.answered = false;
        t.lastSent = now;
        handler.sendHeartbeat(t.peer);
    }

    private static String key(InetSocketAddress peer) {
        return peer.toString();
    }

    private static final class Tick {
        final InetSocketAddress peer;
        volatile boolean sent;
        volatile boolean answered;
        volatile long lastSent;
        volatile ScheduledFuture<?> future;

        Tick(InetSocketAddress peer) {
            this.peer = peer;
        }

        void cancel() {
            ScheduledFuture<?> f = future;
            if (f != null) {
                f.cancel(false);
            }
        }
    }
}