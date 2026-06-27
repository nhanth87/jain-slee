/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.embedded;

import com.microjainslee.core.MicroSleeContainer;

/**
 * Plain-Java bridge for session completion callbacks. HTTP ingress is handled
 * entirely by {@link com.example.ussddemo.ra.HttpIngressResourceAdaptor}.
 */
public final class UssdDemoRuntime {

    private final UssdSessionStore sessionStore;
    private final UssdCallbackDispatcher callbackDispatcher;

    public UssdDemoRuntime(UssdSessionStore sessionStore,
                           UssdCallbackDispatcher callbackDispatcher) {
        this.sessionStore = sessionStore;
        this.callbackDispatcher = callbackDispatcher;
    }

    public UssdSessionStore sessionStore() {
        return sessionStore;
    }

    public void completeSession(String sessionId, String responseText) {
        sessionStore.complete(sessionId, responseText);
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record != null && record.getCallbackUrl() != null) {
            callbackDispatcher.dispatch(record.getCallbackUrl(), sessionId, "COMPLETED",
                    responseText, null);
        }
    }

    public void failSession(String sessionId, String message) {
        sessionStore.fail(sessionId, message);
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record != null && record.getCallbackUrl() != null) {
            callbackDispatcher.dispatch(record.getCallbackUrl(), sessionId, "FAILED",
                    null, message);
        }
    }
}
