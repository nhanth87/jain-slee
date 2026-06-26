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
 * JAIN-SLEE 1.1 §6.1.2 — thrown when an SBB entity cannot be created.
 * <p>
 * Thrown by {@link Sbb#sbbCreate()}, {@link Sbb#sbbPostCreate()},
 * or {@link ChildRelation#create()} to abort creation of an SBB entity
 * when an application-level precondition is not satisfied.
 *
 * @author Tran Nhan (nhanth87)
 */
public class CreateException extends SLEEException {

    private static final long serialVersionUID = 1L;

    /**
     * Construct a {@code CreateException} that wraps another {@code CreateException}.
     *
     * @param ex the original exception
     */
    public CreateException(CreateException ex) {
        super(ex != null ? ex.getMessage() : null, ex);
    }

    /**
     * Construct a {@code CreateException} with a detail message.
     *
     * @param message description of the failure
     */
    public CreateException(String message) {
        super(message);
    }

    /**
     * Construct a {@code CreateException} with a detail message and underlying cause.
     *
     * @param message description of the failure
     * @param cause   underlying cause (may be {@code null})
     */
    public CreateException(String message, Throwable cause) {
        super(message, cause);
    }
}