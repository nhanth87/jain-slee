/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.removal;

/**
 * Immutable value object fired whenever an SBB entity is removed.
 * Carried on the {@link EntityRemovalBus}; never crosses thread boundaries
 * (always published on the virtual thread that owns the entity slot).
 *
 * <p>Sprint S6 (Observability &amp; Removal Notification) — replaces the legacy
 * direct {@code RemovalListener} callback with a publish-subscribe bus so
 * independent observers (IES cleanup, metrics, store auto-fail, lifecycle
 * logger) can attach without coupling to {@code MicroSleeContainer}.
 *
 * @author Tran Nhan (nhanth87)
 */
public record EntityRemovalEvent(
        String entityId,
        String convergenceKey,   // null when IES was not used
        RemovalReason reason,
        long timestampMs
) {
    /**
     * Why the SBB entity was removed. The kernel maps a
     * {@code SimpleSbbLocalObject.RemovalCause} into one of these finer-grained
     * values so subscribers can route / log meaningfully.
     */
    public enum RemovalReason {
        /** An SBB Timer fired (the entity ran out of timer budget). */
        TIMER_EXPIRED,
        /** The SBB itself called {@code sbbLocalObject.remove()}. */
        SBB_SELF_REMOVE,
        /** A child SBB was removed and the parent was cascaded. */
        CASCADE_CHILD,
        /** Operator / management plane action. */
        OPERATOR,
        /** SBB code threw and the container rolled the entity back. */
        EXCEPTION_ROLLBACK,
        /** Hot redeploy — the SBB class was swapped out at runtime. */
        HOT_REDEPLOY
    }
}