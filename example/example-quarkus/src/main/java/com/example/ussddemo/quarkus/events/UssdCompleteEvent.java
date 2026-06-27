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

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/** SS7 leg completed — HttpServerSbb delivers callback / polling result. */
@EventType(name = "UssdComplete", vendor = "com.example.ussddemo.quarkus", version = "1.0")
public final class UssdCompleteEvent implements SleeEvent {

    private final String sessionId;
    private final String responseText;

    public UssdCompleteEvent(String sessionId, String responseText) {
        this.sessionId = sessionId;
        this.responseText = responseText;
    }

    public String getSessionId() { return sessionId; }
    public String getResponseText() { return responseText; }
}
