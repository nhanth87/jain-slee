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

/**
 * Result returned by an SBB's
 * {@link com.microjainslee.api.annotations.InitialEventSelect @InitialEventSelect}
 * method. Tells {@link InitialEventSelectorDispatcher} how to route the
 * incoming event:
 *
 * <ul>
 *   <li>{@link #getConvergenceName()} — unique key for the SBB entity that
 *       already owns this logical session. {@code null} ⇒ stateless SBB,
 *       always allocate a new entity.
 *       <br>Example (USSD): {@code "447911123456:d5"}</li>
 *   <li>{@link #isInitialEvent()} — when {@code true} and no entity matches
 *       the convergence name, the dispatcher will allocate a new entity and
 *       index it under the convergence key. When {@code false} and no
 *       entity matches, the event is dropped per spec §7.5.5
 *       (e.g. a {@code CONTINUE} message arriving before {@code BEGIN}).</li>
 * </ul>
 *
 * @author Tran Nhan (nhanth87)
 */
public final class InitialEventSelectResult {

    private final String convergenceName;
    private final boolean initialEvent;

    private InitialEventSelectResult(Builder b) {
        this.convergenceName = b.convergenceName;
        this.initialEvent = b.initialEvent;
    }

    public String getConvergenceName() { return convergenceName; }
    public boolean isInitialEvent()    { return initialEvent; }

    /** Shorthand: stateless — no convergence, always initial (creates new SBB). */
    public static InitialEventSelectResult stateless() {
        return new Builder().initialEvent(true).build();
    }

    /** Shorthand: session-based routing. */
    public static InitialEventSelectResult forSession(String key, boolean isBegin) {
        return new Builder().convergenceName(key).initialEvent(isBegin).build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String convergenceName;
        private boolean initialEvent = true;

        public Builder convergenceName(String name) {
            this.convergenceName = name;
            return this;
        }
        public Builder initialEvent(boolean v) {
            this.initialEvent = v;
            return this;
        }
        public InitialEventSelectResult build() {
            return new InitialEventSelectResult(this);
        }
    }

    @Override public String toString() {
        return "IESResult{convergence=" + convergenceName + ", initial=" + initialEvent + "}";
    }
}
