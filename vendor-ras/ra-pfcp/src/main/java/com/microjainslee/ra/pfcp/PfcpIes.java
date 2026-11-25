package com.microjainslee.ra.pfcp;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * TS 29.244 IE builders/parsers. The codec (TLV framing, §8.1.1) lives in
 * {@link PfcpCodec}; SBBs use these helpers to read/write the structured IEs
 * (Node ID, F-SEID, Recovery, Create/Update/Remove PDR/FAR/QER/URR/BAR).
 *
 * <p>Flag octets follow the spec numbering: "bit 1" is the least significant bit
 * of the octet (TS 29.244 §8.1.1), so e.g. F-TEID V4 = 0x01, F-SEID V4 = 0x02.</p>
 */
public final class PfcpIes {

    /** §8.2.3 F-TEID flags. */
    static final int FTEID_V4 = 0x01, FTEID_V6 = 0x02;
    /** §8.2.37 F-SEID flags. */
    static final int FSEID_V6 = 0x01, FSEID_V4 = 0x02;
    /** §8.2.62 UE IP Address flags. */
    static final int UEIP_V6 = 0x01, UEIP_V4 = 0x02, UEIP_SD = 0x04;
    /** §8.2.38 Node ID types (low nibble of octet 5). */
    static final int NODE_IPV4 = 0, NODE_IPV6 = 1, NODE_FQDN = 2;
    /** §8.2.56 Outer Header Creation description, octet 5. */
    static final int OHC_GTPU_UDP_IPV4 = 0x01, OHC_GTPU_UDP_IPV6 = 0x02;
    /** §8.2.64 Outer Header Removal: GTP-U/UDP/IPv4. */
    public static final int OHR_GTPU_UDP_IPV4 = 0;

    private PfcpIes() {}

    // ---- scalar IE builders ----

    /** §8.2.38 Node ID: spare + type nibble, then IPv4 / IPv6 / FQDN (RFC 1035 labels). */
    public static PfcpIe nodeId(PfcpNodeId id) {
        if (id.address() != null) {
            byte[] a = id.address().getAddress();
            byte[] v = new byte[1 + a.length];
            v[0] = (byte) (a.length == 16 ? NODE_IPV6 : NODE_IPV4);
            System.arraycopy(a, 0, v, 1, a.length);
            return new PfcpIe(PfcpIe.NODE_ID, v);
        }
        byte[] f = labels(id.fqdn());
        byte[] v = new byte[1 + f.length];
        v[0] = NODE_FQDN;
        System.arraycopy(f, 0, v, 1, f.length);
        return new PfcpIe(PfcpIe.NODE_ID, v);
    }

    /** §8.2.65 Recovery Time Stamp: 32-bit NTP seconds (opaque here). */
    public static PfcpIe recoveryTimeStamp(int seconds) {
        return new PfcpIe(PfcpIe.RECOVERY_TIME_STAMP, u32(seconds));
    }

    /** §8.2.37 F-SEID: flags (V6 bit 1, V4 bit 2), SEID, address. */
    public static PfcpIe fSeid(long seid, InetAddress address) {
        byte[] a = address == null ? new byte[0] : address.getAddress();
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + a.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) (a.length == 4 ? FSEID_V4 : a.length == 16 ? FSEID_V6 : 0));
        b.putLong(seid);
        b.put(a);
        return new PfcpIe(PfcpIe.F_SEID, b.array());
    }

    /** §8.2.3 F-TEID (CH = 0): flags (V4 bit 1, V6 bit 2), TEID, address. */
    public static PfcpIe fTeid(PfcpFteid f) {
        byte[] a = f.address().getAddress();
        ByteBuffer b = ByteBuffer.allocate(1 + 4 + a.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) (a.length == 4 ? FTEID_V4 : FTEID_V6));
        b.putInt(f.teid());
        b.put(a);
        return new PfcpIe(PfcpIe.F_TEID, b.array());
    }

    /** §8.2.1 Cause. */
    public static PfcpIe cause(int code) {
        return new PfcpIe(PfcpIe.CAUSE, new byte[] {(byte) code});
    }

    /** §8.2.36 PDR ID: unsigned 16 bits. */
    public static PfcpIe pdrId(int id) {
        return u16Ie(PfcpIe.PDR_ID, id);
    }

    /** §8.2.11 Precedence: unsigned 32 bits, lower value = higher precedence. */
    public static PfcpIe precedence(int precedence) {
        return u32Ie(PfcpIe.PRECEDENCE, precedence);
    }

    /** §8.2.2 Source Interface (low nibble). */
    public static PfcpIe sourceInterface(int iface) {
        return new PfcpIe(PfcpIe.SOURCE_INTERFACE, new byte[] {(byte) (iface & 0x0f)});
    }

    /** §8.2.24 Destination Interface (low nibble). */
    public static PfcpIe destinationInterface(int iface) {
        return new PfcpIe(PfcpIe.DESTINATION_INTERFACE, new byte[] {(byte) (iface & 0x0f)});
    }

    /** §8.2.26 Apply Action: bit 1 DROP, bit 2 FORW, bit 3 BUFF, bit 4 NOCP, bit 5 DUPL. */
    public static PfcpIe applyAction(int bits) {
        return new PfcpIe(PfcpIe.APPLY_ACTION, new byte[] {(byte) bits});
    }

    /** §8.2.74 FAR ID: unsigned 32 bits. */
    public static PfcpIe farId(int id) {
        return u32Ie(PfcpIe.FAR_ID, id);
    }

    /** §8.2.75 QER ID: unsigned 32 bits. */
    public static PfcpIe qerId(int id) {
        return u32Ie(PfcpIe.QER_ID, id);
    }

    /** §8.2.54 URR ID: unsigned 32 bits. */
    public static PfcpIe urrId(int id) {
        return u32Ie(PfcpIe.URR_ID, id);
    }

    /** §8.2.88 BAR ID: one octet. */
    public static PfcpIe barId(int id) {
        return new PfcpIe(PfcpIe.BAR_ID, new byte[] {(byte) id});
    }

    /**
     * §8.2.7 Gate Status: bits 1-2 DL gate, bits 3-4 UL gate, 0 = OPEN, 1 = CLOSED.
     * {@code openBits} is the {@link PfcpQer} model: bit 0 = UL open, bit 1 = DL open.
     */
    public static PfcpIe gateStatus(int openBits) {
        int dl = (openBits & 2) != 0 ? 0 : 1;
        int ul = (openBits & 1) != 0 ? 0 : 1;
        return new PfcpIe(PfcpIe.GATE_STATUS, new byte[] {(byte) ((ul << 2) | dl)});
    }

    /** §8.2.40 Measurement Method: bit 1 DURAT, bit 2 VOLUM, bit 3 EVENT. */
    public static PfcpIe measurementMethod(int bits) {
        return new PfcpIe(PfcpIe.MEASUREMENT_METHOD, new byte[] {(byte) bits});
    }

    /** §8.2.19 Reporting Triggers: octet 5 bit 1 PERIO, bit 2 VOLTH, bit 3 TIMTH, bit 4 QUHTI …; octet 6 next. */
    public static PfcpIe reportingTriggers(int triggers) {
        return new PfcpIe(PfcpIe.REPORTING_TRIGGERS, flags(triggers, 2));
    }

    /** §8.2.13 Volume Threshold: flags (TOVOL) + total volume, unsigned 64 bits. */
    public static PfcpIe volumeThreshold(long bytes) {
        byte[] v = new byte[9];
        v[0] = (byte) PfcpIe.VM_TOVOL;
        System.arraycopy(u64(bytes), 0, v, 1, 8);
        return new PfcpIe(PfcpIe.VOLUME_THRESHOLD, v);
    }

    /** §8.2.14 Time Threshold: unsigned 32-bit seconds. */
    public static PfcpIe timeThreshold(int seconds) {
        return u32Ie(PfcpIe.TIME_THRESHOLD, seconds);
    }

    /** §7.5.4.10 Query URR group: URR ID to report on demand. */
    public static PfcpIe queryUrr(int id) {
        return grouped(PfcpIe.QUERY_URR, List.of(urrId(id)));
    }

    /** §8.2.64 Outer Header Removal description. */
    public static PfcpIe outerHeaderRemoval(int description) {
        return new PfcpIe(PfcpIe.OUTER_HEADER_REMOVAL, new byte[] {(byte) description});
    }

    /** §8.2.28 Downlink Data Notification Delay (units of 50 ms). */
    public static PfcpIe downlinkDataNotificationDelay(int delay) {
        return new PfcpIe(PfcpIe.DOWNLINK_DATA_NOTIFICATION_DELAY, new byte[] {(byte) delay});
    }

    public static PfcpIe downlinkDataReport(byte flags) {
        return new PfcpIe(PfcpIe.DOWNLINK_DATA_REPORT, new byte[] {flags});
    }

    /** §8.2.62 UE IP Address as a source address (uplink match). */
    public static PfcpIe ueIpAddress(InetAddress ip) {
        return ueIpAddress(ip, false);
    }

    /** §8.2.62 UE IP Address; {@code destination} sets S/D (downlink match on the UE address). */
    public static PfcpIe ueIpAddress(InetAddress ip, boolean destination) {
        byte[] a = ip.getAddress();
        ByteBuffer b = ByteBuffer.allocate(1 + a.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) ((a.length == 4 ? UEIP_V4 : UEIP_V6) | (destination ? UEIP_SD : 0)));
        b.put(a);
        return new PfcpIe(PfcpIe.UE_IP_ADDRESS, b.array());
    }

    /** §8.2.56 Outer Header Creation: description (2 octets), TEID, IPv4 / IPv6 address. */
    public static PfcpIe outerHeaderCreation(PfcpFteid remote) {
        byte[] a = remote.address().getAddress();
        boolean v4 = a.length == 4;
        ByteBuffer b = ByteBuffer.allocate(2 + 4 + a.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) (v4 ? OHC_GTPU_UDP_IPV4 : OHC_GTPU_UDP_IPV6));
        b.put((byte) 0x00);
        b.putInt(remote.teid());
        b.put(a);
        return new PfcpIe(PfcpIe.OUTER_HEADER_CREATION, b.array());
    }

    /** §8.2.21 Report Type: bit 1 DLDR, bit 2 USAR, bit 3 ERIR, bit 4 UPIR. */
    public static PfcpIe reportType(int bits) {
        return new PfcpIe(PfcpIe.REPORT_TYPE, new byte[] {(byte) bits});
    }

    /** §8.2.41 Usage Report Trigger: octet 5 bit 1 PERIO, bit 2 VOLTH, bit 3 TIMTH, bit 4 QUHTI …. */
    public static PfcpIe usageReportTrigger(int triggers) {
        return new PfcpIe(PfcpIe.USAGE_REPORT_TRIGGER, flags(triggers, 3));
    }

    /** §8.2.44 Volume Measurement: flags + UL and DL volume (unsigned 64 bits each). */
    public static PfcpIe volumeMeasurement(long uplink, long downlink) {
        byte[] v = new byte[17];
        v[0] = (byte) (PfcpIe.VM_ULVOL | PfcpIe.VM_DLVOL);
        System.arraycopy(u64(uplink), 0, v, 1, 8);
        System.arraycopy(u64(downlink), 0, v, 9, 8);
        return new PfcpIe(PfcpIe.VOLUME_MEASUREMENT, v);
    }

    /**
     * Usage Report group (§7.5.8.3 SRR / §7.5.5.2 SMR / §7.5.7.2 SDR): URR ID, UR-SEQN,
     * Usage Report Trigger (all M), Volume and Duration Measurement.
     */
    public static PfcpIe usageReport(int groupType, PfcpUsageReport r) {
        return grouped(groupType, List.of(
                urrId(r.urrId()),
                u32Ie(PfcpIe.UR_SEQN, 0),
                usageReportTrigger(r.usageReportTrigger()),
                volumeMeasurement(r.uplinkVolume(), r.downlinkVolume()),
                u32Ie(PfcpIe.DURATION_MEASUREMENT, r.durationSeconds())));
    }

    // ---- grouped IE builders ----

    /** §7.5.2.2 Create PDR: PDR ID, Precedence, PDI, Outer Header Removal, FAR / URR / QER IDs. */
    public static PfcpIe createPdr(PfcpPdr p) {
        List<PfcpIe> out = new ArrayList<>();
        out.add(pdrId(p.pdrId()));
        out.add(precedence(p.precedence()));
        out.add(grouped(PfcpIe.PDI, pdi(p)));
        if (p.outerHeaderRemoval() != null) {
            out.add(outerHeaderRemoval(p.outerHeaderRemoval()));
        }
        if (p.farId() != null) {
            out.add(farId(p.farId()));
        }
        for (int u : p.urrIds()) {
            out.add(urrId(u));
        }
        for (int q : p.qerIds()) {
            out.add(qerId(q));
        }
        if (p.downlinkDataReport()) {
            out.add(downlinkDataReport((byte) 0x01));
        }
        return grouped(PfcpIe.CREATE_PDR, out);
    }

    /**
     * §7.5.2.3 Create FAR: FAR ID, Apply Action, Forwarding Parameters {Destination
     * Interface, Outer Header Creation}, BAR ID. Outer Header Removal is a PDR IE
     * (§7.5.2.2) and is not encoded here even when the model carries it.
     */
    public static PfcpIe createFar(PfcpFar f) {
        return createFar(f, PfcpIe.INTERFACE_CORE);
    }

    public static PfcpIe createFar(PfcpFar f, int destinationInterface) {
        List<PfcpIe> out = new ArrayList<>();
        out.add(farId(f.farId()));
        out.add(applyAction(f.applyAction()));
        if (f.remote() != null) {
            out.add(grouped(PfcpIe.FORWARDING_PARAMETERS, List.of(
                    destinationInterface(destinationInterface),
                    outerHeaderCreation(f.remote()))));
        }
        if (f.barId() != null) {
            out.add(barId(f.barId()));
        }
        return grouped(PfcpIe.CREATE_FAR, out);
    }

    /**
     * §7.5.4.3 Update FAR: FAR ID, Apply Action, Update Forwarding Parameters
     * {Destination Interface, Outer Header Creation} when {@code remote} is given
     * (e.g. Modify Bearer moves the downlink to a new eNB F-TEID).
     */
    public static PfcpIe updateFar(int farId, int applyAction, PfcpFteid remote, int destinationInterface) {
        List<PfcpIe> out = new ArrayList<>();
        out.add(farId(farId));
        out.add(applyAction(applyAction));
        if (remote != null) {
            out.add(grouped(PfcpIe.UPDATE_FORWARDING_PARAMETERS, List.of(
                    destinationInterface(destinationInterface),
                    outerHeaderCreation(remote))));
        }
        return grouped(PfcpIe.UPDATE_FAR, out);
    }

    /** §7.5.4.6 Remove PDR. */
    public static PfcpIe removePdr(int pdrId) {
        return grouped(PfcpIe.REMOVE_PDR, List.of(pdrId(pdrId)));
    }

    /** §7.5.4.7 Remove FAR. */
    public static PfcpIe removeFar(int farId) {
        return grouped(PfcpIe.REMOVE_FAR, List.of(farId(farId)));
    }

    /** §7.5.2.5 Create QER: QER ID, Gate Status. */
    public static PfcpIe createQer(PfcpQer q) {
        return grouped(PfcpIe.CREATE_QER, List.of(qerId(q.qerId()), gateStatus(q.gateStatus())));
    }

    /** §7.5.2.4 Create URR: URR ID, Measurement Method, Reporting Triggers, thresholds. */
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

    /** §7.5.2.6 Create BAR. */
    public static PfcpIe createBar(PfcpBar b) {
        return grouped(PfcpIe.CREATE_BAR, List.of(
                barId(b.barId()), downlinkDataNotificationDelay(b.downlinkDataNotificationDelay())));
    }

    /** Ordered IE list for a PFCP Session Establishment/Modification request. */
    public static List<PfcpIe> sessionIes(List<PfcpPdr> pdrs, List<PfcpFar> fars,
            List<PfcpQer> qers, List<PfcpUrr> urrs, List<PfcpBar> bars) {
        List<PfcpIe> out = new ArrayList<>();
        for (PfcpPdr p : orEmpty(pdrs)) {
            out.add(createPdr(p));
        }
        for (PfcpFar f : orEmpty(fars)) {
            out.add(createFar(f));
        }
        for (PfcpQer q : orEmpty(qers)) {
            out.add(createQer(q));
        }
        for (PfcpUrr u : orEmpty(urrs)) {
            out.add(createUrr(u));
        }
        for (PfcpBar b : orEmpty(bars)) {
            out.add(createBar(b));
        }
        return out;
    }

    /**
     * Maps the {@link PfcpRule} tunnel model onto Create PDR/FAR/QER/URR IEs; rule
     * {@code i} gets PDR ID = FAR ID = {@code i + 1}. See {@link #createRule}.
     */
    public static List<PfcpIe> fromRules(List<PfcpRule> rules, InetAddress ueIpv4) {
        List<PfcpIe> out = new ArrayList<>();
        Set<Integer> qers = new LinkedHashSet<>();
        int i = 0;
        for (PfcpRule r : rules == null ? List.<PfcpRule>of() : rules) {
            out.addAll(createRule(r, i + 1, ueIpv4, qers));
            i++;
        }
        return out;
    }

    /**
     * One tunnel rule as Create PDR + Create FAR (+ QER / URR) with PDR ID = FAR ID =
     * {@code id}. The PDR matches GTP-U on {@code local} (Source Interface ACCESS for
     * uplink, CORE for downlink) and removes the outer header; the FAR forwards with
     * Outer Header Creation toward {@code remote} on the opposite interface. Both PDRs
     * also match the UE address (§5.2.1): as source on uplink, as destination (S/D = 1)
     * on downlink.
     */
    public static List<PfcpIe> createRule(PfcpRule r, int id, InetAddress ueIpv4) {
        return createRule(r, id, ueIpv4, new LinkedHashSet<>());
    }

    private static List<PfcpIe> createRule(PfcpRule r, int id, InetAddress ueIpv4, Set<Integer> qersSeen) {
        List<PfcpIe> out = new ArrayList<>();
        int src = r.uplink() ? PfcpIe.INTERFACE_ACCESS : PfcpIe.INTERFACE_CORE;
        int dst = r.uplink() ? PfcpIe.INTERFACE_CORE : PfcpIe.INTERFACE_ACCESS;
        Integer qer = numericSuffix(r.qerId());
        List<Integer> urrIds = r.urr() == null ? List.of() : List.of(r.urr().urrId());
        out.add(createPdr(new PfcpPdr((short) id, 255 - Math.min(id, 254), src, r.local(), ueIpv4, id,
                qer == null ? List.of() : List.of(qer), urrIds, false, OHR_GTPU_UDP_IPV4)));
        out.add(createFar(new PfcpFar(id, PfcpIe.APPLY_FORW, r.remote()), dst));
        if (qer != null && qersSeen.add(qer)) {
            out.add(createQer(new PfcpQer(qer)));
        }
        if (r.urr() != null) {
            out.add(createUrr(r.urr()));
        }
        return out;
    }

    /**
     * Inverse of {@link #fromRules}: rebuilds the tunnel rules from Create PDR / Create FAR
     * / Create URR IEs of a Session Establishment or Modification Request. A PDR becomes a
     * rule when it has a local F-TEID and its FAR forwards with Outer Header Creation;
     * {@code pdrId} / {@code qerId} come back as {@code "pdr-<PDR ID>"} / {@code "qer-<QER ID>"}.
     */
    public static PfcpRules toRules(long seid, List<PfcpIe> ies) {
        PfcpSessionState st = PfcpSessionState.empty().apply(ies);
        return new PfcpRules(st.tunnels(seid), st.ueIpv4());
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
        int type = v[0] & 0x0f;
        return switch (type) {
            case NODE_IPV4 -> PfcpNodeId.of(inet(v, 1, 4));
            case NODE_IPV6 -> PfcpNodeId.of(inet(v, 1, 16));
            default -> PfcpNodeId.of(fromLabels(v, 1));
        };
    }

    /** §8.2.37 F-SEID → SEID + IPv4 (or IPv6) address. */
    public static PfcpFSeid parseFSeid(PfcpIe ie) {
        byte[] v = ie.value();
        long seid = u64(v, 1);
        InetAddress a = null;
        if ((v[0] & FSEID_V4) != 0 && v.length >= 13) {
            a = inet(v, 9, 4);
        } else if ((v[0] & FSEID_V6) != 0 && v.length >= 25) {
            a = inet(v, 9, 16);
        }
        return new PfcpFSeid(seid, a);
    }

    public static PfcpFteid parseFteid(PfcpIe ie) {
        return parseFteid(ie.value());
    }

    public static PfcpFteid parseFteid(byte[] v) {
        boolean v6 = (v[0] & FTEID_V4) == 0 && (v[0] & FTEID_V6) != 0;
        int teid = i32(v, 1);
        return new PfcpFteid(teid, inet(v, 5, v6 ? 16 : 4), 0);
    }

    public static PfcpFteid parseOuterHeaderCreation(PfcpIe ie) {
        byte[] v = ie.value();
        boolean v6 = (v[0] & OHC_GTPU_UDP_IPV6) != 0;
        int teid = i32(v, 2);
        return new PfcpFteid(teid, inet(v, 6, v6 ? 16 : 4), 0);
    }

    public static PfcpPdr parsePdr(PfcpIe group) {
        List<PfcpIe> c = children(group);
        short pdrId = (short) u16(valueOf(c, PfcpIe.PDR_ID).value(), 0);
        int precedence = (int) u32(valueOf(c, PfcpIe.PRECEDENCE).value(), 0);
        List<PfcpIe> pdiC = children(valueOf(c, PfcpIe.PDI));
        int src = valueOf(pdiC, PfcpIe.SOURCE_INTERFACE).value()[0] & 0x0f;
        PfcpFteid local = null;
        if (find(pdiC, PfcpIe.F_TEID) != null) {
            local = parseFteid(find(pdiC, PfcpIe.F_TEID));
        }
        InetAddress ue = null;
        PfcpIe ueIp = find(pdiC, PfcpIe.UE_IP_ADDRESS);
        if (ueIp != null && (ueIp.value()[0] & UEIP_V4) != 0) {
            ue = inet(ueIp.value(), 1, 4);
        }
        Integer farId = find(c, PfcpIe.FAR_ID) != null
                ? (int) u32(valueOf(c, PfcpIe.FAR_ID).value(), 0)
                : null;
        Integer ohr = find(c, PfcpIe.OUTER_HEADER_REMOVAL) != null
                ? valueOf(c, PfcpIe.OUTER_HEADER_REMOVAL).value()[0] & 0xff
                : null;
        return new PfcpPdr(pdrId, precedence, src, local, ue, farId,
                ids(c, PfcpIe.QER_ID), ids(c, PfcpIe.URR_ID),
                find(c, PfcpIe.DOWNLINK_DATA_REPORT) != null, ohr);
    }

    public static PfcpFar parseFar(PfcpIe group) {
        List<PfcpIe> c = children(group);
        int farId = (int) u32(valueOf(c, PfcpIe.FAR_ID).value(), 0);
        int apply = valueOf(c, PfcpIe.APPLY_ACTION).value()[0] & 0xff;
        PfcpFteid remote = null;
        PfcpIe fp = find(c, PfcpIe.FORWARDING_PARAMETERS);
        if (fp == null) {
            fp = find(c, PfcpIe.UPDATE_FORWARDING_PARAMETERS);
        }
        if (fp != null && find(children(fp), PfcpIe.OUTER_HEADER_CREATION) != null) {
            remote = parseOuterHeaderCreation(find(children(fp), PfcpIe.OUTER_HEADER_CREATION));
        }
        Integer barId = find(c, PfcpIe.BAR_ID) != null
                ? valueOf(c, PfcpIe.BAR_ID).value()[0] & 0xff
                : null;
        return new PfcpFar(farId, apply, remote, null, barId);
    }

    /** Update FAR (§7.5.4.3) parsed into the FAR model; {@code remote} null = no new OHC. */
    public static PfcpFar parseUpdateFar(PfcpIe group) {
        return parseFar(group);
    }

    /** PDR ID of a Remove PDR (§7.5.4.6) or FAR ID of a Remove FAR (§7.5.4.7). */
    public static int parseRemovedId(PfcpIe group) {
        List<PfcpIe> c = children(group);
        PfcpIe id = group.type() == PfcpIe.REMOVE_PDR ? valueOf(c, PfcpIe.PDR_ID) : valueOf(c, PfcpIe.FAR_ID);
        return id.value().length == 2 ? u16(id.value(), 0) : (int) u32(id.value(), 0);
    }

    public static PfcpQer parseQer(PfcpIe group) {
        List<PfcpIe> c = children(group);
        int gate = valueOf(c, PfcpIe.GATE_STATUS).value()[0] & 0x0f;
        int open = ((gate >> 2) & 0x03) == 0 ? 1 : 0;
        open |= (gate & 0x03) == 0 ? 2 : 0;
        return new PfcpQer((int) u32(valueOf(c, PfcpIe.QER_ID).value(), 0), open);
    }

    public static PfcpUrr parseUrr(PfcpIe group) {
        List<PfcpIe> c = children(group);
        int urrId = (int) u32(valueOf(c, PfcpIe.URR_ID).value(), 0);
        int measurementMethod = valueOf(c, PfcpIe.MEASUREMENT_METHOD).value()[0] & 0xff;
        int reportingTriggers = flags(valueOf(c, PfcpIe.REPORTING_TRIGGERS).value());
        long volumeThresholdBytes = 0;
        PfcpIe volumeThreshold = find(c, PfcpIe.VOLUME_THRESHOLD);
        if (volumeThreshold != null && volumeThreshold.value().length >= 9
                && (volumeThreshold.value()[0] & PfcpIe.VM_TOVOL) != 0) {
            volumeThresholdBytes = u64(volumeThreshold.value(), 1);
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

    /** Multi-octet flag field where octet 5 holds bits 1-8, octet 6 bits 9-16, …. */
    public static int flags(byte[] v) {
        int out = 0;
        for (int i = 0; i < Math.min(v.length, 4); i++) {
            out |= (v[i] & 0xff) << (8 * i);
        }
        return out;
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
            out.add(ueIpAddress(p.ueIpv4(), p.sourceInterface() != PfcpIe.INTERFACE_ACCESS));
        }
        return out;
    }

    private static Integer numericSuffix(String id) {
        if (id == null) {
            return null;
        }
        int i = id.length();
        while (i > 0 && Character.isDigit(id.charAt(i - 1))) {
            i--;
        }
        if (i == id.length()) {
            return id.isBlank() ? null : 1;
        }
        return Integer.valueOf(id.substring(i));
    }

    private static byte[] flags(int bits, int octets) {
        byte[] v = new byte[octets];
        for (int i = 0; i < octets; i++) {
            v[i] = (byte) (bits >>> (8 * i));
        }
        return v;
    }

    /** RFC 1035 §3.1 name labels without the terminating zero (TS 29.244 §8.2.38). */
    private static byte[] labels(String fqdn) {
        ByteBuffer b = ByteBuffer.allocate(fqdn.length() + 2);
        for (String label : fqdn.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.US_ASCII);
            b.put((byte) l.length);
            b.put(l);
        }
        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static String fromLabels(byte[] v, int off) {
        StringBuilder sb = new StringBuilder();
        int i = off;
        while (i < v.length) {
            int len = v[i] & 0xff;
            if (len == 0 || i + 1 + len > v.length) {
                break;
            }
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(new String(v, i + 1, len, StandardCharsets.US_ASCII));
            i += 1 + len;
        }
        return sb.toString();
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
        long x = 0;
        for (int i = 0; i < 8; i++) {
            x = (x << 8) | (v[off + i] & 0xffL);
        }
        return x;
    }

    private static PfcpIe valueOf(List<PfcpIe> list, int type) {
        PfcpIe ie = find(list, type);
        if (ie == null) {
            throw new IllegalArgumentException("missing PFCP IE type " + type);
        }
        return ie;
    }

    private static List<Integer> ids(List<PfcpIe> c, int type) {
        List<Integer> out = new ArrayList<>();
        for (PfcpIe ie : c) {
            if (ie.type() == type) {
                out.add((int) u32(ie.value(), 0));
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

    static InetAddress inet(byte[] v, int off, int len) {
        try {
            return InetAddress.getByAddress(java.util.Arrays.copyOfRange(v, off, off + len));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bad PFCP address", e);
        }
    }

    /** IPv4 of a node id, or null (FQDN / IPv6 node ids carry no IPv4). */
    static InetAddress ipv4Of(PfcpNodeId id) {
        return id != null && id.address() instanceof Inet4Address v4 ? v4 : null;
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }
}
