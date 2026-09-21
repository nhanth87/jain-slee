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

/** Authorization failure. {@link #httpStatus()} maps the reason for HTTP-facing RAs. */
public final class AuthzException extends RuntimeException {

    public enum Reason {
        UNAUTHENTICATED(401),
        EXPIRED(401),
        ACCOUNT_NOT_ACTIVE(403),
        MISSING_ROLE(403),
        MISSING_PERMISSION(403),
        CROSS_TENANT(403);

        private final int httpStatus;

        Reason(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Reason reason;

    public AuthzException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public int httpStatus() {
        return reason.httpStatus();
    }

    /** Stable machine code, e.g. {@code "cross_tenant"}. */
    public String code() {
        return reason.name().toLowerCase(java.util.Locale.ROOT);
    }
}
