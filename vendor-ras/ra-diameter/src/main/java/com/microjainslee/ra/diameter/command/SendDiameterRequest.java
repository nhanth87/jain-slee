/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.command;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import java.util.List;
import java.util.Map;

/**
 * Send an outbound Diameter request to a peer (client-initiated).
 *
 * <p>{@code avps} = legacy flat view; {@code avpsStructured} = typed/grouped/
 * vendor tree. Both are merged on encode.</p>
 */
public record SendDiameterRequest(
    String sessionId,
    long applicationId,
    int commandCode,
    String destinationHost,
    String destinationRealm,
    Map<Integer, String> avps,
    List<DiameterAvp> avpsStructured
) implements DiameterCommand {

    public SendDiameterRequest(String sessionId, long applicationId, int commandCode,
                               String destinationHost, String destinationRealm,
                               Map<Integer, String> avps) {
        this(sessionId, applicationId, commandCode, destinationHost, destinationRealm,
                avps, List.of());
    }
}
