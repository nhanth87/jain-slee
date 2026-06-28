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
 * JAIN-SLEE 1.1 §13 — opaque handle returned by an RA that uniquely
 * identifies an activity within the SLEE.
 * <p>
 * RAs hand handles to the container via {@code activityStarted} and
 * use the same handle later in {@code fireEvent} and
 * {@code activityEnded}. The spec leaves equality semantics open;
 * micro-jainslee pins equality to {@link #getId()}.
 */
public interface ActivityHandle {

    /**
     * @return a stable, opaque identifier suitable for hashing and equality.
     */
    String getId();
}
