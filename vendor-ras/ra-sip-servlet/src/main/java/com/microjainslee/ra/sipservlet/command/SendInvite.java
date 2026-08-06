/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.command;

import java.util.List;
import java.util.Map;

/**
 * Send an INVITE request — SBB provides AoR, RA resolves DNS.
 *
 * <p>{@code extensionHeaders} are whitelist IMS/3GPP headers to copy onto the
 * outbound INVITE (name → header values without the name prefix). Use
 * {@link com.microjainslee.ra.sipservlet.ims.ImsSipHeaderNames#INVITE_PRESERVE}.
 */
public record SendInvite(
        String callId,
        String toUri,
        String fromUri,
        String sdp,
        Map<String, List<String>> extensionHeaders
) implements SipOutboundCommand {

    public SendInvite {
        sdp = sdp == null ? "" : sdp;
        extensionHeaders = copyExt(extensionHeaders);
    }

    /** Lab / legacy: no IMS extension headers. */
    public SendInvite(String callId, String toUri, String fromUri, String sdp) {
        this(callId, toUri, fromUri, sdp, Map.of());
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
