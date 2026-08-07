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
public sealed interface HttpCallbackCommand extends OutboundCommand
        permits HttpCallbackCommand.CallbackRequest,
                HttpCallbackCommand.JsonPostRequest {

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
}
