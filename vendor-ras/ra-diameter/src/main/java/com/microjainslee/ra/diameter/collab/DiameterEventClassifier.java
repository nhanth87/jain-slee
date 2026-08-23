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

    private static Map<Integer, String> extractAllAvps(Message msg) {
        Map<Integer, String> map = new LinkedHashMap<>();
        try {
            AvpSet avps = msg.getAvps();
            for (Avp avp : avps) {
                int code = avp.getCode();
                Leaf leaf = leafOf(avp);
                if (leaf != null && leaf.utf8 != null) {
                    map.putIfAbsent(code, leaf.utf8);
                }
            }
        } catch (Exception ignored) { /* fall through */ }
        return map;
    }

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
        try {
            AvpSet group = avp.getGrouped();
            return DiameterAvp.grouped(code, vendor, m, extractStructured(group));
        } catch (Exception ignored) {
            // not grouped
        }
        Leaf leaf = leafOf(avp);
        if (leaf == null) {
            return DiameterAvp.octets(code, vendor, avp.getRawData(), m);
        }
        if (leaf.utf8 != null) {
            return DiameterAvp.utf8(code, vendor, leaf.utf8, m);
        }
        if (leaf.u32 != null) {
            return DiameterAvp.u32(code, vendor, leaf.u32, m);
        }
        return DiameterAvp.octets(code, vendor, leaf.octets, m);
    }

    /** Best-effort leaf decode: UTF8 string, unsigned32, or raw octets. */
    private static Leaf leafOf(Avp avp) {
        try {
            return new Leaf(avp.getUTF8String(), null, null);
        } catch (Exception ignored) { /* not UTF8 */ }
        try {
            return new Leaf(null, avp.getUnsigned32(), null);
        } catch (Exception ignored) { /* not unsigned32 */ }
        return new Leaf(null, null, avp.getRawData());
    }

    private record Leaf(String utf8, Long u32, byte[] octets) {}
}
