/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.clickhouse;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal ClickHouse HTTP interface client (port 8123) — no driver dependency.
 *
 * <p>Values never go into SQL text: queries use ClickHouse query parameters
 * ({@code {name:Type}} + {@code param_name=}), so a hostile MSISDN or correlation id cannot
 * inject SQL. Identifiers (database name) are validated against {@link #IDENT}.</p>
 */
public final class ClickHouseHttp {

    /** Database / table identifiers we are willing to interpolate. */
    public static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    private final HttpClient http;
    private final URI base;
    private final String user;
    private final String password;
    private final Duration requestTimeout;

    public ClickHouseHttp(String url, String user, String password, Duration connectTimeout,
                          Duration requestTimeout) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) {
            throw new IllegalArgumentException("ClickHouse url is required");
        }
        this.base = URI.create(u.endsWith("/") ? u : u + "/");
        this.user = user == null || user.isBlank() ? "default" : user.trim();
        this.password = password == null ? "" : password;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static String requireIdent(String name) {
        if (name == null || !IDENT.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid ClickHouse identifier: " + name);
        }
        return name;
    }

    /** Run a statement with no result (DDL, ALTER). */
    public void execute(String sql) throws IOException {
        execute(sql, Map.of());
    }

    /** Run a statement with query-level settings passed as URL parameters. */
    public void execute(String sql, Map<String, String> settings) throws IOException {
        post(settings, sql.getBytes(StandardCharsets.UTF_8));
    }

    /** Run a query and return the response body lines (use FORMAT JSONEachRow / TSV). */
    public List<String> query(String sql, Map<String, String> params) throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        params.forEach((k, v) -> q.put("param_" + k, v));
        String body = post(q, sql.getBytes(StandardCharsets.UTF_8));
        return body.isEmpty() ? List.of() : body.lines().filter(l -> !l.isBlank()).toList();
    }

    /**
     * INSERT … FORMAT JSONEachRow with {@code ndjson} as the body. {@code dedupToken} makes a
     * retried batch idempotent (table setting {@code non_replicated_deduplication_window}).
     */
    public void insertJsonEachRow(String qualifiedTable, byte[] ndjson, String dedupToken)
            throws IOException {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("query", "INSERT INTO " + qualifiedTable + " FORMAT JSONEachRow");
        q.put("date_time_input_format", "best_effort");
        q.put("input_format_skip_unknown_fields", "1");
        if (dedupToken != null && !dedupToken.isBlank()) {
            q.put("insert_deduplication_token", dedupToken);
        }
        post(q, ndjson);
    }

    /** {@code SELECT 1} — true when the server answers. */
    public boolean ping() {
        try {
            return "1".equals(post(Map.of(), "SELECT 1".getBytes(StandardCharsets.UTF_8)).trim());
        } catch (IOException e) {
            return false;
        }
    }

    private String post(Map<String, String> queryParams, byte[] body) throws IOException {
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : queryParams.entrySet()) {
            qs.append(qs.isEmpty() ? '?' : '&')
                    .append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest req = HttpRequest.newBuilder(base.resolve(qs.toString()))
                .timeout(requestTimeout)
                .header("X-ClickHouse-User", user)
                .header("X-ClickHouse-Key", password)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted talking to ClickHouse", e);
        }
        if (resp.statusCode() / 100 != 2) {
            String msg = resp.body() == null ? "" : resp.body().strip();
            if (msg.length() > 500) {
                msg = msg.substring(0, 500) + "…";
            }
            throw new IOException("ClickHouse HTTP " + resp.statusCode() + ": " + msg);
        }
        return resp.body() == null ? "" : resp.body();
    }
}
