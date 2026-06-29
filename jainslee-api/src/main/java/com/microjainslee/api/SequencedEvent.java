/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

/**
 * Sprint S8 — optional marker interface for events that carry an
 * application-level sequence number.
 *
 * <p>When an event implements {@code SequencedEvent}, the
 * {@link com.microjainslee.core.ordering.OutOfOrderBuffer} uses the
 * sequence number to delay delivery until the SBB entity has been
 * allocated, and the {@link com.microjainslee.core.ordering.DedupWindow}
 * uses the (convergence, sequence) pair to drop duplicate deliveries
 * caused by at-least-once transport semantics (gRPC, SS7 over SCTP,
 * HTTP retry, etc.).</p>
 *
 * <h2>Backward compatibility</h2>
 * <p>Events that do <em>not</em> implement this interface are treated as
 * unordered and are routed immediately with no buffering or dedup — the
 * legacy {@code SleeEndpoint.fireEvent()} hot path is preserved
 * byte-for-byte. Adopting the interface is opt-in per event class.</p>
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #getSequenceNumber()} must be monotonically increasing
 *       within a single convergence (session). Reuse of a sequence
 *       number is the trigger for dedup; gaps are allowed but cause
 *       out-of-order buffering.</li>
 *   <li>{@link #getConvergenceName()} must match the IES convergence
 *       key returned by the initial event for that session (typically
 *       the session / dialog id).</li>
 * </ul>
 *
 * @author Tran Nhan (nhanth87)
 */
public interface SequencedEvent {

    /**
     * Per-convergence monotonically increasing sequence number.
     * @return non-negative sequence; the exact starting value is
     *         producer-defined but every event within a convergence
     *         must carry a value &gt; the previous one's.
     */
    long getSequenceNumber();

    /**
     * Convergence key — must equal the IES result's convergence key for
     * this session. Typically the session / dialog id (e.g. USSD
     * session id, SIP call-id).
     * @return non-null, non-empty convergence identifier.
     */
    String getConvergenceName();
}
