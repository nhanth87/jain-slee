/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.openapi.headers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Codec for 3GPP TS 29.500 custom {@code 3gpp-Sbi-*} headers (subset + passthrough map).
 */
public final class SbiHeaderCodec {

    public static final String MESSAGE_PRIORITY = "3gpp-Sbi-Message-Priority";
    public static final String CALLBACK = "3gpp-Sbi-Callback";
    public static final String TARGET_API_ROOT = "3gpp-Sbi-Target-apiRoot";
    public static final String ROUTING_BINDING = "3gpp-Sbi-Routing-Binding";
    public static final String BINDING = "3gpp-Sbi-Binding";
    public static final String DISCOVERY = "3gpp-Sbi-Discovery";
    public static final String PRODUCER_ID = "3gpp-Sbi-Producer-Id";
    public static final String CORRELATION_INFO = "3gpp-Sbi-Correlation-Info";
    public static final String MAX_RSP_TIME = "3gpp-Sbi-Max-Rsp-Time";
    public static final String SENDER_TIMESTAMP = "3gpp-Sbi-Sender-Timestamp";
    public static final String RETRY_INFO = "3gpp-Sbi-Retry-Info";
    public static final String SELECTION_INFO = "3gpp-Sbi-Selection-Info";
    public static final String NF_PEER_INFO = "3gpp-Sbi-NF-Peer-Info";

    private final Map<String, String> headers;

    public SbiHeaderCodec(Map<String, String> headers) {
        Map<String, String> m = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() != null) {
                    m.put(canonicalize(e.getKey()), e.getValue());
                }
            }
        }
        this.headers = Collections.unmodifiableMap(m);
    }

    public static String canonicalize(String name) {
        // HTTP/2 lowercases; preserve 3gpp-Sbi-* display form for known headers
        String lower = name.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "3gpp-sbi-message-priority" -> MESSAGE_PRIORITY;
            case "3gpp-sbi-callback" -> CALLBACK;
            case "3gpp-sbi-target-apiroot" -> TARGET_API_ROOT;
            case "3gpp-sbi-routing-binding" -> ROUTING_BINDING;
            case "3gpp-sbi-binding" -> BINDING;
            case "3gpp-sbi-discovery" -> DISCOVERY;
            case "3gpp-sbi-producer-id" -> PRODUCER_ID;
            case "3gpp-sbi-correlation-info" -> CORRELATION_INFO;
            case "3gpp-sbi-max-rsp-time" -> MAX_RSP_TIME;
            case "3gpp-sbi-sender-timestamp" -> SENDER_TIMESTAMP;
            case "3gpp-sbi-retry-info" -> RETRY_INFO;
            case "3gpp-sbi-selection-info" -> SELECTION_INFO;
            case "3gpp-sbi-nf-peer-info" -> NF_PEER_INFO;
            default -> name;
        };
    }

    public Map<String, String> all() {
        return headers;
    }

    public Optional<String> get(String name) {
        String v = headers.get(canonicalize(name));
        if (v == null) {
            // case-insensitive scan
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return Optional.ofNullable(e.getValue());
                }
            }
            return Optional.empty();
        }
        return Optional.of(v);
    }

    public boolean noRetries() {
        return get(RETRY_INFO).map(v -> v.toLowerCase(Locale.ROOT).contains("no-retries")).orElse(false);
    }

    public Optional<Long> maxRspTimeMs() {
        return get(MAX_RSP_TIME).flatMap(v -> {
            try {
                return Optional.of(Long.parseLong(v.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    public Optional<String> correlationInfo() {
        return get(CORRELATION_INFO);
    }

    public Optional<String> targetApiRoot() {
        return get(TARGET_API_ROOT);
    }

    /** Extract only 3gpp-Sbi-* headers. */
    public Map<String, String> sbiOnly() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).startsWith("3gpp-sbi-")) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
