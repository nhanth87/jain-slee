/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.jss7.cluster;

/**
 * Called when this RA CAS-claims an SCTP {@code ip:port} lease (steady-state or
 * VIP takeover after Infinispan view change). OS-level IP migrate / SCTP rebind
 * is deployment-specific — default RA logging binder is sufficient for unit
 * tests; production plugs Keepalived/cloud VIP or privileged bind here.
 */
@FunctionalInterface
public interface SctpEndpointBinder {

    /**
     * @param endpointKey {@code ip:port} just claimed by this node
     * @param generation  lease generation after successful CAS
     * @param takeover    {@code true} when reclaiming an orphaned peer endpoint
     */
    void onEndpointClaimed(String endpointKey, long generation, boolean takeover);
}
