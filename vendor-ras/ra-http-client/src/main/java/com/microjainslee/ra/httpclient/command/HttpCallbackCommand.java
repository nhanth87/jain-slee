/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.httpclient.command;

import com.microjainslee.api.OutboundCommand;

/**
 * Sealed outbound command hierarchy for the HTTP client RA
 * ({@code http-callback-ra}).
 *
 * <p>Sent from an SBB via
 * {@link com.microjainslee.api.RaCommandPort#sendCommand(OutboundCommand)}.
 * The RA endpoint pattern-matches the concrete permitted subtype to
 * dispatch to the correct handler. Completion is always
 * {@link com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent}.
 */
public sealed interface HttpCallbackCommand extends com.microjainslee.api.SessionKeyedCommand
        permits HttpCallbackCommand.CallbackRequest,
                HttpCallbackCommand.JsonPostRequest,
                HttpCallbackCommand.PostRequest,
                HttpCallbackCommand.EndSession {

    /** The HTTP exchange this command belongs to (the response comes back under this id). */
    String sessionId();

    /** Commands of one session stay on one RA entity of an entity group. */
    @Override
    default String sessionKey() {
        return sessionId();
    }


    /**
     * Fire-and-forget callback delivery: POST an <em>envelope</em>
     * {@code {"sessionId","status":"OK","payload":"..."}} to
     * {@code callbackUrl}. Use for outbound status callbacks, not AS pull.
     *
     * @param sessionId   the SLEE session / correlation identifier
     * @param callbackUrl the absolute URL to POST to
     * @param payload     application payload nested inside the envelope
     */
    record CallbackRequest(String sessionId, String callbackUrl, String payload)
            implements HttpCallbackCommand, OutboundCommand {
    }

    /**
     * HTTP request/response exchange: POST {@code body} as the raw request
     * entity and complete with the response status/body
     * (classic HttpClientActivity-shaped pull).
     *
     * <p>Name kept as {@code JsonPostRequest} for compatibility; {@code body}
     * may be JSON or XML (or any other text payload). Use
     * {@code contentType} to set the {@code Content-Type} header
     * (e.g. {@code application/json}, {@code text/xml}).</p>
     *
     * @param sessionId   correlation / session id for the completion event
     * @param url         absolute URL to POST to
     * @param body        raw request body (not wrapped); JSON or XML per contentType
     * @param contentType HTTP {@code Content-Type}; null/blank → {@code application/json}
     */
    record JsonPostRequest(String sessionId, String url, String body, String contentType)
            implements HttpCallbackCommand, OutboundCommand {

        /**
         * Compact ctor: defaults {@code Content-Type} to {@code application/json}.
         */
        public JsonPostRequest(String sessionId, String url, String body) {
            this(sessionId, url, body, "application/json");
        }
    }

    /**
     * POST raw {@code body} with extra request headers (e.g. {@code Cookie}, {@code Accept},
     * correlation ids). The RA only carries headers both ways — it keeps no session state; the
     * response headers come back on {@link com.microjainslee.ra.httpclient.events.HttpCallbackCompletedEvent}.
     */
    record PostRequest(String sessionId, String url, String body, String contentType,
                       java.util.Map<String, String> headers)
            implements HttpCallbackCommand, OutboundCommand {
        public PostRequest {
            headers = headers == null ? java.util.Map.of() : java.util.Map.copyOf(headers);
        }
    }

    /**
     * The conversation is over: forget everything kept for it (in-flight record, HA ownership).
     *
     * <p>While a session is open, all its requests leave from the node — and therefore the IP —
     * that sent the first one, so the server keeps its context and session cookie. Sending
     * EndSession when the conversation ends releases that pin; without it the RA would remember
     * every session forever.</p>
     */
    record EndSession(String sessionId) implements HttpCallbackCommand, OutboundCommand {
    }
}
