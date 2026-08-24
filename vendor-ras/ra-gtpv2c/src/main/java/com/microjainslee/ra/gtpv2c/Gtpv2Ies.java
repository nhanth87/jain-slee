package com.microjainslee.ra.gtpv2c;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** TS 29.274 IE helpers. Codec lives in the RA; SBBs use these to read/write IEs. */
public final class Gtpv2Ies {

    public static final int CAUSE_REQUEST_ACCEPTED = 16;
    /** TS 29.274 Cause: Context not found. */
    public static final int CAUSE_CONTEXT_NOT_FOUND = 64;
    /** Default bearer EBI (TS 24.007 — first dedicated/default is 5). */
    public static final byte DEFAULT_BEARER_EBI = 5;

    private Gtpv2Ies() {}

    public static byte[] tbcd(String digits) {
        int n = (digits.length() + 1) / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(i) - '0';
            if (i % 2 == 0) {
                out[i / 2] = (byte) d;
            } else {
                out[i / 2] |= (byte) (d << 4);
            }
        }
        if (digits.length() % 2 == 1) {
            out[n - 1] |= (byte) 0xF0;
        }
        return out;
    }

    public static String tbcdToDigits(byte[] raw) {
        StringBuilder b = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            int lo = value & 0x0f;
            int hi = (value >> 4) & 0x0f;
            if (lo <= 9) {
                b.append((char) ('0' + lo));
            }
            if (hi <= 9) {
                b.append((char) ('0' + hi));
            }
        }
        return b.toString();
    }

    public static String imsiDigits(Gtpv2Message msg) {
        Gtpv2Ie ie = msg.first(Gtpv2Ie.IMSI);
        if (ie == null) {
            throw new IllegalArgumentException("Create Session missing IMSI");
        }
        return tbcdToDigits(ie.value());
    }

    public static String apn(Gtpv2Message msg) {
        Gtpv2Ie ie = msg.first(Gtpv2Ie.APN);
        if (ie == null) {
            return "internet";
        }
        return decodeApn(ie.value());
    }

    public static byte[] encodeApn(String apn) {
        byte[] label = apn.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[1 + label.length];
        out[0] = (byte) label.length;
        System.arraycopy(label, 0, out, 1, label.length);
        return out;
    }

    public static String decodeApn(byte[] raw) {
        if (raw.length == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (i < raw.length) {
            int n = raw[i] & 0xff;
            if (n == 0 || i + 1 + n > raw.length) {
                break;
            }
            if (!b.isEmpty()) {
                b.append('.');
            }
            b.append(new String(raw, i + 1, n, StandardCharsets.US_ASCII));
            i += 1 + n;
        }
        return b.toString();
    }

    public static byte[] encodeFteid(GtpFteid fteid) {
        byte[] addr = fteid.address().getAddress();
        boolean v4 = fteid.address() instanceof Inet4Address;
        byte[] out = new byte[5 + addr.length];
        out[0] = (byte) ((v4 ? 0x80 : 0x40) | (fteid.interfaceType() & 0x3f));
        out[1] = (byte) ((fteid.teid() >>> 24) & 0xff);
        out[2] = (byte) ((fteid.teid() >>> 16) & 0xff);
        out[3] = (byte) ((fteid.teid() >>> 8) & 0xff);
        out[4] = (byte) (fteid.teid() & 0xff);
        System.arraycopy(addr, 0, out, 5, addr.length);
        return out;
    }

    public static GtpFteid decodeFteid(byte[] raw) {
        if (raw == null || raw.length < 9) {
            throw new IllegalArgumentException("truncated F-TEID");
        }
        int iface = raw[0] & 0x3f;
        int teid = ((raw[1] & 0xff) << 24) | ((raw[2] & 0xff) << 16)
                | ((raw[3] & 0xff) << 8) | (raw[4] & 0xff);
        try {
            byte[] addr = new byte[4];
            System.arraycopy(raw, 5, addr, 0, 4);
            return new GtpFteid(teid, InetAddress.getByAddress(addr), iface);
        } catch (Exception e) {
            throw new IllegalArgumentException("F-TEID address", e);
        }
    }

    public static GtpFteid senderFteid(Gtpv2Message msg) {
        Gtpv2Ie ie = msg.first(Gtpv2Ie.FTEID);
        if (ie == null) {
            throw new IllegalArgumentException("missing F-TEID");
        }
        return decodeFteid(ie.value());
    }

    /**
     * PGW S5/S8 control-plane F-TEID carried by the MME in a
     * {@code CREATE_SESSION_REQUEST} (TS 29.274 "PGW S5/S8 address for control
     * plane or PMIP"). It is a top-level F-TEID distinct from the Sender F-TEID
     * (S11 MME); select it by interface type 5..8 (S5/S8 SGW/PGW). Returns
     * {@code null} when the MME did not provide one so the SGW-C can fall back to
     * its configured S5-C peer.
     */
    public static GtpFteid pgwS5cFteid(Gtpv2Message msg) {
        for (Gtpv2Ie ie : msg.ies()) {
            if (ie.type() == Gtpv2Ie.FTEID) {
                GtpFteid f = decodeFteid(ie.value());
                if (f.interfaceType() >= 5 && f.interfaceType() <= 8) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * TS 29.274 IE 93 — grouped Bearer Context: concatenated inner TLVs
     * (EBI + F-TEID + optional extras such as Cause).
     */
    public static byte[] encodeBearerContext(byte ebi, GtpFteid s1u, Gtpv2Ie... extra) {
        Objects.requireNonNull(s1u, "s1u");
        List<Gtpv2Ie> inner = new ArrayList<>();
        inner.add(new Gtpv2Ie(Gtpv2Ie.EBI, 0, new byte[] {ebi}));
        inner.add(new Gtpv2Ie(Gtpv2Ie.FTEID, 0, encodeFteid(s1u)));
        if (extra != null) {
            inner.addAll(List.of(extra));
        }
        return Gtpv2Codec.encodeIes(inner);
    }

    /** Same TLV walk as the outer GTPv2 IE list. */
    public static List<Gtpv2Ie> decodeGrouped(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        return Gtpv2Codec.decodeIes(raw);
    }

    /**
     * S1-U F-TEID from grouped Bearer Context. Legacy lab messages that stuffed a
     * bare F-TEID into IE 93 still decode. Falls back to the top-level F-TEID.
     */
    public static GtpFteid s1uFromBearerContext(Gtpv2Message msg) {
        Gtpv2Ie bc = msg.first(Gtpv2Ie.BEARER_CONTEXT);
        if (bc != null) {
            List<Gtpv2Ie> inner = decodeGrouped(bc.value());
            Gtpv2Ie nested = inner.stream().filter(i -> i.type() == Gtpv2Ie.FTEID).findFirst().orElse(null);
            if (nested != null) {
                return decodeFteid(nested.value());
            }
            boolean grouped = inner.stream().anyMatch(i -> i.type() == Gtpv2Ie.EBI || i.type() == Gtpv2Ie.CAUSE);
            if (!grouped && looksLikeBareFteid(bc.value())) {
                return decodeFteid(bc.value());
            }
        }
        return senderFteid(msg);
    }

    private static boolean looksLikeBareFteid(byte[] raw) {
        if (raw == null || raw.length < 9) {
            return false;
        }
        try {
            decodeFteid(raw);
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    public static byte[] encodePaa(InetAddress ipv4) {
        byte[] addr = ipv4.getAddress();
        byte[] out = new byte[1 + addr.length];
        out[0] = 1; // IPv4
        System.arraycopy(addr, 0, out, 1, addr.length);
        return out;
    }

    public static Gtpv2Ie causeAccepted() {
        return cause(CAUSE_REQUEST_ACCEPTED);
    }

    public static Gtpv2Ie cause(int value) {
        return new Gtpv2Ie(Gtpv2Ie.CAUSE, 0, new byte[] {(byte) value, 0});
    }
}
