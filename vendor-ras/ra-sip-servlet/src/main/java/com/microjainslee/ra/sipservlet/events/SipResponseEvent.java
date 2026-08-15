package com.microjainslee.ra.sipservlet.events;

import java.util.List;
import java.util.Map;

/** SIP response event — any 1xx-6xx response to a previous request. */
public record SipResponseEvent(
    String callId,
    int statusCode,
    String reasonPhrase,
    String sdpBody,              // SDP from 200 OK (contains remote candidates)
    String contentType,
    List<String> viaHeaders,
    Map<String, String> extraHeaders
) implements SipEvent {

    public SipResponseEvent {
        extraHeaders = extraHeaders == null ? Map.of() : extraHeaders;
    }

    public SipResponseEvent(String callId, int statusCode, String reasonPhrase,
                            String sdpBody, String contentType, List<String> viaHeaders) {
        this(callId, statusCode, reasonPhrase, sdpBody, contentType, viaHeaders, Map.of());
    }

    @Override public String method() { return "RESPONSE"; }
    public boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }
    public boolean isFinal() { return statusCode >= 200; }
    public boolean isProvisional() { return statusCode >= 100 && statusCode < 200; }
}
