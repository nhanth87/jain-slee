/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.grpc;

import com.microjainslee.api.SleeEvent;

/**
 * Builds application {@link SleeEvent} types for gRPC menu request/response legs.
 *
 * <p>Sprint S8 — added a sequence-aware overload
 * {@link #createRequestEvent(String, String, String, long)} that the
 * RA calls when stamping a sequence number on the request event. The
 * legacy 3-arg overload is preserved (and remains the default
 * implementation of the 4-arg overload) so existing tests / stubs
 * continue to compile.</p>
 */
public interface GrpcMenuEventFactory {

    SleeEvent createRequestEvent(String sessionId, String msisdn, String ussdString);

    /**
     * Sprint S8 — sequence-stamping overload. Implementations that
     * produce a {@link com.microjainslee.api.SequencedEvent} should
     * override this to wire the sequence number into the event. The
     * default delegates to the legacy 3-arg method (sequence number
     * effectively dropped to {@code 0}).
     */
    default SleeEvent createRequestEvent(String sessionId, String msisdn,
                                          String ussdString, long sequenceNumber) {
        return createRequestEvent(sessionId, msisdn, ussdString);
    }

    SleeEvent createResponseEvent(String sessionId, String status, String menuText, String error);

    default SleeEvent createErrorResponseEvent(String sessionId, Throwable cause) {
        String message = cause == null ? "unknown"
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return createResponseEvent(sessionId, "ERR", null, message);
    }
}
