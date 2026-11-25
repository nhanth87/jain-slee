package com.microjainslee.ra.pfcp;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of one PFCP session's rules at the UP function (TS 29.244 §5.2): the PDRs,
 * FARs and URRs installed so far. {@link #apply} folds a Session Establishment or Modification
 * Request's Create / Update / Remove IEs (§7.5.2, §7.5.4) into a new state; {@link #tunnels}
 * projects it onto the {@link PfcpRule} tunnel model (a PDR with a local F-TEID whose FAR
 * forwards with Outer Header Creation). A FAR that buffers or drops yields no tunnel until an
 * Update FAR sets FORW again (e.g. Release Access Bearers, then Modify Bearer).
 */
public final class PfcpSessionState {

    private static final PfcpSessionState EMPTY =
            new PfcpSessionState(Map.of(), Map.of(), Map.of(), null);

    private final Map<Integer, PfcpPdr> pdrs;
    private final Map<Integer, PfcpFar> fars;
    private final Map<Integer, PfcpUrr> urrs;
    private final InetAddress ueIpv4;

    private PfcpSessionState(Map<Integer, PfcpPdr> pdrs, Map<Integer, PfcpFar> fars,
                             Map<Integer, PfcpUrr> urrs, InetAddress ueIpv4) {
        this.pdrs = pdrs;
        this.fars = fars;
        this.urrs = urrs;
        this.ueIpv4 = ueIpv4;
    }

    public static PfcpSessionState empty() {
        return EMPTY;
    }

    /** Thrown when an Update / Remove names a rule the session does not have (Cause 73, §8.2.1). */
    public static final class RuleNotFound extends IllegalArgumentException {
        public RuleNotFound(String message) {
            super(message);
        }
    }

    /** Apply the rule IEs of a Session Establishment / Modification Request. */
    public PfcpSessionState apply(List<PfcpIe> ies) {
        Map<Integer, PfcpPdr> p = new LinkedHashMap<>(pdrs);
        Map<Integer, PfcpFar> f = new LinkedHashMap<>(fars);
        Map<Integer, PfcpUrr> u = new LinkedHashMap<>(urrs);
        InetAddress ue = ueIpv4;
        for (PfcpIe ie : ies) {
            switch (ie.type()) {
                case PfcpIe.CREATE_PDR -> {
                    PfcpPdr pdr = PfcpIes.parsePdr(ie);
                    p.put(pdr.pdrId() & 0xffff, pdr);
                    if (ue == null && pdr.ueIpv4() != null) {
                        ue = pdr.ueIpv4();
                    }
                }
                case PfcpIe.CREATE_FAR -> {
                    PfcpFar far = PfcpIes.parseFar(ie);
                    f.put(far.farId(), far);
                }
                case PfcpIe.CREATE_URR -> {
                    PfcpUrr urr = PfcpIes.parseUrr(ie);
                    u.put(urr.urrId(), urr);
                }
                case PfcpIe.UPDATE_FAR -> {
                    PfcpFar upd = PfcpIes.parseUpdateFar(ie);
                    PfcpFar old = f.get(upd.farId());
                    if (old == null) {
                        throw new RuleNotFound("Update FAR " + upd.farId() + " not installed");
                    }
                    // §7.5.4.3: only the IEs present change; the forwarding target stays unless replaced.
                    f.put(upd.farId(), new PfcpFar(upd.farId(), upd.applyAction(),
                            upd.remote() != null ? upd.remote() : old.remote(), null,
                            upd.barId() != null ? upd.barId() : old.barId()));
                }
                case PfcpIe.REMOVE_PDR -> {
                    int id = PfcpIes.parseRemovedId(ie);
                    if (p.remove(id) == null) {
                        throw new RuleNotFound("Remove PDR " + id + " not installed");
                    }
                }
                case PfcpIe.REMOVE_FAR -> {
                    int id = PfcpIes.parseRemovedId(ie);
                    if (f.remove(id) == null) {
                        throw new RuleNotFound("Remove FAR " + id + " not installed");
                    }
                }
                default -> { }
            }
        }
        return new PfcpSessionState(Map.copyOf(p), Map.copyOf(f), Map.copyOf(u), ue);
    }

    /**
     * Forwarding tunnels. Two shapes (TS 23.214 §5.2):
     * <ul>
     *   <li>relay (SGW-U): PDR with a local F-TEID → FAR FORW with Outer Header Creation;</li>
     *   <li>anchor (PGW-U): uplink PDR with a local F-TEID → FAR FORW to CORE without a tunnel,
     *       paired with the downlink PDR on the UE address (no F-TEID) whose FAR adds the
     *       Outer Header toward the SGW — one GTP tunnel {local TEID, peer F-TEID, UE IP}.</li>
     * </ul>
     */
    public List<PfcpRule> tunnels(long seid) {
        List<PfcpRule> out = new ArrayList<>();
        for (PfcpPdr pdr : pdrs.values().stream()
                .sorted(java.util.Comparator.comparingInt(x -> x.pdrId() & 0xffff)).toList()) {
            PfcpFar far = pdr.farId() == null ? null : fars.get(pdr.farId());
            if (pdr.local() == null || far == null || (far.applyAction() & PfcpIe.APPLY_FORW) == 0) {
                continue;
            }
            if (far.remote() == null) {
                PfcpFar back = anchorDownlink(pdr);
                if (back != null) {
                    PfcpUrr urr = pdr.urrIds().isEmpty() ? null : urrs.get(pdr.urrIds().get(0));
                    String qer = pdr.qerIds().isEmpty() ? null : "qer-" + pdr.qerIds().get(0);
                    out.add(new PfcpRule(seid, "pdr-" + (pdr.pdrId() & 0xffff), pdr.local(), back.remote(), qer,
                            true, urr));
                }
                continue;
            }
            PfcpUrr urr = pdr.urrIds().isEmpty() ? null : urrs.get(pdr.urrIds().get(0));
            String qer = pdr.qerIds().isEmpty() ? null : "qer-" + pdr.qerIds().get(0);
            out.add(new PfcpRule(seid, "pdr-" + (pdr.pdrId() & 0xffff), pdr.local(), far.remote(), qer,
                    pdr.sourceInterface() == PfcpIe.INTERFACE_ACCESS, urr));
        }
        return out;
    }

    /** The downlink FAR (FORW + Outer Header Creation) of a CORE PDR matching this PDR's UE address. */
    private PfcpFar anchorDownlink(PfcpPdr uplink) {
        for (PfcpPdr dl : pdrs.values()) {
            if (dl.local() != null || dl.sourceInterface() == PfcpIe.INTERFACE_ACCESS || dl.farId() == null) {
                continue;
            }
            if (uplink.ueIpv4() != null && dl.ueIpv4() != null && !uplink.ueIpv4().equals(dl.ueIpv4())) {
                continue;
            }
            PfcpFar f = fars.get(dl.farId());
            if (f != null && f.remote() != null && (f.applyAction() & PfcpIe.APPLY_FORW) != 0) {
                return f;
            }
        }
        return null;
    }

    public InetAddress ueIpv4() {
        return ueIpv4;
    }

    public Map<Integer, PfcpFar> fars() {
        return fars;
    }

    public Map<Integer, PfcpPdr> pdrs() {
        return pdrs;
    }
}
