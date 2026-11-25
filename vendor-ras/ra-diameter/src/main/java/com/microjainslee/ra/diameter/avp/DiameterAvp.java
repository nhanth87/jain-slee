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
    /** The received value octets (inbound leaves only; null when built by an application). */
    private final byte[] raw;

    private DiameterAvp(int code, long vendorId, Type type, Object value, boolean mandatory) {
        this(code, vendorId, type, value, mandatory, null);
    }

    private DiameterAvp(int code, long vendorId, Type type, Object value, boolean mandatory, byte[] raw) {
        this.code = code;
        this.vendorId = vendorId;
        this.type = Objects.requireNonNull(type);
        this.value = value;
        this.mandatory = mandatory;
        this.raw = raw;
    }

    /**
     * An inbound leaf AVP: a best-effort typed {@code value} plus the received octets, so a
     * reader that knows the real type (from its own dictionary) can still decode it:
     * {@link #asOctets()}, {@link #asUnsigned32()} and {@link #asUtf8()} fall back to the raw value.
     */
    public static DiameterAvp received(int code, long vendorId, boolean mandatory, Type type, Object value,
                                       byte[] raw) {
        return new DiameterAvp(code, vendorId, type, value, mandatory, raw == null ? null : raw.clone());
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

    /** Unsigned64 (RFC 6733 §4.2), e.g. CC-Total-Octets (RFC 4006 §8.14). */
    public static DiameterAvp u64(int code, long value) {
        return new DiameterAvp(code, 0L, Type.UNSIGNED64, value, true);
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
        if (value instanceof String s) {
            return s;
        }
        return raw == null ? null : new String(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] asOctets() {
        if (value instanceof byte[] b) {
            return b;
        }
        return raw == null ? null : raw.clone();
    }

    /** Unsigned32 / Integer32 / Enumerated value; a received 4-octet leaf decodes big-endian. */
    public long asUnsigned32() {
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (raw != null && raw.length == 4) {
            return ((raw[0] & 0xffL) << 24) | ((raw[1] & 0xffL) << 16) | ((raw[2] & 0xffL) << 8) | (raw[3] & 0xffL);
        }
        if (raw != null && raw.length == 8) {
            long v = 0;
            for (byte x : raw) {
                v = (v << 8) | (x & 0xffL);
            }
            return v;
        }
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