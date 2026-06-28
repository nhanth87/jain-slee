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
 */
public interface GrpcMenuEventFactory {

    SleeEvent createRequestEvent(String sessionId, String msisdn, String ussdString);

    SleeEvent createResponseEvent(String sessionId, String status, String menuText, String error);

    default SleeEvent createErrorResponseEvent(String sessionId, Throwable cause) {
        String message = cause == null ? "unknown"
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return createResponseEvent(sessionId, "ERR", null, message);
    }
}
