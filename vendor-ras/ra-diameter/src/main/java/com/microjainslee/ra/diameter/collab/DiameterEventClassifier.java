/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.collab;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Classifies raw JDiameter {@link Message} into typed {@link DiameterEvent}.
 *
 * <p>Request → {@link DiameterRequestEvent}, Answer → {@link DiameterAnswerEvent}.
 * The {@code applicationId} field allows SBBs to switch on Diameter
 * application (Cx=16777216, Sh=16777217, Gx=16777238, Ro=4).</p>
 */
public final class DiameterEventClassifier {
    private static final Logger LOG = LogManager.getLogger(DiameterEventClassifier.class);

    // Common Diameter AVP codes
    private static final int SESSION_ID         = 263;
    private static final int ORIGIN_HOST        = 264;
    private static final int ORIGIN_REALM       = 296;
    private static final int DESTINATION_HOST   = 293;
    private static final int DESTINATION_REALM  = 283;
    private static final int RESULT_CODE        = 268;

    public DiameterEvent classify(Message msg) {
        try {
            int cmdCode = msg.getCommandCode();
            long appId = msg.getApplicationId();
            long hbh = msg.getHopByHopIdentifier();
            long ete = msg.getEndToEndIdentifier();
            String sessId = extractSessionId(msg);
            String originHost = extractUTF8String(msg, ORIGIN_HOST);
            String originRealm = extractUTF8String(msg, ORIGIN_REALM);
            Map<Integer, String> avps = extractAllAvps(msg);
            List<DiameterAvp> structured = extractStructured(msg.getAvps());

            if (msg.isRequest()) {
                String destHost = extractUTF8String(msg, DESTINATION_HOST);
                String destRealm = extractUTF8String(msg, DESTINATION_REALM);
                return new DiameterRequestEvent(sessId, appId, cmdCode, hbh, ete,
                        originHost, originRealm, destHost, destRealm, avps, structured);
            } else {
                long resultCode = extractUnsigned32(msg, RESULT_CODE, 2001);
                return new DiameterAnswerEvent(sessId, appId, cmdCode, resultCode, hbh, ete,
                        originHost, originRealm, avps, structured);
            }
        } catch (Exception e) {
            LOG.warn("Failed to classify Diameter message", e);
            return null;
        }
    }

    // ---- helpers ----

    private static String extractSessionId(Message msg) {
        try {
            Avp avp = msg.getAvps().getAvp(SESSION_ID);
            if (avp != null) return avp.getUTF8String();
        } catch (Exception ignored) { /* fall through */ }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String extractUTF8String(Message msg, int code) {
        try {
            Avp avp = msg.getAvps().getAvp(code);
            if (avp != null) return avp.getUTF8String();
        } catch (Exception ignored) { /* fall through */ }
        return "";
    }

    private static long extractUnsigned32(Message msg, int code, long def) {
        try {
            Avp avp = msg.getAvps().getAvp(code);
            if (avp != null) return avp.getUnsigned32();
        } catch (Exception ignored) { /* fall through */ }
        return def;
    }

    /** Flat legacy view: printable strings as-is, 4-octet numbers in decimal. */
    private static Map<Integer, String> extractAllAvps(Message msg) {
        Map<Integer, String> map = new LinkedHashMap<>();
        try {
            AvpSet avps = msg.getAvps();
            for (Avp avp : avps) {
                Leaf leaf = leafOf(rawOf(avp));
                if (leaf.utf8 != null) {
                    map.putIfAbsent(avp.getCode(), leaf.utf8);
                } else if (leaf.u32 != null) {
                    map.putIfAbsent(avp.getCode(), Long.toString(leaf.u32));
                }
            }
        } catch (Exception ignored) { /* fall through */ }
        return map;
    }

    /**
     * Typed tree of a whole message (every AVP, grouped ones walked), for transports that hold
     * a JDiameter {@link AvpSet} of the received message.
     */
    public static List<DiameterAvp> structuredOf(AvpSet avps) {
        return extractStructured(avps);
    }

    /**
     * Grouped AVPs of the base protocol and the 3GPP applications carried here (TS 29.212 Gx,
     * 29.214 Rx, 29.272 S6a, 29.229 Cx, RFC 4006): walked even when the dictionary-less probe
     * would not recognise them.
     */
    static final java.util.Set<Integer> GROUPED = java.util.Set.of(
            260, 279, 284, 297, 443, 456, 458, 873, 874,
            431, 1001, 1002, 1003, 1016, 1034, 1049, 1058, 1067,
            517, 519, 603, 612, 615, 628,
            1400, 1413, 1414, 1415, 1416, 1431, 1432, 1435, 1436);

    /** Recursively collect a typed/grouped mirror of the JDiameter AVP set. */
    private static List<DiameterAvp> extractStructured(AvpSet avps) {
        List<DiameterAvp> out = new ArrayList<>();
        if (avps == null) {
            return out;
        }
        for (Avp avp : avps) {
            out.add(toAvp(avp));
        }
        return out;
    }

    private static DiameterAvp toAvp(Avp avp) {
        int code = avp.getCode();
        long vendor = avp.isVendorId() ? avp.getVendorId() : 0;
        boolean m = avp.isMandatory();
        byte[] raw = rawOf(avp);
        // Only a value that parses as a set of AVPs is grouped (a leaf is never a valid AVP header + body).
        if (raw != null && raw.length >= 8 && (GROUPED.contains(code) || looksGrouped(raw))) {
            try {
                AvpSet group = avp.getGrouped();
                if (group != null && group.size() > 0) {
                    return DiameterAvp.grouped(code, vendor, m, extractStructured(group));
                }
            } catch (Exception ignored) {
                // not grouped
            }
        }
        Leaf leaf = leafOf(raw);
        if (leaf.utf8 != null) {
            return DiameterAvp.received(code, vendor, m, DiameterAvp.Type.UTF8_STRING, leaf.utf8, raw);
        }
        if (leaf.u32 != null) {
            return DiameterAvp.received(code, vendor, m, DiameterAvp.Type.UNSIGNED32, leaf.u32, raw);
        }
        return DiameterAvp.received(code, vendor, m, DiameterAvp.Type.OCTET_STRING, leaf.octets, raw);
    }

    /** First AVP header plausible: length >= 8 and within the value (RFC 6733 §4.1). */
    private static boolean looksGrouped(byte[] raw) {
        int len = ((raw[5] & 0xff) << 16) | ((raw[6] & 0xff) << 8) | (raw[7] & 0xff);
        boolean vendor = (raw[4] & 0x80) != 0;
        return len >= (vendor ? 12 : 8) && len <= raw.length;
    }

    private static byte[] rawOf(Avp avp) {
        try {
            return avp.getRawData();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Dictionary-less leaf decode. JDiameter's {@code getUTF8String} accepts any octets, so it
     * cannot tell a string from a number: an Unsigned32 / Enumerated / Address leaf became a
     * string of control characters and its value was lost. A leaf is UTF-8 only when it decodes
     * strictly and every character is printable; otherwise 4 octets are an Unsigned32 /
     * Integer32 / Enumerated, and anything else stays octets. The raw value is always kept.
     */
    static Leaf leafOf(byte[] raw) {
        if (raw == null) {
            return new Leaf(null, null, new byte[0]);
        }
        if (raw.length > 0) {
            try {
                String s = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(raw)).toString();
                if (s.chars().noneMatch(c -> Character.isISOControl(c))) {
                    return new Leaf(s, null, null);
                }
            } catch (java.nio.charset.CharacterCodingException notUtf8) {
                // fall through
            }
        }
        if (raw.length == 4) {
            return new Leaf(null, ((raw[0] & 0xffL) << 24) | ((raw[1] & 0xffL) << 16)
                    | ((raw[2] & 0xffL) << 8) | (raw[3] & 0xffL), null);
        }
        return new Leaf(null, null, raw);
    }

    record Leaf(String utf8, Long u32, byte[] octets) {}
}
