/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.events;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import java.util.List;
import java.util.Map;

/**
 * Generic Diameter answer event — any application, any command.
 *
 * <p>{@code avps} keeps the legacy flat {@code avpCode → string} view for
 * simple single-valued AVPs. {@code avpsStructured} carries the full typed,
 * grouped, vendor-aware AVP tree.</p>
 */
public record DiameterAnswerEvent(
    String sessionId,
    long applicationId,
    int commandCode,
    long resultCode,
    long hopByHopId,
    long endToEndId,
    String originHost,
    String originRealm,
    Map<Integer, String> avps,          // avpCode → string value (legacy flat view)
    List<DiameterAvp> avpsStructured     // typed/grouped/vendor AVPs (may be empty)
) implements DiameterEvent {

    /** Backward-compatible constructor (no structured AVPs). */
    public DiameterAnswerEvent(
            String sessionId, long applicationId, int commandCode, long resultCode,
            long hopByHopId, long endToEndId, String originHost, String originRealm,
            Map<Integer, String> avps) {
        this(sessionId, applicationId, commandCode, resultCode, hopByHopId, endToEndId,
                originHost, originRealm, avps, List.of());
    }
}
