package com.microjainslee.ra.gtpv2c;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/** TS 29.274 IE helpers. Codec lives in the RA; SBBs use these to read/write IEs. */
public final class Gtpv2Ies {

    public static final int CAUSE_REQUEST_ACCEPTED = 16;

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

    public static byte[] encodePaa(InetAddress ipv4) {
        byte[] addr = ipv4.getAddress();
        byte[] out = new byte[1 + addr.length];
        out[0] = 1; // IPv4
        System.arraycopy(addr, 0, out, 1, addr.length);
        return out;
    }

    public static Gtpv2Ie causeAccepted() {
        return new Gtpv2Ie(Gtpv2Ie.CAUSE, 0, new byte[] {(byte) CAUSE_REQUEST_ACCEPTED, 0});
    }
}
