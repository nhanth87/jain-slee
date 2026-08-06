/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.events;

import java.util.List;
import java.util.Map;

/**
 * SIP INVITE event fired when a new dialog is initiated.
 *
 * <p>{@code imsHeaders} carries 3GPP/IMS private headers (TS 24.229) extracted
 * by the classifier — see {@link com.microjainslee.ra.sipservlet.ims.ImsSipHeaderNames}.
 * The RA is signaling-only (not an SBC); it does not relay RTP.
 */
public record SipInviteEvent(
        String callId,
        String fromUri,
        String toUri,
        String contactUri,
        List<String> viaHeaders,
        List<String> recordRoute,
        List<String> route,
        String sdpBody,
        String contentType,
        Map<String, List<String>> imsHeaders
) implements SipEvent {

    public SipInviteEvent {
        viaHeaders = viaHeaders == null ? List.of() : List.copyOf(viaHeaders);
        recordRoute = recordRoute == null ? List.of() : List.copyOf(recordRoute);
        route = route == null ? List.of() : List.copyOf(route);
        sdpBody = sdpBody == null ? "" : sdpBody;
        contentType = contentType == null ? "" : contentType;
        imsHeaders = copyIms(imsHeaders);
    }

    /** Legacy / lab callers without IMS map. */
    public SipInviteEvent(
            String callId,
            String fromUri,
            String toUri,
            String contactUri,
            List<String> viaHeaders,
            List<String> recordRoute,
            List<String> route,
            String sdpBody,
            String contentType) {
        this(callId, fromUri, toUri, contactUri, viaHeaders, recordRoute, route,
                sdpBody, contentType, Map.of());
    }

    private static Map<String, List<String>> copyIms(Map<String, List<String>> src) {
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

    @Override
    public String method() {
        return "INVITE";
    }

    public String pAssertedIdentity() {
        return firstIms("P-Asserted-Identity");
    }

    public String pAccessNetworkInfo() {
        return firstIms("P-Access-Network-Info");
    }

    public String pChargingVector() {
        return firstIms("P-Charging-Vector");
    }

    private String firstIms(String name) {
        List<String> vals = imsHeaders.get(name);
        return vals == null || vals.isEmpty() ? "" : vals.getFirst();
    }
}
