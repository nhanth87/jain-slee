/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

import java.util.List;
import java.util.Set;

import org.restcomm.protocols.ss7.sccp.impl.acl.IncomingAccessRule;
import org.restcomm.protocols.ss7.sccp.impl.acl.SccpIncomingAcl;

/**
 * Nextgen STP transit-plane profile applied by {@link Ss7ResourceAdaptor} in
 * {@code raActive()} AFTER the jSS7 stack has started (canRelay / removeSpc /
 * incoming ACL all require a RUNNING SCCP stack).
 *
 * <p>Immutable config record — no behavior beyond {@link #validate()} and the
 * pure {@link #toIncomingAclState()} translation (RA must stay config + wiring,
 * never business logic). When {@code null} on the RA, nothing is applied and
 * the stack behaves exactly as before (terminating-end-node default).</p>
 *
 * <p>HA semantics (ADR 0001/0002): {@link HaMode} is carried here for the
 * Phase-3 lease gate; the shared sticky fabric stays authoritative in the RA
 * regardless of mode.</p>
 */
public record StpTransitProfile(
        /** true → {@code sccp.setCanRelay(true)}: this node relays non-local DPC traffic. */
        boolean transitEnabled,
        /** true → {@code sccp.setRemoveSpc(true)}: topology hiding — peers never see internal PCs. */
        boolean removeSpcOnRelay,
        /** Fabric mode; {@link HaMode#ACTIVE_ACTIVE} is the ADR 0001 default. */
        HaMode haMode,
        /** Mask GT digits in RA log lines (topology / privacy hygiene). */
        boolean maskGtInLogs,
        /** Enable the default-deny incoming ACL (SS7-firewall-lite). */
        boolean aclEnabled,
        /** Deny traffic from OPCs with no rule. STP posture: true. */
        boolean aclDefaultDeny,
        /** One rule per trusted peer OPC; may be empty when {@code !aclEnabled}. */
        List<AclPeerRule> aclRules) {

    /** Dual HA fabric modes for the Nextgen STP plane (DESIGN.md §1). */
    public enum HaMode {
        /** N–N loadshare: 1 AS / N ASPs, ADR 0001 sticky fabric (default). */
        ACTIVE_ACTIVE,
        /** 1+1 warm standby: ISPN lease gate fences the standby (Phase 3). */
        ACTIVE_STANDBY
    }

    /** One per-peer ACL rule — neutral carrier translated to jSS7 {@link IncomingAccessRule}. */
    public record AclPeerRule(
            int incomingOpc,
            String description,
            /** GT prefixes allowed from this OPC; trailing {@code *} = wildcard; empty = no GT restriction. */
            List<String> calledGtPrefixes,
            /** SSNs allowed from this OPC; empty = no SSN restriction. */
            Set<Integer> allowedSsns) {
        public AclPeerRule {
            if (incomingOpc <= 0) {
                throw new IllegalArgumentException("incomingOpc must be > 0, got " + incomingOpc);
            }
            calledGtPrefixes = calledGtPrefixes == null ? List.of() : List.copyOf(calledGtPrefixes);
            allowedSsns = allowedSsns == null ? Set.of() : Set.copyOf(allowedSsns);
        }
    }

    public StpTransitProfile {
        haMode = haMode == null ? HaMode.ACTIVE_ACTIVE : haMode;
        aclRules = aclRules == null ? List.of() : List.copyOf(aclRules);
    }

    /** Non-transit default: nothing applied, zero behavior change. */
    public static StpTransitProfile disabled() {
        return new StpTransitProfile(false, true, HaMode.ACTIVE_ACTIVE, true, false, true, List.of());
    }

    /**
     * Consistency rules (fail fast before stack start — a misconfigured STP must
     * never silently relay):
     * <ul>
     *   <li>ACL enabled requires {@code transitEnabled} (terminating nodes keep jSS7 defaults).</li>
     *   <li>ACL enabled + defaultDeny requires at least one rule (else all traffic dies).</li>
     *   <li>OPC keys must be unique.</li>
     * </ul>
     *
     * @throws IllegalArgumentException on violation
     */
    public void validate() {
        if (aclEnabled && !transitEnabled) {
            throw new IllegalArgumentException("StpTransitProfile: aclEnabled requires transitEnabled");
        }
        if (aclEnabled && aclDefaultDeny && aclRules.isEmpty()) {
            throw new IllegalArgumentException(
                    "StpTransitProfile: aclEnabled + defaultDeny with zero rules would deny ALL traffic");
        }
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (AclPeerRule r : aclRules) {
            if (!seen.add(r.incomingOpc())) {
                throw new IllegalArgumentException(
                        "StpTransitProfile: duplicate ACL rule for opc=" + r.incomingOpc());
            }
        }
    }

    /**
     * Pure translation to the jSS7 ACL state model (Jackson-persistable).
     * Rules carry {@link IncomingAccessRule.Action#ALLOW}; blocking a peer = drop
     * its rule (defaultDeny takes over).
     */
    public SccpIncomingAcl.State toIncomingAclState() {
        SccpIncomingAcl.State state = new SccpIncomingAcl.State();
        state.setEnabled(aclEnabled);
        state.setDefaultDeny(aclDefaultDeny);
        java.util.List<IncomingAccessRule> rules = new java.util.ArrayList<>(aclRules.size());
        for (AclPeerRule r : aclRules) {
            rules.add(new IncomingAccessRule(
                    r.incomingOpc(),
                    IncomingAccessRule.Action.ALLOW,
                    r.calledGtPrefixes(),
                    r.allowedSsns()));
        }
        state.setRules(rules);
        return state;
    }
}
