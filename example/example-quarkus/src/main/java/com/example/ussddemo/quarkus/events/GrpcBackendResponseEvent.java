/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * Response from the mock gRPC backend routed back to the SS7 ingress SBB.
 */
@EventType(name = "GrpcBackendResponse", vendor = "com.example.ussddemo", version = "1.0")
public final class GrpcBackendResponseEvent implements SleeEvent {

    private final String sessionId;
    private final String menuText;

    public GrpcBackendResponseEvent(String sessionId, String menuText) {
        this.sessionId = sessionId;
        this.menuText = menuText;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getMenuText() {
        return menuText;
    }
}
