/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.embedded;

import com.example.ussddemo.events.UssdBeginEvent;
import com.example.ussddemo.sbbs.GrpcBackendSbb;
import com.example.ussddemo.sbbs.Ss7UssdIngressSbb;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.InMemoryActivityContext;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Plain-Java bridge between the HTTP front-end and the embedded
 * {@link MicroSleeContainer}. No CDI; wired up by constructor injection
 * from {@link EmbeddedUssdMain}.
 */
public final class UssdDemoRuntime {

    private static final Logger LOG = LogManager.getLogger(UssdDemoRuntime.class);

    public static final String SS7_SBB_ID = "Ss7UssdIngress";
    public static final String GRPC_SBB_ID = "GrpcBackend";

    private final MicroSleeContainer container;
    private final UssdSessionStore sessionStore;
    private final UssdCallbackDispatcher callbackDispatcher;

    public UssdDemoRuntime(MicroSleeContainer container,
                           UssdSessionStore sessionStore,
                           UssdCallbackDispatcher callbackDispatcher) {
        this.container = container;
        this.sessionStore = sessionStore;
        this.callbackDispatcher = callbackDispatcher;
    }

    public MicroSleeContainer container() {
        return container;
    }

    public UssdSessionStore sessionStore() {
        return sessionStore;
    }

    /** Called by the HTTP handler; parses the JSON body and fires the begin event. */
    public void beginSession(String sessionId, String requestJson, String callbackUrl) {
        sessionStore.open(sessionId);
        sessionStore.attachCallback(sessionId, callbackUrl);

        // Minimal hand-rolled field extraction -- the request is just two strings.
        String msisdn = extractJsonString(requestJson, "msisdn");
        String ussdString = extractJsonString(requestJson, "ussdString");
        if (msisdn == null || msisdn.trim().isEmpty()) {
            throw new IllegalArgumentException("msisdn is required");
        }
        if (ussdString == null || ussdString.trim().isEmpty()) {
            throw new IllegalArgumentException("ussdString is required");
        }

        InMemoryActivityContext aci = container.createActivityContext(sessionId);
        SimpleSbbLocalObject ss7 = container.registerSbb(
                SS7_SBB_ID, new Ss7UssdIngressSbb());
        SimpleSbbLocalObject grpc = container.registerSbb(
                GRPC_SBB_ID, new GrpcBackendSbb());
        ss7.setPriority(8);
        grpc.setPriority(5);
        container.attach(sessionId, ss7);
        container.attach(sessionId, grpc);

        LOG.info("Firing UssdBeginEvent session={} msisdn={} callback={}",
                sessionId, msisdn,
                callbackUrl == null ? "<polling>" : callbackUrl);
        container.routeEvent(
                new UssdBeginEvent(sessionId, msisdn.trim(), ussdString.trim()),
                aci);
    }

    public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
        container.routeEvent(event, aci);
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

    /** Same logic as the test's extractJsonString -- avoids a JSON dep. */
    private static String extractJsonString(String json, String field) {
        if (json == null) {
            return null;
        }
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return null;
        }
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return null;
        }
        return json.substring(valueStart, valueEnd);
    }
}
