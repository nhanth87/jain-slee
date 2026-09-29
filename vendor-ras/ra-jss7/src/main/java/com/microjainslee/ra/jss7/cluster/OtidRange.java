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

import java.io.Serializable;
import java.util.Objects;

/**
 * One node's TCAP OTID range (inclusive), published cluster-wide so any node
 * can name the owner of a DTID arithmetically (ADR 0007 D11).
 */
public record OtidRange(String nodeId, long start, long end) implements Serializable {

    private static final long serialVersionUID = 1L;

    public OtidRange {
        Objects.requireNonNull(nodeId, "nodeId");
        if (start <= 0 || end < start) {
            throw new IllegalArgumentException("invalid OTID range [" + start + ", " + end + "] for " + nodeId);
        }
    }

    public boolean contains(long otid) {
        return otid >= start && otid <= end;
    }

    public boolean overlaps(OtidRange other) {
        return start <= other.end && other.start <= end;
    }
}
