/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.avp;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * Structured Diameter AVP carried between the RA and SBBs.
 *
 * <p>The legacy {@code Map&lt;Integer,String&gt;} channel is fine for simple
 * single-valued UTF8String AVPs, but every grouped/octet/uint32 AVP required by
 * 3GPP S6a/Cx/Gx/Rx (Authentication-Info, E-UTRAN-Vector, SIP-Auth-Data-Item,
 * Subscription-Data, Charging-Rule-Definition, Flow-Information, QoS-Information,
 * ARP, …) needs vendor-id ({@code V} bit), mandatory ({@code M} bit) and a typed
 * value. This class is the unified carrier.</p>
 */
public final class DiameterAvp implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final long VENDOR_3GPP = 10415L;

    /** Supported value encodings (subset sufficient for EPC/IMS Diameter apps). */
    public enum Type {
        UTF8_STRING, OCTET_STRING, UNSIGNED32, INTEGER32, ENUMERATED32,
        GROUPED, ADDRESS, TIME, UNSIGNED64
    }

    private final int code;
    private final long vendorId;
    private final Type type;
    private final Object value; // String | byte[] | Long | Integer | List<DiameterAvp>
    private final boolean mandatory;

    private DiameterAvp(int code, long vendorId, Type type, Object value, boolean mandatory) {
        this.code = code;
        this.vendorId = vendorId;
        this.type = Objects.requireNonNull(type);
        this.value = value;
        this.mandatory = mandatory;
    }

    // ---- factories (default vendor 0 = no V bit, mandatory false) ----

    public static DiameterAvp utf8(int code, String value) {
        return utf8(code, 0, value, false);
    }

    public static DiameterAvp utf8(int code, long vendorId, String value, boolean mandatory) {
        return new DiameterAvp(code, vendorId, Type.UTF8_STRING, value, mandatory);
    }

    public static DiameterAvp octets(int code, byte[] value) {
        return octets(code, 0, value, false);
    }

    public static DiameterAvp octets(int code, long vendorId, byte[] value, boolean mandatory) {
        return new DiameterAvp(code, vendorId, Type.OCTET_STRING, value, mandatory);
    }

    public static DiameterAvp u32(int code, long value) {
        return u32(code, 0, value, false);
    }

    public static DiameterAvp u32(int code, long vendorId, long value, boolean mandatory) {
        return new DiameterAvp(code, vendorId, Type.UNSIGNED32, value, mandatory);
    }

    public static DiameterAvp i32(int code, long value) {
        return new DiameterAvp(code, 0, Type.INTEGER32, (int) value, false);
    }

    public static DiameterAvp enum32(int code, long value) {
        return enum32(code, 0, value, false);
    }

    public static DiameterAvp enum32(int code, long vendorId, long value, boolean mandatory) {
        return new DiameterAvp(code, vendorId, Type.ENUMERATED32, (int) value, mandatory);
    }

    /** 3GPP vendor (10415) enumerated AVP — the common EPC/IMS case. */
    public static DiameterAvp v3gpp(int code, long value) {
        return enum32(code, VENDOR_3GPP, value, true);
    }

    public static DiameterAvp grouped(int code, long vendorId, boolean mandatory, List<DiameterAvp> children) {
        return new DiameterAvp(code, vendorId, Type.GROUPED,
                children == null ? List.of() : List.copyOf(children), mandatory);
    }

    public static DiameterAvp grouped(int code, List<DiameterAvp> children) {
        return grouped(code, 0, true, children);
    }

    public static DiameterAvp grouped3gpp(int code, List<DiameterAvp> children) {
        return grouped(code, VENDOR_3GPP, true, children);
    }

    // ---- accessors ----

    public int code() { return code; }

    /** 0 means no V bit; non-zero sets V and encodes the vendor-id. */
    public long vendorId() { return vendorId; }

    public Type type() { return type; }

    public boolean mandatory() { return mandatory; }

    public Object value() { return value; }

    public String asUtf8() {
        return value instanceof String s ? s : null;
    }

    public byte[] asOctets() {
        return value instanceof byte[] b ? b : null;
    }

    public long asUnsigned32() {
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        return 0L;
    }

    @SuppressWarnings("unchecked")
    public List<DiameterAvp> children() {
        return type == Type.GROUPED && value instanceof List<?> list ? (List<DiameterAvp>) list : List.of();
    }

    @Override
    public String toString() {
        return "DiameterAvp{code=" + code + ", vendor=" + vendorId
                + ", type=" + type + ", mandatory=" + mandatory
                + (type == Type.GROUPED ? ", children=" + children().size() : ", value=" + value) + "}";
    }
}