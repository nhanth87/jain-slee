/*
 * micro-jainslee 1.1.0 — example application (ussdgw-simulator)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussdgw;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone CLI that pretends to be the USSD gateway SS7 stack: it POSTs a USSD begin
 * to the Quarkus demo and polls until the menu text is ready.
 */
public final class Ss7UssdSimulatorMain {

    private static final Pattern SESSION_ID = Pattern.compile("\"sessionId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RESPONSE = Pattern.compile("\"responseText\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"",
            Pattern.DOTALL);

    private Ss7UssdSimulatorMain() {
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://127.0.0.1:8080";
        String msisdn = args.length > 1 ? args[1] : "251911000001";
        String ussd = args.length > 2 ? args[2] : "*123#";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        String body = "{\"msisdn\":\"" + escapeJson(msisdn) + "\",\"ussdString\":\"" + escapeJson(ussd) + "\"}";
        HttpRequest begin = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/ussd/begin"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        System.out.println("[SS7-sim] Firing MAP USSD begin msisdn=" + msisdn + " ussd=" + ussd);
        HttpResponse<String> beginResponse = client.send(begin, HttpResponse.BodyHandlers.ofString());
        if (beginResponse.statusCode() != 202) {
            System.err.println("[SS7-sim] begin failed HTTP " + beginResponse.statusCode() + ": "
                    + beginResponse.body());
            System.exit(1);
        }

        String sessionId = extract(SESSION_ID, beginResponse.body(), 1);
        System.out.println("[SS7-sim] sessionId=" + sessionId);

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            HttpRequest poll = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ussd/sessions/" + sessionId))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> pollResponse = client.send(poll, HttpResponse.BodyHandlers.ofString());
            if (pollResponse.statusCode() != 200) {
                System.err.println("[SS7-sim] poll failed HTTP " + pollResponse.statusCode());
                System.exit(2);
            }
            String status = extract(STATUS, pollResponse.body(), 1);
            if ("COMPLETED".equals(status)) {
                String menu = unescapeJson(extract(RESPONSE, pollResponse.body(), 1));
                System.out.println("[SS7-sim] MAP USSD response:");
                System.out.println(menu);
                return;
            }
            if ("FAILED".equals(status)) {
                System.err.println("[SS7-sim] session failed: " + pollResponse.body());
                System.exit(3);
            }
            Thread.sleep(100L);
        }
        System.err.println("[SS7-sim] timed out waiting for USSD response");
        System.exit(4);
    }

    private static String extract(Pattern pattern, String json, int group) {
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Pattern not found in: " + json);
        }
        return matcher.group(group);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescapeJson(String value) {
        return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
