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

import com.example.ussddemo.events.UssdBeginEvent;
import com.example.ussddemo.rest.UssdBeginRequest;
import com.example.ussddemo.rest.UssdSessionView;
import com.microjainslee.core.InMemoryActivityContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Orchestrates USSD sessions from the HTTP entry point into the SLEE event router.
 */
@ApplicationScoped
public final class UssdSessionService {

    private static final Logger LOG = Logger.getLogger(UssdSessionService.class);

    @Inject
    UssdDemoRuntime runtime;

    @Inject
    UssdSessionStore sessionStore;

    public UssdSessionView begin(UssdBeginRequest request) {
        return begin(request, null);
    }

    /**
     * HttpClient RA-style begin: returns 202 immediately, then POSTs the
     * response back to {@code callbackUrl} when the SLEE pipeline finishes.
     * If {@code callbackUrl} is {@code null}, the caller falls back to
     * polling {@code /sessions/{id}} — both paths produce the same result.
     */
    public UssdSessionView begin(UssdBeginRequest request, String callbackUrl) {
        if (request == null || request.msisdn == null || request.msisdn.trim().isEmpty()) {
            throw new IllegalArgumentException("msisdn is required");
        }
        if (request.ussdString == null || request.ussdString.trim().isEmpty()) {
            throw new IllegalArgumentException("ussdString is required");
        }

        String sessionId = UUID.randomUUID().toString();
        sessionStore.open(sessionId);
        // Stash the callback URL alongside the session record so the runtime
        // can fire it on completeSession().
        sessionStore.attachCallback(sessionId, callbackUrl);

        InMemoryActivityContext aci = runtime.createSessionActivityContext(sessionId);
        runtime.attachDemoSbbs(sessionId);

        LOG.infof("Firing UssdBeginEvent session=%s msisdn=%s callback=%s",
                sessionId, request.msisdn,
                callbackUrl == null ? "<polling>" : callbackUrl);
        runtime.routeEvent(
                new UssdBeginEvent(sessionId, request.msisdn.trim(), request.ussdString.trim()),
                aci);

        return UssdSessionView.processing(sessionId);
    }

    public UssdSessionView getSession(String sessionId) {
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record == null) {
            return null;
        }
        UssdSessionView view = new UssdSessionView();
        view.sessionId = sessionId;
        view.status = record.getStatus().name();
        view.responseText = record.getResponseText();
        view.errorMessage = record.getErrorMessage();
        return view;
    }
}
