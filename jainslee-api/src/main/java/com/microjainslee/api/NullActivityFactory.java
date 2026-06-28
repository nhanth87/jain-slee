/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §15 — Null Activity Factory.
 * <p>
 * Lets an RA create a transient "null activity" — an ACI not bound to
 * any real protocol activity — to fire RA-initiated events into the
 * SLEE. RAs use this for unsolicited messages (probes, scheduled
 * notifications, periodic keep-alives) where no inbound dialog
 * establishes the ACI.
 */
public interface NullActivityFactory {

    /**
     * Create a new null activity bound to the given name.
     *
     * @param name logical name to register in the ACNF
     * @return the freshly-created ACI
     */
    ActivityContextInterface createNullActivity(String name);
}
