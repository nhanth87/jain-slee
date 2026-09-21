/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.authz;

/**
 * Lifecycle of a principal's account. Only {@link #ACTIVE} accounts pass
 * {@link AuthzPolicy#requireActive(Principal)}: users must be approved
 * before they may use the service.
 */
public enum AccountStatus {
    ACTIVE,
    PENDING_APPROVAL,
    SUSPENDED,
    DISABLED;

    /** Lenient parse: unknown / null → {@link #PENDING_APPROVAL} (fail closed). */
    public static AccountStatus parse(String value) {
        if (value == null) {
            return PENDING_APPROVAL;
        }
        String v = value.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (v) {
            case "ACTIVE", "APPROVED" -> ACTIVE;
            case "SUSPENDED" -> SUSPENDED;
            case "DISABLED", "DELETED", "REJECTED" -> DISABLED;
            default -> PENDING_APPROVAL;
        };
    }
}
