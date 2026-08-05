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
     * HTTP request/response exchange: POST {@code body} as the raw JSON
     * request entity and complete with the response status/body
     * (classic HttpClientActivity-shaped pull).
     *
     * @param sessionId  correlation / session id for the completion event
     * @param url        absolute URL to POST to
     * @param body       raw JSON request body (not wrapped)
     */
    record JsonPostRequest(String sessionId, String url, String body)
            implements HttpCallbackCommand, OutboundCommand {
    }
}
