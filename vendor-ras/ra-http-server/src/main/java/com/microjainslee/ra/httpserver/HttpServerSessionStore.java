/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.httpserver;

/**
 * Stores session-level tracking state for HTTP-RA-backed sessions.
 *
 * <p>Applications implement this interface (typically as a CDI bean or
 * Spring component) to provide session-tracking behaviour to the
 * HTTP Resource Adaptor and its associated SBBs.</p>
 */
public interface HttpServerSessionStore {

    /**
     * Retrieves the snapshot for a given session, or {@code null} if the
     * session has never been opened or has already been released.
     *
     * @param sessionId the session identifier
     * @return the snapshot, or {@code null}
     */
    SessionSnapshot get(String sessionId);

    /**
     * Immutable point-in-time view of a USSD/HTTP session.
     */
    interface SessionSnapshot {

        /** Current processing status ("PROCESSING", "COMPLETED", "FAILED"). */
        String getStatus();

        /** Completed response text (may be {@code null} while processing). */
        String getResponseText();

        /** Error message (may be {@code null} unless status is "FAILED"). */
        String getErrorMessage();
    }
}
