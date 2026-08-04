/*
 * micro-jainslee example :: HelloWorld Web
 */
package com.example.helloworld.quarkus.http;

import com.example.helloworld.quarkus.telemetry.EndpointHitStore;
import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import com.microjainslee.telemetry.TelemetryPort;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thin adapter over the shared {@link com.microjainslee.monitor.MonitorHandler}
 * hub, plus app-specific {@code /api/telemetry/endpoints}.
 */
public final class MonitorHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final com.microjainslee.monitor.MonitorHandler delegate;
    private final EndpointHitStore endpointHits;

    public MonitorHandler(TelemetryPort telemetry, EndpointHitStore endpointHits) {
        this.delegate = new com.microjainslee.monitor.MonitorHandler(telemetry);
        this.endpointHits = endpointHits;
    }

    /** Handle a monitor path, or empty if it is not ours. */
    public Optional<HttpReply> handle(HttpWebRequestEvent e) {
        String path = e.getPath();
        if (path.equals("/api/telemetry/endpoints")) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("total", endpointHits.totalHits());
                body.put("endpoints", endpointHits.snapshot());
                return Optional.of(HttpReply.json(JSON.writeValueAsString(body)));
            } catch (Exception ex) {
                return Optional.of(HttpReply.html(500, "telemetry error: " + ex.getMessage()));
            }
        }
        Optional<RaAdminHttpResponse> hit = delegate.handle(
                e.getMethod(), path, e.getQueryParams(), e.getBody());
        return hit.map(MonitorHandler::toReply);
    }

    private static HttpReply toReply(RaAdminHttpResponse r) {
        String ct = r.contentType();
        if (ct != null && (ct.startsWith("image/") || ct.equals("application/octet-stream"))) {
            return new HttpReply(r.status(), ct, null, r.body(), r.headers());
        }
        return new HttpReply(r.status(), ct, r.bodyAsString(), null, r.headers());
    }
}
