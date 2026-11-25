package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** PFCP message and IE codec (TS 29.244 §7.2.2 and §8.1.1). */
public final class PfcpCodec {

    private static final byte RULE_VERSION = 2;
    private static final int RULE_VERSION_1 = 1;
    private static final int UE_ABSENT = 0;
    private static final int UE_IPV4 = 1;

    private PfcpCodec() {}

    /** TS 29.244 §7.2.2: session messages carry SEID even when its value is zero. */
    public static byte[] encode(PfcpMessage msg) {
        boolean s = msg.type().seidInHeader();
        int headerLength = s ? 16 : 8;
        int messageLength = headerLength - 4 + msg.payload().length;
        if (messageLength > 65_535) {
            throw new IllegalArgumentException("PFCP message exceeds the 16-bit length");
        }
        ByteBuffer buf = ByteBuffer.allocate(headerLength + msg.payload().length).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) (0x20 | (s ? 0x01 : 0))); // Version=1, spare/FO/MP=0, S by message class.
        buf.put((byte) msg.type().code);
        buf.putShort((short) messageLength);
        if (s) {
            buf.putLong(msg.seid());
        }
        buf.put((byte) (msg.sequence() >>> 16));
        buf.put((byte) (msg.sequence() >>> 8));
        buf.put((byte) msg.sequence());
        buf.put((byte) 0); // Message priority is absent (MP=0); spare bits are zero.
        buf.put(msg.payload());
        return buf.array();
    }

    /** Inverse of {@link #encode(PfcpMessage)}, including the §7.2.2 length checks. */
    public static PfcpMessage decode(byte[] wire) {
        if (wire == null || wire.length < 8) {
            throw new IllegalArgumentException("truncated PFCP");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int flags = buf.get() & 0xff;
        if (((flags >>> 5) & 0x07) != 1) {
            throw new IllegalArgumentException("not PFCP v1");
        }
        boolean s = (flags & 0x01) != 0;
        PfcpMessageType type = PfcpMessageType.of(buf.get() & 0xff);
        int messageLength = buf.getShort() & 0xffff;
        int headerLength = s ? 16 : 8;
        if (messageLength != wire.length - 4 || messageLength < headerLength - 4) {
            throw new IllegalArgumentException("PFCP message length does not match the datagram");
        }
        if (type != PfcpMessageType.UNKNOWN && type.seidInHeader() != s) {
            throw new IllegalArgumentException("PFCP S flag does not match the message type");
        }
        long seid = s ? buf.getLong() : 0L;
        int seq = ((buf.get() & 0xff) << 16) | ((buf.get() & 0xff) << 8) | (buf.get() & 0xff);
        buf.get(); // Message priority/spare.
        byte[] payload = new byte[messageLength - (headerLength - 4)];
        buf.get(payload);
        return new PfcpMessage(type, seid, seq, payload);
    }

    /**
     * Legacy private rule payload (version 2, per-rule optional URR). Not TS 29.244 —
     * kept only so older peers' payloads still decode; {@code pfcp-ra} now sends
     * {@link PfcpIes#fromRules} IEs on the wire.
     */
    public static byte[] encodeRules(List<PfcpRule> rules, InetAddress ueIpv4) {
        List<PfcpRule> list = rules == null ? List.of() : rules;
        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        buf.put(RULE_VERSION);
        buf.put((byte) list.size());
        if (ueIpv4 != null) {
            byte[] a = ueIpv4.getAddress();
            buf.put((byte) UE_IPV4);
            buf.put((byte) a.length);
            buf.put(a);
        } else {
            buf.put((byte) UE_ABSENT);
        }
        for (PfcpRule r : list) {
            buf.putLong(r.seid());
            putStr(buf, r.pdrId());
            putFteid(buf, r.local());
            putFteid(buf, r.remote());
            putStr(buf, r.qerId());
            buf.put((byte) (r.uplink() ? 1 : 0));
            if (r.urr() != null) {
                buf.put((byte) 1);
                putUrr(buf, r.urr());
            } else {
                buf.put((byte) 0);
            }
        }
        byte[] out = new byte[buf.position()];
        buf.flip();
        buf.get(out);
        return out;
    }

    /**
     * Session rules carried by a Session Establishment / Modification Request. The
     * TS 29.244 IE form (Create PDR / FAR / QER / URR, see {@link PfcpIes#toRules}) is
     * what {@code pfcp-ra} sends; the legacy private payload of {@link #encodeRules}
     * (first octet 1 or 2) is still accepted. IE payloads start with a two-octet IE
     * type whose first octet is 0 for every IE used here.
     */
    public static PfcpRules decodeRules(long seid, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return PfcpRules.empty();
        }
        if (payload[0] == 0) {
            return PfcpIes.toRules(seid, decodeIes(payload));
        }
        return decodeLegacyRules(payload);
    }

    /** {@link #decodeRules(long, byte[])} with SEID 0 for the IE form. */
    public static PfcpRules decodeRules(byte[] payload) {
        return decodeRules(0L, payload);
    }

    /**
     * Inverse of {@link #encodeRules}. Accepts version 1 payloads (rules without
     * URRs); unknown versions return an empty payload.
     */
    private static PfcpRules decodeLegacyRules(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return PfcpRules.empty();
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int version = buf.get() & 0xff;
        if (version != RULE_VERSION && version != RULE_VERSION_1) {
            return PfcpRules.empty();
        }
        int count = buf.get() & 0xff;
        InetAddress ue = null;
        int ueFlag = buf.get() & 0xff;
        if (ueFlag == UE_IPV4) {
            int len = buf.get() & 0xff;
            byte[] a = new byte[len];
            buf.get(a);
            ue = addr(a);
        }
        List<PfcpRule> rules = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long seid = buf.getLong();
            String pdrId = getStr(buf);
            PfcpFteid local = getFteid(buf);
            PfcpFteid remote = getFteid(buf);
            String qerId = getStr(buf);
            boolean uplink = buf.get() != 0;
            PfcpUrr urr = null;
            if (version == RULE_VERSION && (buf.get() & 0xff) != 0) {
                urr = getUrr(buf);
            }
            rules.add(new PfcpRule(seid, pdrId, local, remote, qerId, uplink, urr));
        }
        return new PfcpRules(rules, ue);
    }

    private static void putStr(ByteBuffer buf, String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) b.length);
        buf.put(b);
    }

    private static void putFteid(ByteBuffer buf, PfcpFteid f) {
        buf.putInt(f.teid());
        byte[] a = f.address().getAddress();
        buf.put((byte) a.length);
        buf.put(a);
        buf.putInt(f.interfaceType());
    }

    private static void putUrr(ByteBuffer buf, PfcpUrr u) {
        buf.putInt(u.urrId());
        buf.put((byte) u.measurementMethod());
        buf.putShort((short) u.reportingTriggers());
        buf.putLong(u.volumeThresholdBytes());
        buf.putInt(u.timeThresholdSeconds());
    }

    private static PfcpUrr getUrr(ByteBuffer buf) {
        int urrId = buf.getInt();
        int measurementMethod = buf.get() & 0xff;
        int reportingTriggers = buf.getShort() & 0xffff;
        long volumeThresholdBytes = buf.getLong();
        int timeThresholdSeconds = buf.getInt();
        return new PfcpUrr(urrId, measurementMethod, reportingTriggers,
                volumeThresholdBytes, timeThresholdSeconds);
    }

    private static String getStr(ByteBuffer buf) {
        int len = buf.getShort() & 0xffff;
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static PfcpFteid getFteid(ByteBuffer buf) {
        int teid = buf.getInt();
        int len = buf.get() & 0xff;
        byte[] a = new byte[len];
        buf.get(a);
        int iface = buf.getInt();
        return new PfcpFteid(teid, addr(a), iface);
    }

    /**
     * Encodes a Session Report Request payload (TS 29.244 §7.5.8.1): mandatory
     * Report Type(USAR) followed by Usage Report(SRR) grouped IEs.
     */
    public static byte[] encodeUsageReports(List<PfcpUsageReport> reports) {
        return encodeUsageReports(reports, PfcpIe.USAGE_REPORT_SRR);
    }

    /**
     * Encodes Usage Report(SRR/SMR/SDR) groups (TS 29.244 §7.5.8.3, §7.5.5.2,
     * §7.5.7.2). Report Type is message-level and is therefore added only for SRR.
     */
    public static byte[] encodeUsageReports(List<PfcpUsageReport> reports, int usageReportType) {
        if (usageReportType != PfcpIe.USAGE_REPORT_SRR && usageReportType != PfcpIe.USAGE_REPORT_SMR
                && usageReportType != PfcpIe.USAGE_REPORT_SDR) {
            throw new IllegalArgumentException("not a PFCP Usage Report group type: " + usageReportType);
        }
        List<PfcpUsageReport> list = reports == null ? List.of() : reports;
        List<PfcpIe> out = new ArrayList<>();
        if (usageReportType == PfcpIe.USAGE_REPORT_SRR) {
            out.add(PfcpIes.reportType(PfcpIe.REPORT_TYPE_USAR));
        }
        for (PfcpUsageReport r : list) {
            out.add(PfcpIes.usageReport(usageReportType, r));
        }
        return encodeIes(out);
    }

    /** Inverse of {@link #encodeUsageReports}; accepts SRR, SMR and SDR groups. */
    public static List<PfcpUsageReport> decodeUsageReports(byte[] payload) {
        List<PfcpUsageReport> out = new ArrayList<>();
        for (PfcpIe ie : decodeIes(payload)) {
            if (ie.type() == PfcpIe.USAGE_REPORT_SRR || ie.type() == PfcpIe.USAGE_REPORT_SMR
                    || ie.type() == PfcpIe.USAGE_REPORT_SDR) {
                out.add(parseUsageReport(ie));
            }
        }
        return out;
    }

    /** True when the payload carries a Query URR IE (TS 29.244 §8.2.75). */
    public static boolean hasQueryUrr(byte[] payload) {
        if (payload == null || payload.length == 0 || payload[0] != 0) {
            return false; // empty or legacy private payload
        }
        for (PfcpIe ie : decodeIes(payload)) {
            if (ie.type() == PfcpIe.QUERY_URR) {
                return true;
            }
        }
        return false;
    }

    private static PfcpUsageReport parseUsageReport(PfcpIe group) {
        int urrId = 0;
        int trigger = 0;
        int duration = 0;
        long uplink = 0;
        long downlink = 0;
        for (PfcpIe ie : decodeIes(group.value())) {
            byte[] v = ie.value();
            switch (ie.type()) {
                case PfcpIe.URR_ID -> {
                    if (v.length >= 4) {
                        urrId = (int) getU32(v, 0);
                    }
                }
                case PfcpIe.USAGE_REPORT_TRIGGER -> trigger = PfcpIes.flags(v);
                case PfcpIe.VOLUME_MEASUREMENT -> {
                    int flags = v.length > 0 ? v[0] & 0xff : 0;
                    int off = 1;
                    if ((flags & PfcpIe.VM_TOVOL) != 0) {
                        off += 8; // total volume is not modelled
                    }
                    if ((flags & PfcpIe.VM_ULVOL) != 0 && v.length >= off + 8) {
                        uplink = getU64(v, off);
                        off += 8;
                    }
                    if ((flags & PfcpIe.VM_DLVOL) != 0 && v.length >= off + 8) {
                        downlink = getU64(v, off);
                    }
                }
                case PfcpIe.DURATION_MEASUREMENT -> {
                    if (v.length >= 4) {
                        duration = (int) getU32(v, 0);
                    }
                }
                default -> { }
            }
        }
        return new PfcpUsageReport(urrId, trigger, uplink, downlink, duration);
    }

    /** TS 29.244 §8.1.1: two-octet type + two-octet length + value. */
    public static byte[] encodeIes(List<PfcpIe> ies) {
        List<PfcpIe> list = ies == null ? List.of() : ies;
        int n = 0;
        for (PfcpIe ie : list) {
            n += 4 + ie.value().length;
        }
        ByteBuffer buf = ByteBuffer.allocate(n).order(ByteOrder.BIG_ENDIAN);
        for (PfcpIe ie : list) {
            buf.putShort((short) ie.type());
            buf.putShort((short) ie.value().length);
            buf.put(ie.value());
        }
        return buf.array();
    }

    /** Inverse of {@link #encodeIes}; truncated or trailing non-TLV bytes are rejected. */
    public static List<PfcpIe> decodeIes(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<PfcpIe> out = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        while (buf.hasRemaining()) {
            if (buf.remaining() < 4) {
                throw new IllegalArgumentException("truncated PFCP IE header");
            }
            int type = buf.getShort() & 0xffff;
            int len = buf.getShort() & 0xffff;
            if (buf.remaining() < len) {
                throw new IllegalArgumentException("truncated PFCP IE value");
            }
            byte[] v = new byte[len];
            buf.get(v);
            out.add(new PfcpIe(type, v));
        }
        return out;
    }

    private static InetAddress addr(byte[] a) {
        try {
            return InetAddress.getByAddress(a);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bad address", e);
        }
    }

    private static long getU32(byte[] v, int off) {
        return ((long) (v[off] & 0xff) << 24) | ((v[off + 1] & 0xff) << 16)
                | ((v[off + 2] & 0xff) << 8) | (v[off + 3] & 0xff);
    }

    private static long getU64(byte[] v, int off) {
        long x = 0;
        for (int i = 0; i < 8; i++) {
            x = (x << 8) | (v[off + i] & 0xffL);
        }
        return x;
    }
}
