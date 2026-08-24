package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * TS 29.244 IE builders/parsers. The codec (TLV framing) lives in
 * {@link PfcpCodec}; SBBs use these helpers to read/write the structured IEs
 * (Node ID, CP F-SEID, Recovery, Create PDR/FAR/QER/URR/BAR).
 *
 * <p>Byte layouts follow TS 29.244 (cross-checked against open5gs):</p>
 * <ul>
 *   <li>F-TEID flags: V4=0x80, V6=0x40, CH=0x20, CHID=0x10.</li>
 *   <li>F-SEID flags: V6=0x80, V4=0x40.</li>
 *   <li>Node ID type nibble = high nibble (0=IPv4,1=IPv6,2=FQDN).</li>
 *   <li>Outer Header Creation = flags(0x15 GTP-U/UDP/IPv4) + flags2 + TEID + addr.</li>
 * </ul>
 */
public final class PfcpIes {

    private PfcpIes() {}

    // ---- scalar IE builders ----

    public static PfcpIe nodeId(PfcpNodeId id) {
        if (id.address() != null) {
            byte[] a = id.address().getAddress();
            byte[] v = new byte[1 + a.length];
            v[0] = (byte) (a.length == 16 ? 0x10 : 0x00);
            System.arraycopy(a, 0, v, 1, a.length);
            return new PfcpIe(PfcpIe.NODE_ID, v);
        }
        byte[] f = id.fqdn().getBytes(StandardCharsets.US_ASCII);
        byte[] v = new byte[1 + f.length];
        v[0] = 0x20;
        System.arraycopy(f, 0, v, 1, f.length);
        return new PfcpIe(PfcpIe.NODE_ID, v);
    }

    public static PfcpIe recoveryTimeStamp(int seconds) {
        return new PfcpIe(PfcpIe.RECOVERY_TIME_STAMP, u32(seconds));
    }

    public static PfcpIe fSeid(long seid, InetAddress ipv4) {
        boolean v4 = ipv4 != null;
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + (v4 ? ipv4.getAddress().length : 0))
                .order(ByteOrder.BIG_ENDIAN);
        b.put((byte) (v4 ? 0x40 : 0x00));
        b.putLong(seid);
        if (v4) {
            b.put(ipv4.getAddress());
        }
        return new PfcpIe(PfcpIe.F_SEID, b.array());
    }

    public static PfcpIe fTeid(PfcpFteid f) {
        byte[] a = f.address().getAddress();
        boolean v4 = a.length == 4;
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + a.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) (v4 ? 0x80 : 0x40));
        b.putInt(f.teid());
        b.put(a);
        return new PfcpIe(PfcpIe.F_TEID, b.array());
    }

    public static PfcpIe cause(int code) {
        return new PfcpIe(PfcpIe.CAUSE, new byte[] {(byte) code});
    }

    public static PfcpIe pdrId(int id) {
        return u16Ie(PfcpIe.PDR_ID, id);
    }

    public static PfcpIe precedence(int precedence) {
        return u32Ie(PfcpIe.PRECEDENCE, precedence);
    }

    public static PfcpIe sourceInterface(int iface) {
        return new PfcpIe(PfcpIe.SOURCE_INTERFACE, new byte[] {(byte) iface});
    }

    public static PfcpIe destinationInterface(int iface) {
        return new PfcpIe(PfcpIe.DESTINATION_INTERFACE, new byte[] {(byte) iface});
    }

    public static PfcpIe applyAction(int bits) {
        return new PfcpIe(PfcpIe.APPLY_ACTION, new byte[] {(byte) bits});
    }

    public static PfcpIe farId(int id) {
        return u32Ie(PfcpIe.FAR_ID, id);
    }

    public static PfcpIe qerId(int id) {
        return u32Ie(PfcpIe.QER_ID, id);
    }

    public static PfcpIe urrId(int id) {
        return u32Ie(PfcpIe.URR_ID, id);
    }

    public static PfcpIe barId(int id) {
        return new PfcpIe(PfcpIe.BAR_ID, new byte[] {(byte) id});
    }

    public static PfcpIe gateStatus(int bits) {
        return new PfcpIe(PfcpIe.GATE_STATUS, new byte[] {(byte) bits});
    }

    public static PfcpIe measurementMethod(int bits) {
        return new PfcpIe(PfcpIe.MEASUREMENT_METHOD, new byte[] {(byte) bits});
    }

    public static PfcpIe reportingTriggers(int triggers) {
        return u16Ie(PfcpIe.REPORTING_TRIGGERS, triggers);
    }

    /** Volume Threshold (TS 29.244 §8.2.26): unsigned 64-bit bytes. */
    public static PfcpIe volumeThreshold(long bytes) {
        return new PfcpIe(PfcpIe.VOLUME_THRESHOLD, u64(bytes));
    }

    /** Time Threshold (TS 29.244 §8.2.27): unsigned 32-bit seconds. */
    public static PfcpIe timeThreshold(int seconds) {
        return u32Ie(PfcpIe.TIME_THRESHOLD, seconds);
    }

    /** Query URR group (TS 29.244 §8.2.75): URR ID to report on demand. */
    public static PfcpIe queryUrr(int id) {
        return grouped(PfcpIe.QUERY_URR, List.of(urrId(id)));
    }

    public static PfcpIe outerHeaderRemoval(int description) {
        return new PfcpIe(PfcpIe.OUTER_HEADER_REMOVAL, new byte[] {(byte) description});
    }

    public static PfcpIe downlinkDataNotificationDelay(int delay) {
        return new PfcpIe(PfcpIe.DOWNLINK_DATA_NOTIFICATION_DELAY, new byte[] {(byte) delay});
    }

    public static PfcpIe downlinkDataReport(byte flags) {
        return new PfcpIe(PfcpIe.DOWNLINK_DATA_REPORT, new byte[] {flags});
    }

    public static PfcpIe ueIpAddress(InetAddress ip) {
        byte[] a = ip.getAddress();
        ByteBuffer b = ByteBuffer.allocate(1 + a.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) (a.length == 4 ? 0x02 : 0x01));
        b.put(a);
        return new PfcpIe(PfcpIe.UE_IP_ADDRESS, b.array());
    }

    public static PfcpIe outerHeaderCreation(PfcpFteid remote) {
        byte[] a = remote.address().getAddress();
        boolean v4 = a.length == 4;
        ByteBuffer b = ByteBuffer.allocate(2 + 4 + a.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) (v4 ? 0x15 : 0x2A)); // GTP-U/UDP/IPv4 or GTP-U/UDP/IPv6
        b.put((byte) 0x00);              // second flags octet (no N6/N19/SSM)
        b.putInt(remote.teid());
        b.put(a);
        return new PfcpIe(PfcpIe.OUTER_HEADER_CREATION, b.array());
    }

    // ---- grouped IE builders ----

    public static PfcpIe createPdr(PfcpPdr p) {
        List<PfcpIe> out = new ArrayList<>();
        out.add(pdrId(p.pdrId()));
        out.add(precedence(p.precedence()));
        out.add(grouped(PfcpIe.PDI, pdi(p)));
        if (p.farId() != null) {
            out.add(farId(p.farId()));
        }
        for (int q : p.qerIds()) {
            out.add(qerId(q));
        }
        for (int u : p.urrIds()) {
            out.add(urrId(u));
        }
        if (p.downlinkDataReport()) {
            out.add(downlinkDataReport((byte) 0x01));
        }
        return grouped(PfcpIe.CREATE_PDR, out);
    }

    public static PfcpIe createFar(PfcpFar f) {
        List<PfcpIe> out = new ArrayList<>();
        out.add(farId(f.farId()));
        out.add(applyAction(f.applyAction()));
        if (f.remote() != null) {
            out.add(grouped(PfcpIe.FORWARDING_PARAMETERS, List.of(
                    destinationInterface(PfcpIe.INTERFACE_CORE),
                    outerHeaderCreation(f.remote()))));
        }
        if (f.outerHeaderRemoval() != null) {
            out.add(outerHeaderRemoval(f.outerHeaderRemoval()));
        }
        if (f.barId() != null) {
            out.add(barId(f.barId()));
        }
        return grouped(PfcpIe.CREATE_FAR, out);
    }

    public static PfcpIe createQer(PfcpQer q) {
        return grouped(PfcpIe.CREATE_QER, List.of(qerId(q.qerId()), gateStatus(q.gateStatus())));
    }

    public static PfcpIe createUrr(PfcpUrr u) {
        List<PfcpIe> out = new ArrayList<>();
        out.add(urrId(u.urrId()));
        out.add(measurementMethod(u.measurementMethod()));
        out.add(reportingTriggers(u.reportingTriggers()));
        if (u.volumeThresholdBytes() > 0) {
            out.add(volumeThreshold(u.volumeThresholdBytes()));
        }
        if (u.timeThresholdSeconds() > 0) {
            out.add(timeThreshold(u.timeThresholdSeconds()));
        }
        return grouped(PfcpIe.CREATE_URR, out);
    }

    public static PfcpIe createBar(PfcpBar b) {
        return grouped(PfcpIe.CREATE_BAR, List.of(
                barId(b.barId()), downlinkDataNotificationDelay(b.downlinkDataNotificationDelay())));
    }

    /** Ordered IE list for a PFCP Session Establishment/Modification request. */
    public static List<PfcpIe> sessionIes(List<PfcpPdr> pdrs, List<PfcpFar> fars,
            List<PfcpQer> qers, List<PfcpUrr> urrs, List<PfcpBar> bars) {
        List<PfcpIe> out = new ArrayList<>();
        for (PfcpPdr p : pdrsOrEmpty(pdrs)) {
            out.add(createPdr(p));
        }
        for (PfcpFar f : farsOrEmpty(fars)) {
            out.add(createFar(f));
        }
        for (PfcpQer q : qersOrEmpty(qers)) {
            out.add(createQer(q));
        }
        for (PfcpUrr u : urrsOrEmpty(urrs)) {
            out.add(createUrr(u));
        }
        for (PfcpBar b : barsOrEmpty(bars)) {
            out.add(createBar(b));
        }
        return out;
    }

    /**
     * Maps the legacy {@link PfcpRule} shape onto real PFCP Create PDR/FAR/QER/URR IEs.
     * Uplink rules get an ACCESS PDI; downlink rules get a CORE PDI carrying the UE IPv4.
     */
    public static List<PfcpIe> fromRules(List<PfcpRule> rules, InetAddress ueIpv4) {
        List<PfcpIe> out = new ArrayList<>();
        List<PfcpRule> list = rules == null ? List.of() : rules;
        int i = 0;
        for (PfcpRule r : list) {
            int farId = i + 1;
            short pdrId = (short) (i + 1);
            int qerId = i + 1;
            int urrId = i + 1;
            InetAddress ue = (!r.uplink() && ueIpv4 != null) ? ueIpv4 : null;
            int src = r.uplink() ? PfcpIe.INTERFACE_ACCESS : PfcpIe.INTERFACE_CORE;
            out.add(createPdr(new PfcpPdr(pdrId, i, src, r.local(), ue, farId,
                    List.of(qerId), List.of(urrId), false)));
            out.add(createFar(new PfcpFar(farId, PfcpIe.APPLY_FORW, r.remote(), 0, null)));
            out.add(createQer(new PfcpQer(qerId)));
            out.add(createUrr(r.urr() != null ? r.urr() : new PfcpUrr(urrId)));
            i++;
        }
        return out;
    }

    // ---- parsers (used by tests and SBBs reading inbound IEs) ----

    public static List<PfcpIe> children(PfcpIe group) {
        return PfcpCodec.decodeIes(group.value());
    }

    public static PfcpIe find(List<PfcpIe> ies, int type) {
        for (PfcpIe ie : ies) {
            if (ie.type() == type) {
                return ie;
            }
        }
        return null;
    }

    public static PfcpNodeId parseNodeId(PfcpIe ie) {
        byte[] v = ie.value();
        int type = (v[0] >>> 4) & 0x0f;
        return switch (type) {
            case 0 -> PfcpNodeId.of(inet(v, 1, 4));
            case 1 -> PfcpNodeId.of(inet(v, 1, 16));
            default -> PfcpNodeId.of(new String(v, 1, v.length - 1, StandardCharsets.US_ASCII));
        };
    }

    public static PfcpFteid parseFteid(PfcpIe ie) {
        return parseFteid(ie.value());
    }

    public static PfcpFteid parseFteid(byte[] v) {
        boolean v6 = (v[0] & 0x40) != 0 && (v[0] & 0x80) == 0;
        int teid = i32(v, 1);
        return new PfcpFteid(teid, inet(v, 5, v6 ? 16 : 4), 0);
    }

    public static PfcpFteid parseOuterHeaderCreation(PfcpIe ie) {
        byte[] v = ie.value();
        int teid = i32(v, 2);
        return new PfcpFteid(teid, inet(v, 6, 4), 0);
    }

    public static PfcpPdr parsePdr(PfcpIe group) {
        List<PfcpIe> c = children(group);
        short pdrId = (short) u16(valueOf(c, PfcpIe.PDR_ID).value(), 0);
        int precedence = (int) u32(valueOf(c, PfcpIe.PRECEDENCE).value(), 0);
        List<PfcpIe> pdiC = children(valueOf(c, PfcpIe.PDI));
        int src = valueOf(pdiC, PfcpIe.SOURCE_INTERFACE).value()[0] & 0xff;
        PfcpFteid local = null;
        if (find(pdiC, PfcpIe.F_TEID) != null) {
            local = parseFteid(find(pdiC, PfcpIe.F_TEID));
        }
        InetAddress ue = null;
        if (find(pdiC, PfcpIe.UE_IP_ADDRESS) != null) {
            ue = inet(find(pdiC, PfcpIe.UE_IP_ADDRESS).value(), 1, 4);
        }
        Integer farId = find(c, PfcpIe.FAR_ID) != null
                ? (int) u32(valueOf(c, PfcpIe.FAR_ID).value(), 0)
                : null;
        return new PfcpPdr(pdrId, precedence, src, local, ue, farId,
                ids(c, PfcpIe.QER_ID, 4), ids(c, PfcpIe.URR_ID, 4),
                find(c, PfcpIe.DOWNLINK_DATA_REPORT) != null);
    }

    public static PfcpFar parseFar(PfcpIe group) {
        List<PfcpIe> c = children(group);
        int farId = (int) u32(valueOf(c, PfcpIe.FAR_ID).value(), 0);
        int apply = valueOf(c, PfcpIe.APPLY_ACTION).value()[0] & 0xff;
        PfcpFteid remote = null;
        PfcpIe fp = find(c, PfcpIe.FORWARDING_PARAMETERS);
        if (fp != null && find(children(fp), PfcpIe.OUTER_HEADER_CREATION) != null) {
            remote = parseOuterHeaderCreation(find(children(fp), PfcpIe.OUTER_HEADER_CREATION));
        }
        Integer ohr = find(c, PfcpIe.OUTER_HEADER_REMOVAL) != null
                ? valueOf(c, PfcpIe.OUTER_HEADER_REMOVAL).value()[0] & 0xff
                : null;
        Integer barId = find(c, PfcpIe.BAR_ID) != null
                ? valueOf(c, PfcpIe.BAR_ID).value()[0] & 0xff
                : null;
        return new PfcpFar(farId, apply, remote, ohr, barId);
    }

    public static PfcpQer parseQer(PfcpIe group) {
        List<PfcpIe> c = children(group);
        return new PfcpQer((int) u32(valueOf(c, PfcpIe.QER_ID).value(), 0),
                valueOf(c, PfcpIe.GATE_STATUS).value()[0] & 0xff);
    }

    public static PfcpUrr parseUrr(PfcpIe group) {
        List<PfcpIe> c = children(group);
        int urrId = (int) u32(valueOf(c, PfcpIe.URR_ID).value(), 0);
        int measurementMethod = valueOf(c, PfcpIe.MEASUREMENT_METHOD).value()[0] & 0xff;
        int reportingTriggers = u16(valueOf(c, PfcpIe.REPORTING_TRIGGERS).value(), 0);
        long volumeThresholdBytes = 0;
        PfcpIe volumeThreshold = find(c, PfcpIe.VOLUME_THRESHOLD);
        if (volumeThreshold != null && volumeThreshold.value().length >= 8) {
            volumeThresholdBytes = u64(volumeThreshold.value(), 0);
        }
        int timeThresholdSeconds = 0;
        PfcpIe timeThreshold = find(c, PfcpIe.TIME_THRESHOLD);
        if (timeThreshold != null && timeThreshold.value().length >= 4) {
            timeThresholdSeconds = (int) u32(timeThreshold.value(), 0);
        }
        return new PfcpUrr(urrId, measurementMethod, reportingTriggers,
                volumeThresholdBytes, timeThresholdSeconds);
    }

    public static PfcpBar parseBar(PfcpIe group) {
        List<PfcpIe> c = children(group);
        return new PfcpBar(valueOf(c, PfcpIe.BAR_ID).value()[0] & 0xff,
                valueOf(c, PfcpIe.DOWNLINK_DATA_NOTIFICATION_DELAY).value()[0] & 0xff);
    }

    // ---- internal helpers ----

    private static PfcpIe grouped(int type, List<PfcpIe> children) {
        return new PfcpIe(type, PfcpCodec.encodeIes(children));
    }

    private static List<PfcpIe> pdi(PfcpPdr p) {
        List<PfcpIe> out = new ArrayList<>();
        out.add(sourceInterface(p.sourceInterface()));
        if (p.local() != null) {
            out.add(fTeid(p.local()));
        }
        if (p.ueIpv4() != null) {
            out.add(ueIpAddress(p.ueIpv4()));
        }
        return out;
    }

    private static PfcpIe u16Ie(int type, int v) {
        return new PfcpIe(type, new byte[] {(byte) (v >>> 8), (byte) v});
    }

    private static PfcpIe u32Ie(int type, int v) {
        return new PfcpIe(type, u32(v));
    }

    private static byte[] u32(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static byte[] u64(long v) {
        return new byte[] {(byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static long u64(byte[] v, int off) {
        return ((long) (v[off] & 0xff) << 56) | ((long) (v[off + 1] & 0xff) << 48)
                | ((long) (v[off + 2] & 0xff) << 40) | ((long) (v[off + 3] & 0xff) << 32)
                | ((long) (v[off + 4] & 0xff) << 24) | ((long) (v[off + 5] & 0xff) << 16)
                | ((long) (v[off + 6] & 0xff) << 8) | (v[off + 7] & 0xff);
    }

    private static PfcpIe valueOf(List<PfcpIe> list, int type) {
        PfcpIe ie = find(list, type);
        if (ie == null) {
            throw new IllegalArgumentException("missing PFCP IE type " + type);
        }
        return ie;
    }

    private static List<Integer> ids(List<PfcpIe> c, int type, int width) {
        List<Integer> out = new ArrayList<>();
        for (PfcpIe ie : c) {
            if (ie.type() == type) {
                out.add((int) (width == 4 ? u32(ie.value(), 0) : u16(ie.value(), 0)));
            }
        }
        return out;
    }

    private static int u16(byte[] v, int off) {
        return ((v[off] & 0xff) << 8) | (v[off + 1] & 0xff);
    }

    private static long u32(byte[] v, int off) {
        return ((long) (v[off] & 0xff) << 24) | ((v[off + 1] & 0xff) << 16)
                | ((v[off + 2] & 0xff) << 8) | (v[off + 3] & 0xff);
    }

    private static int i32(byte[] v, int off) {
        return (int) u32(v, off);
    }

    private static InetAddress inet(byte[] v, int off, int len) {
        try {
            byte[] a = java.util.Arrays.copyOfRange(v, off, off + len);
            return InetAddress.getByAddress(a);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bad PFCP address", e);
        }
    }

    private static List<PfcpPdr> pdrsOrEmpty(List<PfcpPdr> l) {
        return l == null ? List.of() : l;
    }

    private static List<PfcpFar> farsOrEmpty(List<PfcpFar> l) {
        return l == null ? List.of() : l;
    }

    private static List<PfcpQer> qersOrEmpty(List<PfcpQer> l) {
        return l == null ? List.of() : l;
    }

    private static List<PfcpUrr> urrsOrEmpty(List<PfcpUrr> l) {
        return l == null ? List.of() : l;
    }

    private static List<PfcpBar> barsOrEmpty(List<PfcpBar> l) {
        return l == null ? List.of() : l;
    }
}