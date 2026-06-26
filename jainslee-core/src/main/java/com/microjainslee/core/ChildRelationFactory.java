/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.SbbLocalObject;

/**
 * Factory SPI that {@link ChildRelationImpl} invokes when the parent SBB
 * calls {@link com.microjainslee.api.ChildRelation#create()}.
 *
 * <p>micro-jainslee keeps the parent SBB free of any container knowledge
 * by routing child creation through this single-method factory — the
 * container (which owns the {@link MicroSleeContainer}) supplies the
 * implementation at registration time so it can keep the SBB entity pool,
 * the SBB lifecycle manager, and the {@link CmpFieldStore} consistent.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface ChildRelationFactory {

    /**
     * Create a new child SBB entity under the given parent.
     *
     * @param parentSbbId id of the parent SBB entity
     * @return the freshly-created child local object
     */
    SbbLocalObject createChild(String parentSbbId);
}