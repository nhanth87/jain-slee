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
 * JAIN-SLEE 1.1 §7.1 — Initial event selector for root SBB selection.
 *
 * <h2>Two flavours</h2>
 * <ol>
 *   <li><strong>Legacy selector</strong> — the five original accessor
 *       methods below are kept binary-compatible. They are driven by
 *       {@link com.microjainslee.core.InitialEventSelectorCustomizer}
 *       during {@link com.microjainslee.core.MicroSleeContainer#routeEvent}.
 *   </li>
 *   <li><strong>Per-SBB IES method</strong> — SBBs may declare a method
 *       annotated with
 *       {@link com.microjainslee.api.annotations.InitialEventSelect} returning
 *       a {@code com.microjainslee.core.ies.InitialEventSelectResult}. This
 *       style is dispatched by
 *       {@link com.microjainslee.core.ies.InitialEventSelectorDispatcher}
 *       (Perfect Core S3) and is the preferred way to express convergence.
 *   </li>
 * </ol>
 *
 * <p>The default {@code setInitialEvent} / {@code setRootSbbId} no-op
 * overloads preserve source-level backward compatibility for customizers
 * that do not need to call them.
 *
 * @author Tran Nhan (nhanth87)
 */
public interface InitialEventSelector {
    boolean isInitialEvent();
    void setInitialEvent(boolean initial);
    SleeEvent getEvent();
    ActivityContextInterface getActivityContext();
    String getRootSbbId();
    void setRootSbbId(String rootSbbId);

    // ─────────────────────────────────────────────────────────────────────
    // Backward-compat extension — default no-op overloads.
    // Existing customizers compiled against the 6-method surface continue
    // to work unchanged.
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Convenience used by reflectively-bound customizers that do not need
     * to mutate {@code initialEvent}. The default implementation is a no-op.
     */
    default void setInitialEvent() {
        // no-op — kept for backward compat with old customizers
    }

    /**
     * Convenience used by reflectively-bound customizers that do not need
     * to mutate {@code rootSbbId}. The default implementation is a no-op.
     */
    default void setRootSbbId() {
        // no-op
    }

    /**
     * Marker predicate — returns {@code true} if this selector was produced
     * by the per-SBB IES dispatcher (Perfect Core S3) rather than the
     * legacy customizer path. Default implementation returns {@code false}.
     */
    default boolean isConvergenceBased() {
        return false;
    }

    /**
     * Marker accessor — when {@link #isConvergenceBased()} is {@code true},
     * returns the convergence key that should drive entity lookup. Default
     * returns {@code null} (stateless SBBs).
     */
    default String getConvergenceName() {
        return null;
    }
}
