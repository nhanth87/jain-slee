/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.ies;

import com.microjainslee.api.ActivityContextInterface;

/**
 * Condition object passed to an SBB's
 * {@link com.microjainslee.api.annotations.InitialEventSelect @InitialEventSelect}
 * method. Carries the incoming event and the activity context so the SBB
 * can derive a convergence name and the {@code isInitialEvent} flag.
 *
 * <h2>Example (USSD SBB)</h2>
 * <pre>{@code
 * @InitialEventSelect
 * public InitialEventSelectResult selectInitialEvent(InitialEventSelectCondition c) {
 *     UssdEvent e = (UssdEvent) c.getEvent();
 *     return InitialEventSelectResult.builder()
 *         .convergenceName(e.getMsisdn() + ":" + e.getDialogId())
 *         .initialEvent(e.getType() == UssdEventType.BEGIN)
 *         .build();
 * }
 * }</pre>
 *
 * <p>Spec reference: JAIN SLEE 1.1 §7.5 — Initial Event Selection.
 *
 * @author Tran Nhan (nhanth87)
 */
public record InitialEventSelectCondition(
        Object event,
        ActivityContextInterface aci
) {
    public Object getEvent() { return event; }
    public ActivityContextInterface getAci() { return aci; }
}
