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
        return toEvent(msg, null);
    }

    /**
     * @param wire every AVP of the message as received (re-parsed from its encoding), or
     *             {@code null}. Corsac maps the AVPs a command <em>defines</em> (Result-Code,
     *             Experimental-Result, Server-Name, SIP-Auth-Data-Item, Subscription-Data …)
     *             to typed getters, so without this only User-Name / Session-Id / Origin-*
     *             and unknown AVPs reached the SBB — no Cx/S6a answer could be read.
     */
    public static DiameterEvent toEvent(DiameterMessage msg, org.jdiameter.api.AvpSet wire) {
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

        if (wire != null) {
            flatten(wire, avps);
        }
        // The whole message as a typed tree (Corsac keeps the AVPs its dictionary knows in typed
        // fields, so its optional-AVP list missed e.g. Gx Subscription-Id / Charging-Rule-Install).
        List<DiameterAvp> structured = wire != null
                ? com.microjainslee.ra.diameter.collab.DiameterEventClassifier.structuredOf(wire)
                : extractStructured(msg);

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
        } else if (avps.containsKey(EXPERIMENTAL_RESULT_CODE)) {
            // TS 29.229 / 29.272: 3GPP outcomes travel as Experimental-Result, no Result-Code.
            result = Long.parseLong(avps.get(EXPERIMENTAL_RESULT_CODE));
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

    static final int EXPERIMENTAL_RESULT_CODE = 298;

    /** AVPs whose value is an Unsigned32 / Integer32 / Enumerated (rendered in decimal). */
    private static final java.util.Set<Integer> NUMERIC = java.util.Set.of(
            258, 259, 266, 268, 270, 277, 278, 298, 415, 416, 450, 607, 614, 616, 624, 633,
            1028, 1032, 1046, 1047, 1048, 1407, 1417, 1418, 1419, 513, 511, 512, 1027,
            // Gx (TS 29.212): Precedence, Online/Offline, bit rates, APN-AMBR, Flow-Direction.
            1010, 1008, 1009, 515, 516, 1025, 1026, 1040, 1041, 1080, 432, 439);

    /** Grouped AVPs that are walked into (their members land in the flat map). */
    private static final java.util.Set<Integer> GROUPED = java.util.Set.of(
            260, 297, 443, 603, 612, 615, 628, 1400, 1413, 1414, 1415, 1416,
            1001, 1003, 1016, 1034, 1049, 517, 519, 1058);

    /**
     * Every AVP into {@code out} (first value per code wins, already-set codes are kept):
     * numbers in decimal, printable UTF-8 as text, anything else as lower-case hex.
     * Grouped AVPs contribute their members.
     */
    static void flatten(org.jdiameter.api.AvpSet set, Map<Integer, String> out) {
        for (org.jdiameter.api.Avp avp : set) {
            int code = avp.getCode();
            try {
                if (GROUPED.contains(code)) {
                    flatten(avp.getGrouped(), out);
                    continue;
                }
                byte[] raw = avp.getRaw();
                if (raw == null) {
                    continue;
                }
                out.putIfAbsent(code, render(code, raw));
            } catch (org.jdiameter.api.AvpDataException ignored) {
                // malformed member: skip it, keep the rest
            }
        }
    }

    static String render(int code, byte[] raw) {
        if (NUMERIC.contains(code) && (raw.length == 4 || raw.length == 8)) {
            long v = 0;
            for (byte b : raw) {
                v = (v << 8) | (b & 0xff);
            }
            return Long.toUnsignedString(raw.length == 4 ? v & 0xffffffffL : v);
        }
        if (printable(raw)) {
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        }
        return java.util.HexFormat.of().formatHex(raw);
    }

    private static boolean printable(byte[] raw) {
        if (raw.length == 0) {
            return true;
        }
        for (byte b : raw) {
            int c = b & 0xff;
            if (c < 0x20 && c != '\t' || c == 0x7f) {
                return false;
            }
        }
        // valid UTF-8 (rejects arbitrary binary with the high bit set)
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(raw));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static DiameterCommandDefinition definition(DiameterMessage msg) {
        return msg == null ? null : DEFINITIONS.get(msg.getClass());
    }

    /**
     * Command definition of a Corsac message class. The annotation sits on the command
     * <em>interface</em> (e.g. {@code UserAuthorizationRequest}); the {@code ...Impl} the stack
     * decodes into does not carry it, and {@code @Inherited} never applies to interfaces — so
     * {@code getClass().getAnnotation} was always null and every inbound S6a / Cx / Gx / Rx
     * message was dropped before reaching the SLEE.
     */
    static DiameterCommandDefinition definitionOf(Class<?> type) {
        return type == null ? null : DEFINITIONS.get(type);
    }

    private static final ClassValue<DiameterCommandDefinition> DEFINITIONS = new ClassValue<>() {
        @Override
        protected DiameterCommandDefinition computeValue(Class<?> type) {
            return find(type, new java.util.HashSet<>());
        }
    };

    private static DiameterCommandDefinition find(Class<?> type, java.util.Set<Class<?>> seen) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (!seen.add(c)) {
                continue;
            }
            DiameterCommandDefinition d = c.getAnnotation(DiameterCommandDefinition.class);
            if (d != null) {
                return d;
            }
            for (Class<?> i : c.getInterfaces()) {
                d = find(i, seen);
                if (d != null) {
                    return d;
                }
            }
        }
        return null;
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
