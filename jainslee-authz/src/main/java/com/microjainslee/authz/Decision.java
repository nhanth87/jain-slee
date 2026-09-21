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
 * Outcome of one authorization check, passed to {@link AuthzAuditSink}.
 *
 * @param principal the caller ({@code null} when unauthenticated)
 * @param action    what was checked, e.g. {@code "role:tenant-admin"}
 * @param allowed   result
 * @param reason    denial reason, {@code null} when allowed
 * @param detail    human-readable detail
 */
public record Decision(Principal principal, String action, boolean allowed,
                       AuthzException.Reason reason, String detail) {
}
