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

/** Receives authorization decisions (e.g. write denials to an audit log). */
@FunctionalInterface
public interface AuthzAuditSink {

    AuthzAuditSink NONE = decision -> { };

    void record(Decision decision);
}
