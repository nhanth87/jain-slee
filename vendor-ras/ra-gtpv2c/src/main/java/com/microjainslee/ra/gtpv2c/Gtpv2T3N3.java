package com.microjainslee.ra.gtpv2c;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TS 29.274 T3/N3 for outstanding <em>requests this node sent</em>.
 * Default T3 = 3s, N3 = 3 retransmissions (initial send + up to 3 retries).
 * Responses are never tracked. No GTP-U. Single daemon scheduler, no Netty.
 */
final class Gtpv2T3N3 {

    static final long DEFAULT_T3_MS = 3_000L;
    static final int DEFAULT_N3 = 3;

    interface Handler {
        void resend(byte[] wire, InetSocketAddress peer);

        void echoExhausted(InetSocketAddress peer);

        void procedureExhausted(Gtpv2MessageType type, int sequence, InetSocketAddress peer);
    }

    private final long t3Ms;
    private final int n3;
    private final Handler handler;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Outstanding> outstanding = new ConcurrentHashMap<>();

    Gtpv2T3N3(long t3Ms, int n3, Handler handler) {
        if (t3Ms <= 0) {
            throw new IllegalArgumentException("T3 must be > 0 ms");
        }
        if (n3 < 0) {
            throw new IllegalArgumentException("N3 must be >= 0");
        }
        this.t3Ms = t3Ms;
        this.n3 = n3;
        this.handler = Objects.requireNonNull(handler);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gtpv2c-t3");
            t.setDaemon(true);
            return t;
        });
    }

    long t3Millis() {
        return t3Ms;
    }

    int n3() {
        return n3;
    }

    int size() {
        return outstanding.size();
    }

    void track(InetSocketAddress peer, int sequence, Gtpv2MessageType type, byte[] wire) {
        if (!type.isRequest()) {
            return;
        }
        String key = key(peer, sequence);
        Outstanding prev = outstanding.remove(key);
        if (prev != null) {
            prev.cancel();
        }
        Outstanding o = new Outstanding(peer, wire.clone(), sequence, type, n3);
        outstanding.put(key, o);
        o.timer = scheduler.schedule(() -> onT3(key), t3Ms, TimeUnit.MILLISECONDS);
    }

    void complete(InetSocketAddress peer, int sequence) {
        Outstanding o = outstanding.remove(key(peer, sequence));
        if (o != null) {
            o.cancel();
        }
    }

    void cancelAll() {
        for (Outstanding o : outstanding.values()) {
            o.cancel();
        }
        outstanding.clear();
    }

    void shutdown() {
        cancelAll();
        scheduler.shutdownNow();
    }

    private void onT3(String key) {
        Outstanding o = outstanding.get(key);
        if (o == null) {
            return;
        }
        if (o.remaining.get() <= 0) {
            if (outstanding.remove(key, o)) {
                o.cancel();
                if (o.type == Gtpv2MessageType.ECHO_REQUEST) {
                    handler.echoExhausted(o.peer);
                } else {
                    handler.procedureExhausted(o.type, o.sequence, o.peer);
                }
            }
            return;
        }
        o.remaining.decrementAndGet();
        handler.resend(o.wire, o.peer);
        if (outstanding.get(key) == o) {
            o.timer = scheduler.schedule(() -> onT3(key), t3Ms, TimeUnit.MILLISECONDS);
        }
    }

    static String key(InetSocketAddress peer, int sequence) {
        return peer + "/" + sequence;
    }

    private static final class Outstanding {
        final InetSocketAddress peer;
        final byte[] wire;
        final int sequence;
        final Gtpv2MessageType type;
        final AtomicInteger remaining;
        volatile ScheduledFuture<?> timer;

        Outstanding(InetSocketAddress peer, byte[] wire, int sequence, Gtpv2MessageType type, int n3) {
            this.peer = peer;
            this.wire = wire;
            this.sequence = sequence;
            this.type = type;
            this.remaining = new AtomicInteger(n3);
        }

        void cancel() {
            ScheduledFuture<?> t = timer;
            if (t != null) {
                t.cancel(false);
            }
        }
    }
}
