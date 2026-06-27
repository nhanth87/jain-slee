/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Same store as embedded, just a CDI @ApplicationScoped bean here. */
@ApplicationScoped
public final class UssdSessionStore {

    public enum Status {
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public static final class SessionRecord {
        private volatile Status status = Status.PROCESSING;
        private volatile String responseText;
        private volatile String errorMessage;
        private volatile String callbackUrl;

        public Status getStatus() { return status; }
        public String getResponseText() { return responseText; }
        public String getErrorMessage() { return errorMessage; }
        public String getCallbackUrl() { return callbackUrl; }
    }

    private final Map<String, SessionRecord> sessions =
            new ConcurrentHashMap<String, SessionRecord>();

    public SessionRecord open(String sessionId) {
        SessionRecord record = new SessionRecord();
        sessions.put(sessionId, record);
        return record;
    }

    public SessionRecord get(String sessionId) { return sessions.get(sessionId); }

    public void attachCallback(String sessionId, String callbackUrl) {
        SessionRecord record = sessions.get(sessionId);
        if (record != null) {
            record.callbackUrl = callbackUrl;
        }
    }

    public void complete(String sessionId, String responseText) {
        SessionRecord record = sessions.get(sessionId);
        if (record != null) {
            record.responseText = responseText;
            record.status = Status.COMPLETED;
        }
    }

    public void fail(String sessionId, String message) {
        SessionRecord record = sessions.get(sessionId);
        if (record != null) {
            record.errorMessage = message;
            record.status = Status.FAILED;
        }
    }
}
