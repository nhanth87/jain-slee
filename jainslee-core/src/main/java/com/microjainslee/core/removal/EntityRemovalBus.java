/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.removal;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lightweight synchronous publish-subscribe bus for entity removal events.
 *
 * <p>Sprint S6 (Observability &amp; Removal Notification) — replaces the legacy
 * direct {@code RemovalListener} callback with a publish-subscribe bus so
 * independent observers (IES cleanup, metrics, store auto-fail, lifecycle
 * logger) can attach without coupling to {@code MicroSleeContainer}.
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li>Zero external dependencies (no Guava EventBus, no Reactor).</li>
 *   <li>Subscribers run on the SAME virtual thread that triggered removal.
 *       They MUST be fast (&lt; 1 &micro;s) and MUST NOT block or call back into
 *       {@code MicroSleeContainer} (deadlock risk).</li>
 *   <li>{@link CopyOnWriteArrayList}: reads (publish path) are lock-free;
 *       writes (subscribe/unsubscribe) are rare and copy-on-write.</li>
 *   <li>Defensive fan-out: a subscriber that throws does NOT abort the
 *       remaining subscribers. The bus guarantees every subscriber gets the
 *       event unless it itself blows up.</li>
 * </ul>
 *
 * @author Tran Nhan (nhanth87)
 */
public final class EntityRemovalBus {

    private final List<Consumer<EntityRemovalEvent>> subscribers =
            new CopyOnWriteArrayList<Consumer<EntityRemovalEvent>>();

    /** Total events ever published — monotonic, never reset. */
    private final java.util.concurrent.atomic.AtomicLong publishCount =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Register a subscriber. Idempotent at the API level: if the same
     * {@link Consumer} reference is passed twice it will fire twice.
     * Subscribers that want exactly-once semantics should unsubscribe
     * themselves before re-subscribing.
     */
    public void subscribe(Consumer<EntityRemovalEvent> subscriber) {
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber is required");
        }
        subscribers.add(subscriber);
    }

    /**
     * Unregister a previously-added subscriber. Best-effort: a no-op when
     * the reference was never registered.
     */
    public void unsubscribe(Consumer<EntityRemovalEvent> subscriber) {
        if (subscriber == null) {
            return;
        }
        subscribers.remove(subscriber);
    }

    /**
     * Total number of events ever published on this bus. Useful for tests
     * and for the {@code MicroSleeContainer.getEntityRemovalCount()} metric.
     */
    public long getPublishCount() {
        return publishCount.get();
    }

    /**
     * Current subscriber count — diagnostics only.
     */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Publish {@code event} to every subscriber synchronously.
     *
     * <p>Individual subscriber failures are caught and logged to {@code
     * System.err} (no Logger dependency here to avoid a circular class-init
     * edge — log4j is configured by the kernel, not by core/removal/).
     * The bus MUST NOT abort the remaining subscribers.
     */
    public void publish(EntityRemovalEvent event) {
        if (event == null) {
            return;
        }
        publishCount.incrementAndGet();
        for (Consumer<EntityRemovalEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (Exception ex) {
                // Must not use Logger here to avoid circular dependency;
                // use System.err as last resort — real apps route via JUL.
                System.err.printf(
                    "[EntityRemovalBus] subscriber %s threw on event %s: %s%n",
                    s, event.entityId(), ex.getMessage());
            }
        }
    }
}