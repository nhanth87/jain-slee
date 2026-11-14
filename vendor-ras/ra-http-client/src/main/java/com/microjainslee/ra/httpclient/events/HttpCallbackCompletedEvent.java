/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.httpclient.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * Fired by the HTTP callback RA when an asynchronous callback POST
 * completes (success or failure).
 *
 * <p>SBBs subscribe to this event to react to callback outcomes —
 * e.g. logging, retry at the application layer, or tearing down
 * session resources.
 */
@EventType(name = "HttpCallbackCompleted", vendor = "com.microjainslee", version = "1.0")
public final class HttpCallbackCompletedEvent implements SleeEvent {

    private final String sessionId;
    private final int statusCode;
    private final String responseBody;
    private final String errorMessage;
    /** Response headers, names lower-cased; empty on transport failure. */
    private final java.util.Map<String, java.util.List<String>> responseHeaders;

    public HttpCallbackCompletedEvent(String sessionId, int statusCode,
                                      String responseBody, String errorMessage) {
        this(sessionId, statusCode, responseBody, errorMessage, java.util.Map.of());
    }

    public HttpCallbackCompletedEvent(String sessionId, int statusCode, String responseBody,
                                      String errorMessage,
                                      java.util.Map<String, java.util.List<String>> responseHeaders) {
        this.sessionId = sessionId;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errorMessage = errorMessage;
        this.responseHeaders = responseHeaders == null ? java.util.Map.of() : responseHeaders;
    }

    /** All response headers (lower-case names → values in arrival order). */
    public java.util.Map<String, java.util.List<String>> getResponseHeaders() { return responseHeaders; }

    /** Values of one response header, case-insensitive; empty when absent. */
    public java.util.List<String> getHeaderValues(String name) {
        if (name == null) {
            return java.util.List.of();
        }
        java.util.List<String> v = responseHeaders.get(name.toLowerCase(java.util.Locale.ROOT));
        return v == null ? java.util.List.of() : v;
    }

    public String getSessionId() { return sessionId; }
    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return "HttpCallbackCompletedEvent{sessionId='" + sessionId
                + "', statusCode=" + statusCode
                + ", errorMessage='" + errorMessage + "'}";
    }
}
