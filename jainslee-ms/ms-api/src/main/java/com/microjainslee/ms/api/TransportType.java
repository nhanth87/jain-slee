/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.api;

/**
 * Preferred inter-service transport. MVP wires {@link #INFINISPAN_QUEUE} only;
 * {@link #GRPC}/{@link #REST}/{@link #BOTH} are stubs for a later phase.
 */
public enum TransportType {
    /** Distributed Infinispan inbox/reply queues (telecom default). */
    INFINISPAN_QUEUE,
    /** Same-JVM only — factory refuses remote placement. */
    LOCAL_ONLY,
    /** Stub — not wired in MVP. */
    GRPC,
    /** Stub — not wired in MVP. */
    REST,
    /** Stub — not wired in MVP. */
    BOTH
}
