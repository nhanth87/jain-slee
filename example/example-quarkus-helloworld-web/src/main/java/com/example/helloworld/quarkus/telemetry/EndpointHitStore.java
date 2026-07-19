/*
 * micro-jainslee example :: HelloWorld Web
 */
package com.example.helloworld.quarkus.telemetry;

import com.microjainslee.telemetry.TelemetryPort;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-endpoint request counters for the HelloWorld app.
 *
 * <p>Hot path: {@link #record(String, String)} is lock-free
 * ({@link ConcurrentHashMap} + {@link AtomicLong}). Optionally mirrors each
 * key into a Micrometer {@link TelemetryPort.Counter} so Prometheus scrape
 * ({@code /api/telemetry/metrics}) and the JSON surface
 * ({@code /api/telemetry/endpoints}) stay in sync.</p>
 */
public final class EndpointHitStore {

    private final ConcurrentHashMap<String, AtomicLong> hits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TelemetryPort.Counter> micrometer =
            new ConcurrentHashMap<>();
    private volatile TelemetryPort telemetry; // nullable

    /** Attach Micrometer after {@link AppTelemetry#install} (may be null). */
    public void bindTelemetry(TelemetryPort telemetry) {
        this.telemetry = telemetry;
    }

    /**
     * Count one hit for {@code METHOD path} (e.g. {@code GET /}).
     * Telemetry UI paths are counted too so ops can see dashboard traffic.
     */
    public void record(String method, String path) {
        String m = method == null || method.isBlank() ? "?" : method.toUpperCase();
        String p = path == null || path.isBlank() ? "/" : path;
        String key = m + " " + p;
        hits.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();

        TelemetryPort port = this.telemetry;
        if (port != null) {
            micrometer.computeIfAbsent(key, k ->
                    port.customCounter("http_endpoint_hits_total",
                            "method", m, "path", sanitizeTag(p))
            ).increment();
        }
    }

    /** Snapshot {@code "GET /" → count} sorted by key for stable JSON. */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        hits.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(e.getKey(), e.getValue().get()));
        return out;
    }

    public long totalHits() {
        return hits.values().stream().mapToLong(AtomicLong::get).sum();
    }

    /** Micrometer tag values must stay relatively short / label-safe. */
    private static String sanitizeTag(String path) {
        if (path.length() <= 64) {
            return path;
        }
        return path.substring(0, 61) + "...";
    }
}
