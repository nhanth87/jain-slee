/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.command;

import java.util.List;
import java.util.Map;

/**
 * Out-of-dialog REGISTER toward next CSCF hop (Mw) — P→I or I→S.
 *
 * <p>{@code requestUri} is typically the registrar / next CSCF SIP URI.
 * {@code extensionHeaders} whitelist IMS headers (Path, Authorization, …).
 */
public record SendRegister(
        String callId,
        String requestUri,
        String fromUri,
        String toUri,
        String contactUri,
        int expires,
        Map<String, List<String>> extensionHeaders
) implements SipOutboundCommand {

    public SendRegister {
        contactUri = contactUri == null ? "" : contactUri;
        expires = Math.max(0, expires);
        extensionHeaders = copyExt(extensionHeaders);
    }

    public SendRegister(String callId, String requestUri, String fromUri, String toUri,
                        String contactUri, int expires) {
        this(callId, requestUri, fromUri, toUri, contactUri, expires, Map.of());
    }

    private static Map<String, List<String>> copyExt(Map<String, List<String>> src) {
        if (src == null || src.isEmpty()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (var e : src.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }
}
