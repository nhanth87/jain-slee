/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;

/**
 * Point-to-point delivery of a portable payload to <b>one</b> named node
 * (ADR 0007 D11).
 *
 * <p>
 * Uses Infinispan's {@code ClusterExecutor} filtered to the target address, so
 * exactly one JGroups unicast carries the payload. Nothing is written to a
 * cache: an envelope for a dead node cannot leak, and other members are never
 * notified. That is the difference with the cache + clustered-listener pattern
 * used by the older sticky buses.
 *
 * <p>
 * Handlers are keyed by {@code (nodeId, topic)} in a JVM-wide registry so the
 * shipped task can find the receiver on the target without carrying a
 * reference; keying by node id keeps two in-process nodes (tests) apart.
 * Handlers run on the Infinispan remote-command thread and must not block —
 * hand off to your own executor or mailbox.
 *
 * <p>
 * Payloads travel with the manager's Java-serialization marshaller, so they
 * must be inside {@link MarshallingAllowList}.
 */
public final class ClusterUnicast {

    private static final Logger LOG = LogManager.getLogger(ClusterUnicast.class);

    /** Default time a send may take before the returned future fails. */
    public static final long DEFAULT_TIMEOUT_MS = 5_000L;

    private static final ConcurrentMap<String, Consumer<Serializable>> HANDLERS = new ConcurrentHashMap<>();

    private final ClusterManager clusterManager;
    private final String localNodeId;
    private final long timeoutMs;

    public ClusterUnicast(ClusterManager clusterManager) {
        this(clusterManager, DEFAULT_TIMEOUT_MS);
    }

    public ClusterUnicast(ClusterManager clusterManager, long timeoutMs) {
        this.clusterManager = Objects.requireNonNull(clusterManager, "clusterManager");
        this.localNodeId = clusterManager.getNodeId();
        this.timeoutMs = timeoutMs;
    }

    public String localNodeId() {
        return localNodeId;
    }

    /** Register this node's receiver for {@code topic}. Replaces a previous one. */
    public void register(String topic, Consumer<Serializable> handler) {
        HANDLERS.put(key(localNodeId, topic), Objects.requireNonNull(handler, "handler"));
    }

    public void unregister(String topic) {
        HANDLERS.remove(key(localNodeId, topic));
    }

    /**
     * Deliver {@code payload} to {@code targetNodeId}'s handler for {@code topic}.
     *
     * @return completes {@code true} when the target ran its handler,
     *         {@code false} when the target is not in the view or has no handler;
     *         completes exceptionally on transport failure or timeout
     */
    public CompletableFuture<Boolean> send(String targetNodeId, String topic, Serializable payload) {
        Objects.requireNonNull(topic, "topic");
        Address target = addressOf(targetNodeId);
        if (target == null) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        AtomicBoolean handled = new AtomicBoolean();
        clusterManager.getCacheManager().executor()
                .filterTargets(List.of(target))
                .timeout(timeoutMs, TimeUnit.MILLISECONDS)
                .submitConsumer(new Delivery(targetNodeId, topic, payload),
                        (address, delivered, error) -> {
                            if (error == null && Boolean.TRUE.equals(delivered)) {
                                handled.set(true);
                            }
                        })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(handled.get());
                    }
                });
        return result;
    }

    /**
     * Exact match on the JGroups logical name. A prefix or substring match would
     * treat {@code ss7-1} as present when only {@code ss7-10} is.
     */
    private Address addressOf(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        List<Address> members = clusterManager.getCacheManager().getMembers();
        if (members == null) {
            return null;
        }
        for (Address member : members) {
            if (nodeId.equals(String.valueOf(member))) {
                return member;
            }
        }
        return null;
    }

    private static String key(String nodeId, String topic) {
        return nodeId + '\u0000' + topic;
    }

    /** The shipped task. Runs on the target, looks the handler up by that node's name. */
    static final class Delivery implements Function<EmbeddedCacheManager, Boolean>, Serializable {

        private static final long serialVersionUID = 1L;

        private final String targetNodeId;
        private final String topic;
        private final Serializable payload;

        Delivery(String targetNodeId, String topic, Serializable payload) {
            this.targetNodeId = targetNodeId;
            this.topic = topic;
            this.payload = payload;
        }

        @Override
        public Boolean apply(EmbeddedCacheManager manager) {
            String self = manager.getCacheManagerConfiguration().transport().nodeName();
            if (!targetNodeId.equals(self)) {
                return false;                       // mis-addressed; never deliver elsewhere
            }
            Consumer<Serializable> handler = HANDLERS.get(key(self, topic));
            if (handler == null) {
                LOG.warn("No unicast handler for topic={} on node={} — payload dropped", topic, self);
                return false;
            }
            handler.accept(payload);
            return true;
        }
    }
}
