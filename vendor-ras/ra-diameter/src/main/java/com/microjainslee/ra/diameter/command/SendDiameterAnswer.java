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
 * Send a Diameter answer back to the peer.
 *
 * <p>{@code avps} = legacy flat {@code avpCode → string}; {@code avpsStructured}
 * = full typed/grouped/vendor AVP tree (required for S6a/Cx/Gx/Rx grouped and
 * vendor-10415 AVPs). Both are merged on encode.</p>
 */
public record SendDiameterAnswer(
    String sessionId,
    long applicationId,
    int commandCode,
    long resultCode,
    long hopByHopId,
    long endToEndId,
    Map<Integer, String> avps,
    List<DiameterAvp> avpsStructured
) implements DiameterCommand {

    /** DIAMETER_SUCCESS (2001). */
    public static final long SUCCESS = 2001;

    public SendDiameterAnswer(String sessionId, long applicationId, int commandCode,
                              long resultCode, long hopByHopId, long endToEndId,
                              Map<Integer, String> avps) {
        this(sessionId, applicationId, commandCode, resultCode, hopByHopId, endToEndId,
                avps, List.of());
    }

    public static SendDiameterAnswer ok(String sessionId, long appId, int cmdCode,
                                         long hbh, long ete, Map<Integer, String> avps) {
        return new SendDiameterAnswer(sessionId, appId, cmdCode, SUCCESS, hbh, ete, avps);
    }

    public static SendDiameterAnswer ok(String sessionId, long appId, int cmdCode,
                                         long hbh, long ete, List<DiameterAvp> avps) {
        return new SendDiameterAnswer(sessionId, appId, cmdCode, SUCCESS, hbh, ete,
                Map.of(), avps);
    }

    public static SendDiameterAnswer error(String sessionId, long appId, int cmdCode,
                                            long hbh, long ete, long resultCode) {
        return new SendDiameterAnswer(sessionId, appId, cmdCode, resultCode, hbh, ete, Map.of());
    }
}
