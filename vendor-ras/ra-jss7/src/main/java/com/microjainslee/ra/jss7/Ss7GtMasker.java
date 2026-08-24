/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7;

/**
 * GT (Global Title) digit masking for log/admin output — topology and privacy
 * hygiene for the Nextgen STP plane (DESIGN.md §2 "hiding network topology").
 *
 * <p>Keeps the first 3 and last 2 digits when long enough; masks the middle.
 * Short digits (≤5) are masked entirely. Null / blank pass through unchanged.
 * Pure static utility — no state, no allocation beyond the result string.</p>
 */
public final class Ss7GtMasker {

    private Ss7GtMasker() { }

    /**
     * @param digits GT digit string, may be null
     * @return masked form (e.g. {@code 251912345678} → {@code 251******78}),
     *         or the input unchanged when null/blank/too-short-to-hide
     */
    public static String mask(String digits) {
        if (digits == null) {
            return null;
        }
        int n = digits.length();
        if (n <= 5) {
            return "*".repeat(n);
        }
        int keepHead = 3;
        int keepTail = 2;
        return digits.substring(0, keepHead)
                + "*".repeat(n - keepHead - keepTail)
                + digits.substring(n - keepTail);
    }
}
