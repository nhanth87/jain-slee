/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.ra;

import java.util.Objects;

/** RA-side activity token bound to an HTTP USSD session id. */
public final class HttpSessionActivity {

    private final String sessionId;

    public HttpSessionActivity(String sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
    }

    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof HttpSessionActivity)) {
            return false;
        }
        return sessionId.equals(((HttpSessionActivity) obj).sessionId);
    }

    @Override
    public int hashCode() {
        return sessionId.hashCode();
    }
}
