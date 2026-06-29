/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.events;

import com.microjainslee.api.SequencedEvent;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * Fired by {@code GrpcMenuResourceAdaptor} before the upstream gRPC call.
 *
 * <p>Sprint S8 — now implements {@link SequencedEvent} so the
 * gRPC menu RA gets ordered delivery and dedup. The sequence
 * number is stamped by the RA from a per-session atomic counter
 * (see {@code GrpcMenuResourceAdaptor.sequenceCounters}). The
 * convergence name is the session id.</p>
 */
@EventType(name = "GrpcMenuRequest", vendor = "com.example.ussddemo.quarkus", version = "1.0")
public final class GrpcMenuRequestEvent implements SleeEvent, SequencedEvent {

    private final String sessionId;
    private final String msisdn;
    private final String ussdString;
    private final long sequenceNumber;

    /** Legacy 3-arg constructor — sequence defaults to 0 (no dedup). */
    public GrpcMenuRequestEvent(String sessionId, String msisdn, String ussdString) {
        this(sessionId, msisdn, ussdString, 0L);
    }

    /**
     * Sprint S8 — sequence-aware constructor.
     *
     * @param sequenceNumber per-session monotonically increasing
     *        sequence number stamped by the gRPC menu RA.
     */
    public GrpcMenuRequestEvent(String sessionId, String msisdn,
                                String ussdString, long sequenceNumber) {
        this.sessionId = sessionId;
        this.msisdn = msisdn;
        this.ussdString = ussdString;
        this.sequenceNumber = sequenceNumber;
    }

    public String getSessionId() { return sessionId; }
    public String getMsisdn() { return msisdn; }
    public String getUssdString() { return ussdString; }

    @Override
    public long getSequenceNumber() { return sequenceNumber; }

    @Override
    public String getConvergenceName() { return sessionId; }
}
