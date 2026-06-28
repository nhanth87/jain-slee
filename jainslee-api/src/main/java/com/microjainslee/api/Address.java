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
 * JAIN-SLEE 1.1 §6 — abstract address used by event routing to identify
 * a destination (e.g. a SIP URI, a phone number, an E.164 MSISDN).
 * <p>
 * The base interface is intentionally minimal so protocol-specific
 * address implementations (MSISDN, SIP-URI, IMSI, …) can extend it
 * with their own schema.
 */
public interface Address {

    /**
     * @return a stable string identifier suitable for hashing and
     *         equality; e.g. {@code "sip:+15551234567@slee.example"}.
     */
    String getAddressString();
}
