package com.microjainslee.ra.sipservlet.command;

import java.util.Map;

/** Send a SIP response (1xx-6xx) back to the originator. Optional extra headers (e.g. WWW-Authenticate). */
public record SendResponse(String callId, int statusCode, String reason, Map<String, String> headers)
        implements SipOutboundCommand {

    public SendResponse(String callId, int statusCode, String reason) {
        this(callId, statusCode, reason, Map.of());
    }

    public static SendResponse of(String callId, int statusCode, String reason) {
        return new SendResponse(callId, statusCode, reason, Map.of());
    }

    public static SendResponse withHeaders(String callId, int statusCode, String reason,
                                           Map<String, String> headers) {
        return new SendResponse(callId, statusCode, reason,
                headers == null ? Map.of() : Map.copyOf(headers));
    }
}
