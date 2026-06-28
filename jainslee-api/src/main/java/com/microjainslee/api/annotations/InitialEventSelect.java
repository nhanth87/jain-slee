/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * JAIN-SLEE 1.1 §7.5 — Marker for a Service Building Block (SBB) method that
 * performs <em>Initial Event Selection</em>.
 *
 * <p>A method annotated with {@code @InitialEventSelect} must have the
 * following signature:
 *
 * <pre>{@code
 * @InitialEventSelect
 * public InitialEventSelectResult selectInitialEvent(InitialEventSelectCondition c) {
 *     // ... derive convergence name + isInitialEvent ...
 * }
 * }</pre>
 *
 * <p>The method is invoked by
 * {@link com.microjainslee.core.ies.InitialEventSelectorDispatcher} on a
 * <strong>temporary</strong> SBB instance (never on a pooled entity) per
 * spec §7.5 — IES must therefore be free of side effects on CMP state.
 *
 * <h2>Usage example (USSD SBB)</h2>
 * <pre>{@code
 * @InitialEventSelect(name = "ussd-convergence")
 * public InitialEventSelectResult selectInitialEvent(InitialEventSelectCondition c) {
 *     UssdEvent e = (UssdEvent) c.getEvent();
 *     return InitialEventSelectResult.forSession(
 *         e.getMsisdn() + ":" + e.getDialogId(),
 *         e.getType() == UssdEventType.BEGIN
 *     );
 * }
 * }</pre>
 *
 * @author Tran Nhan (nhanth87)
 * @see com.microjainslee.core.ies.InitialEventSelectCondition
 * @see com.microjainslee.core.ies.InitialEventSelectResult
 * @see com.microjainslee.core.ies.InitialEventSelectorDispatcher
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface InitialEventSelect {

    /**
     * Optional human-readable name used for logging / metrics only.
     * Defaults to the empty string — the dispatcher will fall back to
     * {@code SBBClass#methodName}.
     */
    String name() default "";
}
