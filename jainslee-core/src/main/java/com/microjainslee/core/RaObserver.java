/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.core;

/**
 * Passive observation hook for Resource Adaptor activity — the seam that lets
 * an observability layer (jainslee-telemetry) watch RA events and commands
 * without the core owing it a dependency.
 *
 * <p>The container invokes the observer:
 * <ul>
 *   <li>{@link #onEventFired} — after a {@link com.microjainslee.api.RaBootstrapPort#fireEvent}
 *       call is accepted for routing (per-event, on the RA's caller thread).</li>
 *   <li>{@link #onCommandSent} — after
 *       {@link com.microjainslee.api.RaCommandPort#sendCommand} is called by an SBB.</li>
 *   <li>{@link #onFailure} — when a command send throws.</li>
 * </ul>
 *
 * <p>Contract for implementations (same as {@link DispatchObserver}):
 * <ul>
 *   <li><b>Fast</b> — a few counter increments; never block, never do I/O.</li>
 *   <li><b>Never throw</b> — the container additionally shields itself, but a
 *       throwing observer is a bug.</li>
 *   <li><b>Thread-safe</b> — events and commands happen concurrently across RAs.</li>
 * </ul>
 *
 * <p>When no observer is registered the cost is one volatile read per
 * call — nothing else.</p>
 *
 * @see DispatchObserver
 */
public interface RaObserver {

    /**
     * An event was accepted by the container endpoint for routing.
     *
     * @param raName the RA entity name that fired the event
     */
    void onEventFired(String raName);

    /**
     * An SBB sent an outbound command to the RA via {@code RaCommandPort}.
     *
     * @param raName the RA entity name the command was sent to
     */
    void onCommandSent(String raName);

    /**
     * A command send threw an exception (the command was not delivered).
     *
     * @param raName the RA entity name the command was targeted at
     */
    void onFailure(String raName);
}
