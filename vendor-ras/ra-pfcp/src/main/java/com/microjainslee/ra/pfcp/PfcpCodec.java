package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Minimal PFCP codec (TS 29.244 header). S flag set when SEID != 0. */
public final class PfcpCodec {

    private static final byte RULE_VERSION = 2;
    private static final int RULE_VERSION_1 = 1;
    private static final int UE_ABSENT = 0;
    private static final int UE_IPV4 = 1;

    private PfcpCodec() {}

    public static byte[] encode(PfcpMessage msg) {
        boolean s = msg.seid() != 0;
        int header = s ? 16 : 8;
        ByteBuffer buf = ByteBuffer.allocate(header + msg.payload().length).order(ByteOrder.BIG_ENDIAN);
        int flags = 0x20; // version 1
        if (s) {
            flags |= 0x01;
        }
        buf.put((byte) flags);
        buf.put((byte) msg.type().code);
        buf.putShort((short) (header - 4 + msg.payload().length));
        if (s) {
            buf.putLong(msg.seid());
        }
        buf.put((byte) ((msg.sequence() >> 16) & 0xff));
        buf.put((byte) ((msg.sequence() >> 8) & 0xff));
        buf.put((byte) (msg.sequence() & 0xff));
        buf.put((byte) 0);
        buf.put(msg.payload());
        return buf.array();
    }

    public static PfcpMessage decode(byte[] wire) {
        if (wire == null || wire.length < 8) {
            throw new IllegalArgumentException("truncated PFCP");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int flags = buf.get() & 0xff;
        if (((flags >> 5) & 0x07) != 1) {
            throw new IllegalArgumentException("not PFCP v1");
        }
        boolean s = (flags & 0x01) != 0;
        PfcpMessageType type = PfcpMessageType.of(buf.get() & 0xff);
        buf.getShort();
        long seid = s ? buf.getLong() : 0L;
        int seq = ((buf.get() & 0xff) << 16) | ((buf.get() & 0xff) << 8) | (buf.get() & 0xff);
        buf.get();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new PfcpMessage(type, seid, seq, payload);
    }

    /**
     * Encodes forwarding rules plus the optional UE IPv4 into a session-request
     * payload (version 2, per-rule optional URR). Lossless so PDR/FAR/QER/URR
     * rule data actually crosses the Sxa/Sxb wire instead of a bare rule count.
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
     * Inverse of {@link #encodeRules}. Accepts version 1 payloads (rules without
     * URRs); unknown versions return an empty payload.
     */
    public static PfcpRules decodeRules(byte[] payload) {
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
     * Encodes usage reports as grouped Usage Report (SRR) IEs (TS 29.244 §8.2.39):
     * URR ID u32, Usage Report Trigger u32, Volume Measurement with UL u64 + DL u64,
     * and Duration Measurement u32 seconds. The result is a TLV payload ready for a
     * PFCP Session Report Request.
     */
    public static byte[] encodeUsageReports(List<PfcpUsageReport> reports) {
        List<PfcpUsageReport> list = reports == null ? List.of() : reports;
        List<PfcpIe> out = new ArrayList<>();
        for (PfcpUsageReport r : list) {
            out.add(new PfcpIe(PfcpIe.USAGE_REPORT_SRR, encodeIes(List.of(
                    new PfcpIe(PfcpIe.URR_ID, u32(r.urrId())),
                    new PfcpIe(PfcpIe.USAGE_REPORT_TRIGGER, u32(r.usageReportTrigger())),
                    volumeMeasurement(r.uplinkVolume(), r.downlinkVolume()),
                    new PfcpIe(PfcpIe.DURATION_MEASUREMENT, u32(r.durationSeconds()))))));
        }
        return encodeIes(out);
    }

    /** Inverse of {@link #encodeUsageReports}; also accepts Usage Report (SMR) groups. */
    public static List<PfcpUsageReport> decodeUsageReports(byte[] payload) {
        List<PfcpUsageReport> out = new ArrayList<>();
        for (PfcpIe ie : decodeIes(payload)) {
            if (ie.type() == PfcpIe.USAGE_REPORT_SRR || ie.type() == PfcpIe.USAGE_REPORT_SMR) {
                out.add(parseUsageReport(ie));
            }
        }
        return out;
    }

    /** True when the payload carries a Query URR IE (TS 29.244 §8.2.75). */
    public static boolean hasQueryUrr(byte[] payload) {
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
                case PfcpIe.USAGE_REPORT_TRIGGER -> {
                    if (v.length >= 4) {
                        trigger = (int) getU32(v, 0);
                    }
                }
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

    /** Volume Measurement (TS 29.244 §8.2.45): ULVOL|DLVOL flags + UL u64 + DL u64. */
    private static PfcpIe volumeMeasurement(long uplink, long downlink) {
        byte[] v = new byte[17];
        v[0] = (byte) (PfcpIe.VM_ULVOL | PfcpIe.VM_DLVOL);
        putU64(v, 1, uplink);
        putU64(v, 9, downlink);
        return new PfcpIe(PfcpIe.VOLUME_MEASUREMENT, v);
    }

    /** TS 29.244 TLV framing: type (1 octet) + length (2 octets, big-endian) + value. */
    public static byte[] encodeIes(List<PfcpIe> ies) {
        List<PfcpIe> list = ies == null ? List.of() : ies;
        int n = 0;
        for (PfcpIe ie : list) {
            n += 3 + ie.value().length;
        }
        ByteBuffer buf = ByteBuffer.allocate(n).order(ByteOrder.BIG_ENDIAN);
        for (PfcpIe ie : list) {
            buf.put((byte) ie.type());
            buf.putShort((short) ie.value().length);
            buf.put(ie.value());
        }
        return buf.array();
    }

    /** Inverse of {@link #encodeIes}; malformed trailing bytes are ignored. */
    public static List<PfcpIe> decodeIes(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<PfcpIe> out = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 3) {
            int type = buf.get() & 0xff;
            int len = buf.getShort() & 0xffff;
            if (buf.remaining() < len) {
                break;
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

    private static byte[] u32(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static long getU32(byte[] v, int off) {
        return ((long) (v[off] & 0xff) << 24) | ((v[off + 1] & 0xff) << 16)
                | ((v[off + 2] & 0xff) << 8) | (v[off + 3] & 0xff);
    }

    private static void putU64(byte[] v, int off, long x) {
        for (int i = 0; i < 8; i++) {
            v[off + i] = (byte) (x >>> (56 - 8 * i));
        }
    }

    private static long getU64(byte[] v, int off) {
        long x = 0;
        for (int i = 0; i < 8; i++) {
            x = (x << 8) | (v[off + i] & 0xffL);
        }
        return x;
    }
}
