/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.transport;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import com.mobius.software.telco.protocols.diameter.annotations.DiameterCommandDefinition;
import com.mobius.software.telco.protocols.diameter.commands.DiameterAnswer;
import com.mobius.software.telco.protocols.diameter.commands.DiameterMessage;
import com.mobius.software.telco.protocols.diameter.commands.DiameterRequest;
import com.mobius.software.telco.protocols.diameter.exceptions.AvpNotSupportedException;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterAvpKey;
import com.mobius.software.telco.protocols.diameter.primitives.DiameterUnknownAvp;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Corsac {@link DiameterMessage} → SLEE events. Base CER/CEA/DWR/DPR stay in the
 * stack (connection), never become SBB events.
 */
public final class CorsacEventBridge {

    private CorsacEventBridge() {}

    public static boolean isBaseProtocol(DiameterMessage msg) {
        DiameterCommandDefinition def = definition(msg);
        if (def == null) {
            return false;
        }
        int code = def.commandCode();
        return code == 257 || code == 280 || code == 282;
    }

    public static DiameterEvent toEvent(DiameterMessage msg) {
        DiameterCommandDefinition def = definition(msg);
        if (def == null) {
            return null;
        }
        String sessionId = sessionId(msg);
        long appId = def.applicationId();
        int cmd = def.commandCode();
        long hbh = msg.getHopByHopIdentifier() == null ? 0L : msg.getHopByHopIdentifier();
        long ete = msg.getEndToEndIdentifier() == null ? 0L : msg.getEndToEndIdentifier();
        String originHost = nullToEmpty(msg.getOriginHost());
        String originRealm = nullToEmpty(msg.getOriginRealm());
        Map<Integer, String> avps = new LinkedHashMap<>();
        putUtf8(avps, 1, username(msg));
        putUtf8(avps, 263, sessionId);
        putUtf8(avps, 264, originHost);
        putUtf8(avps, 296, originRealm);

        List<DiameterAvp> structured = extractStructured(msg);

        if (msg instanceof DiameterRequest req) {
            String destHost = nullToEmpty(req.getDestinationHost());
            String destRealm = nullToEmpty(req.getDestinationRealm());
            putUtf8(avps, 293, destHost);
            putUtf8(avps, 283, destRealm);
            return new DiameterRequestEvent(sessionId, appId, cmd, hbh, ete,
                    originHost, originRealm, destHost, destRealm, avps, structured);
        }
        long result = 2001L;
        if (msg instanceof DiameterAnswer ans && ans.getResultCode() != null) {
            result = ans.getResultCode();
        }
        return new DiameterAnswerEvent(sessionId, appId, cmd, result, hbh, ete,
                originHost, originRealm, avps, structured);
    }

    /**
     * Decode every optional application AVP into the structured tree. Grouped and
     * non-UTF (TBCD/BCD/octet) values stay structured-only.
     */
    private static List<DiameterAvp> extractStructured(DiameterMessage msg) {
        List<DiameterAvp> out = new ArrayList<>();
        Map<DiameterAvpKey, List<DiameterUnknownAvp>> optional;
        try {
            optional = msg.getOptionalAvps();
        } catch (Exception e) {
            return out;
        }
        if (optional == null || optional.isEmpty()) {
            return out;
        }
        for (Map.Entry<DiameterAvpKey, List<DiameterUnknownAvp>> e : optional.entrySet()) {
            DiameterAvpKey key = e.getKey();
            int code = key.getAvpID() == null ? 0 : key.getAvpID().intValue();
            long vendor = key.getVendorID() == null ? 0 : key.getVendorID();
            List<DiameterUnknownAvp> list = e.getValue();
            if (list == null) {
                continue;
            }
            for (DiameterUnknownAvp u : list) {
                boolean mandatory = Boolean.TRUE.equals(u.getIsMust());
                byte[] bytes = bytesOf(u);
                if (bytes == null) {
                    continue;
                }
                out.add(DiameterAvp.octets(code, vendor, bytes, mandatory));
            }
        }
        return out;
    }

    private static byte[] bytesOf(DiameterUnknownAvp u) {
        try {
            ByteBuf buf = u.getValue();
            if (buf == null) {
                return null;
            }
            byte[] b = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private static DiameterCommandDefinition definition(DiameterMessage msg) {
        return msg == null ? null : msg.getClass().getAnnotation(DiameterCommandDefinition.class);
    }

    private static String sessionId(DiameterMessage msg) {
        try {
            String sid = msg.getSessionId();
            return sid == null || sid.isBlank() ? "" : sid;
        } catch (AvpNotSupportedException e) {
            return "";
        }
    }

    private static String username(DiameterMessage msg) {
        try {
            return nullToEmpty(msg.getUsername());
        } catch (AvpNotSupportedException e) {
            return "";
        }
    }

    private static void putUtf8(Map<Integer, String> avps, int code, String value) {
        if (value != null && !value.isBlank()) {
            avps.put(code, value);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
