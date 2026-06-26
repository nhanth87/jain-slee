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
 * JAIN-SLEE 1.1 §6.8 — ChildRelation interface.
 * <p>
 * A child relation is a typed collection of child SBB entities that all
 * share the same parent SBB entity. Parents obtain a {@code ChildRelation}
 * from their generated get-child-relation method; this interface exposes
 * {@link #create() create} to spawn a new child plus the inherited
 * {@link java.util.Collection} contract to enumerate, count, and check
 * membership of existing children.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface ChildRelation extends java.util.Collection<SbbLocalObject> {

    /**
     * Create a new child SBB entity on this child relation.
     * <p>
     * The returned object implements the SBB local interface of the child SBB;
     * the parent typically narrows it to the typed interface.
     *
     * @return the freshly created child SBB local object
     * @throws CreateException                    if application-level preconditions fail
     * @throws TransactionRequiredLocalException  if invoked without an active transaction
     * @throws SLEEException                      for system-level failures
     */
    SbbLocalObject create() throws CreateException,
            TransactionRequiredLocalException,
            SLEEException;
}