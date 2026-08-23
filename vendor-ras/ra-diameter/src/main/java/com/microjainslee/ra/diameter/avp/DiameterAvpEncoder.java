/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.avp;

import java.util.List;
import org.jdiameter.api.AvpSet;

/**
 * Encodes a List of {@link DiameterAvp} into a JDiameter {@link AvpSet} with the
 * correct type, vendor-id (V bit) and mandatory (M) flags. Grouped AVPs recurse.
 *
 * <p>JDiameter's {@code addGroupedAvp(code, vendorId, m, p)} is the grouped-entry
 * point; scalar {@code addAvp(code, value, vendorId, m, p[, octet])} variants carry
 * the vendor-id. Vendor 0 selects the non-vendor overload (no V bit).</p>
 */
public final class DiameterAvpEncoder {

    private DiameterAvpEncoder() {}

    /**
     * Append {@code avps} to {@code target}.
     * @param target the JDiameter AvpSet to populate (message avp set or a group)
     */
    public static void encode(List<DiameterAvp> avps, AvpSet target) {
        if (avps == null) return;
        for (DiameterAvp avp : avps) {
            encode(avp, target);
        }
    }

    public static void encode(DiameterAvp avp, AvpSet target) {
        long vendor = avp.vendorId();
        boolean m = avp.mandatory();
        boolean useVendor = vendor != 0;
        switch (avp.type()) {
            case GROUPED -> {
                AvpSet group = useVendor
                        ? target.addGroupedAvp(avp.code(), vendor, m, false)
                        : target.addGroupedAvp(avp.code(), m, false);
                encode(avp.children(), group);
            }
            case UTF8_STRING -> {
                String s = avp.asUtf8() == null ? "" : avp.asUtf8();
                if (useVendor) {
                    target.addAvp(avp.code(), s, vendor, m, false, false);
                } else {
                    target.addAvp(avp.code(), s, m, false, false);
                }
            }
            case OCTET_STRING -> {
                byte[] b = avp.asOctets() == null ? new byte[0] : avp.asOctets();
                if (useVendor) {
                    target.addAvp(avp.code(), b, vendor, m, false);
                } else {
                    target.addAvp(avp.code(), b, m, false);
                }
            }
            case UNSIGNED32 -> {
                long v = avp.asUnsigned32();
                if (useVendor) {
                    target.addAvp(avp.code(), v, vendor, m, false, true);
                } else {
                    target.addAvp(avp.code(), v, m, false, true);
                }
            }
            case INTEGER32, ENUMERATED32 -> {
                int v = (int) avp.asUnsigned32();
                if (useVendor) {
                    target.addAvp(avp.code(), v, vendor, m, false);
                } else {
                    target.addAvp(avp.code(), v, m, false);
                }
            }
            case UNSIGNED64 -> {
                long v = avp.asUnsigned32();
                if (useVendor) {
                    target.addAvp(avp.code(), v, vendor, m, false);
                } else {
                    target.addAvp(avp.code(), v, m, false);
                }
            }
            case ADDRESS, TIME -> {
                String s = avp.asUtf8() == null ? "" : avp.asUtf8();
                if (useVendor) {
                    target.addAvp(avp.code(), s, vendor, m, false, true);
                } else {
                    target.addAvp(avp.code(), s, m, false, true);
                }
            }
            default -> { /* unsupported type: skip */ }
        }
    }
}