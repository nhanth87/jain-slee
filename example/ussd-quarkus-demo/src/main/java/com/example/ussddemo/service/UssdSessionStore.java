/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.service;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory session state exposed to the REST API and SBBs.
 */
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

        public Status getStatus() {
            return status;
        }

        public String getResponseText() {
            return responseText;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<String, SessionRecord>();

    public SessionRecord open(String sessionId) {
        SessionRecord record = new SessionRecord();
        sessions.put(sessionId, record);
        return record;
    }

    public SessionRecord get(String sessionId) {
        return sessions.get(sessionId);
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
