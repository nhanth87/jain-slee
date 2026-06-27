/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.ra;

import java.util.Objects;

/** RA-side activity token for an in-flight gRPC menu lookup. */
public final class GrpcMenuActivity {

    private final String sessionId;
    private final String msisdn;
    private final String ussdString;
    private volatile boolean completed;

    public GrpcMenuActivity(String sessionId, String msisdn, String ussdString) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.msisdn = Objects.requireNonNull(msisdn, "msisdn");
        this.ussdString = Objects.requireNonNull(ussdString, "ussdString");
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getMsisdn() {
        return msisdn;
    }

    public String getUssdString() {
        return ussdString;
    }

    public void complete() {
        completed = true;
    }

    public void completeExceptionally(Throwable t) {
        completed = true;
    }

    public boolean isCompleted() {
        return completed;
    }
}
