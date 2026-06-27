/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api.management;

/**
 * JAIN-SLEE 1.1 §14 — Management facility marker.
 * <p>
 * <strong>TODO Phase 2:</strong> full MBean wiring via JMX 1.2.1.
 * The interface is intentionally empty in this R&amp;D milestone so that
 * downstream code can already take a {@code Management} dependency and
 * have it resolve once the concrete implementation lands. Embedders
 * should not rely on the marker carrying any methods.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface Management {
    // TODO Phase 2: full MBean via JMX 1.2.1
}
