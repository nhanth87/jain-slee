/*
 * micro-jainslee example :: CMR
 */
package com.example.cmr.http;

import com.microjainslee.admin.RaAdminHttpResponse;
import com.microjainslee.ai.AIAgentEngine;
import com.microjainslee.ai.AIAnalysis;
import com.microjainslee.ai.AIMode;
import com.microjainslee.ai.ReportAudience;
import com.microjainslee.monitor.MonitorHandler.AiMonitorBridge;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import com.microjainslee.telemetry.TelemetryPort;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thin adapter: {@link HttpWebRequestEvent} → shared
 * {@link com.microjainslee.monitor.MonitorHandler} hub (telemetry + RA admin packs).
 */
public final class MonitorHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final com.microjainslee.monitor.MonitorHandler delegate;

    public MonitorHandler(TelemetryPort telemetry, Supplier<String> healthJson, AIAgentEngine ai) {
        AiMonitorBridge bridge = ai == null ? null : new AiBridge(ai);
        this.delegate = new com.microjainslee.monitor.MonitorHandler(telemetry, healthJson, bridge);
    }

    /** Handle a monitor path, or empty if it is not ours. */
    public Optional<HttpReply> handle(HttpWebRequestEvent e) {
        Optional<RaAdminHttpResponse> hit = delegate.handle(
                e.getMethod(), e.getPath(), e.getQueryParams(), e.getBody());
        return hit.map(MonitorHandler::toReply);
    }

    private static HttpReply toReply(RaAdminHttpResponse r) {
        String ct = r.contentType();
        if (ct != null && (ct.startsWith("image/") || ct.equals("application/octet-stream"))) {
            return new HttpReply(r.status(), ct, null, r.body(), r.headers());
        }
        return new HttpReply(r.status(), ct, r.bodyAsString(), null, r.headers());
    }

    private static final class AiBridge implements AiMonitorBridge {
        private final AIAgentEngine ai;

        AiBridge(AIAgentEngine ai) {
            this.ai = ai;
        }

        @Override
        public String statusJson() throws Exception {
            return JSON.writeValueAsString(ai.status());
        }

        @Override
        public String analysisJson() throws Exception {
            AIAnalysis last = ai.lastAnalysis();
            return last == null ? null : JSON.writeValueAsString(last);
        }

        @Override
        public String analyzeNowJson() throws Exception {
            AIAnalysis a = ai.analyzeNow();
            return a == null ? null : JSON.writeValueAsString(a);
        }

        @Override
        public String report(String audience) {
            return ai.report(ReportAudience.parse(audience));
        }

        @Override
        public void applyConfig(String bodyJson) throws Exception {
            if (bodyJson == null || bodyJson.isBlank()) {
                return;
            }
            JsonNode body = JSON.readTree(bodyJson);
            if (body.has("enabled")) {
                ai.setEnabled(body.get("enabled").asBoolean());
            }
            if (body.has("mode")) {
                ai.setMode(AIMode.parse(body.get("mode").asText()));
            }
        }
    }
}
