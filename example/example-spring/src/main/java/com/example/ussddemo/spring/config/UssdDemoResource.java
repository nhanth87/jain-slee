/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.config;

import com.example.ussddemo.spring.rest.UssdBeginRequest;
import com.example.ussddemo.spring.rest.UssdSessionView;
import com.example.ussddemo.spring.service.UssdDemoRuntime;
import com.example.ussddemo.spring.service.UssdSessionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Spring REST entry point. The {@code jainslee-spring-boot-starter}
 * auto-configures a {@code MicroSleeContainer} bean; this controller
 * injects the runtime and dispatches events.
 */
@RestController
@RequestMapping("/api/ussd")
public class UssdDemoResource {

    @Autowired
    private UssdDemoRuntime runtime;

    @Autowired
    private UssdSessionStore sessionStore;

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @PostMapping("/begin")
    public ResponseEntity<UssdSessionView> begin(@RequestBody UssdBeginRequest request) {
        validate(request);
        String sessionId = UUID.randomUUID().toString();
        runtime.beginSession(sessionId, request.msisdn, request.ussdString, null);
        return ResponseEntity.accepted().body(UssdSessionView.processing(sessionId));
    }

    /**
     * HttpClient RA-style begin: returns 202 Accepted with a
     * {@code Location} header pointing at the per-session callback URL.
     * When the SLEE pipeline finishes, the server POSTs the
     * {@link UssdSessionView} body to that URL.
     */
    @PostMapping("/begin-callback")
    public ResponseEntity<UssdSessionView> beginCallback(
            @RequestBody UssdBeginRequest request,
            @RequestParam("callbackUrl") String callbackUrl) {
        if (callbackUrl == null || callbackUrl.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "callbackUrl is required");
        }
        validate(request);
        String sessionId = UUID.randomUUID().toString();
        runtime.beginSession(sessionId, request.msisdn, request.ussdString, callbackUrl);
        return ResponseEntity.accepted()
                .header("Location", callbackUrl + "?sessionId=" + sessionId)
                .body(UssdSessionView.processing(sessionId));
    }

    @GetMapping("/sessions/{sessionId}")
    public UssdSessionView session(@PathVariable String sessionId) {
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Unknown session: " + sessionId);
        }
        UssdSessionView view = new UssdSessionView();
        view.sessionId = sessionId;
        view.status = record.getStatus().name();
        view.responseText = record.getResponseText();
        view.errorMessage = record.getErrorMessage();
        return view;
    }

    private static void validate(UssdBeginRequest request) {
        if (request == null || request.msisdn == null || request.msisdn.trim().isEmpty()) {
            throw new IllegalArgumentException("msisdn is required");
        }
        if (request.ussdString == null || request.ussdString.trim().isEmpty()) {
            throw new IllegalArgumentException("ussdString is required");
        }
    }
}
