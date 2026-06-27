/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/** Fired when the USSD pipeline completes (success or failure). */
@EventType(name = "UssdComplete", vendor = "com.example.ussddemo", version = "1.0")
public final class UssdCompleteEvent implements SleeEvent {

    private final String sessionId;
    private final String responseText;
    private final String errorMessage;

    public UssdCompleteEvent(String sessionId, String responseText, String errorMessage) {
        this.sessionId = sessionId;
        this.responseText = responseText;
        this.errorMessage = errorMessage;
    }

    public String getSessionId() { return sessionId; }
    public String getResponseText() { return responseText; }
    public String getErrorMessage() { return errorMessage; }
}
