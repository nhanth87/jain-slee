package com.microjainslee.core.ies;

/**
 * Result của IES method — xác định routing behavior cho incoming event.
 *
 * convergenceName: unique key để tìm SBB entity đang tồn tại.
 *   null → no convergence, always create new (stateless SBBs)
 *   "447911123456:d5" → USSD session key (msisdn + dialogId)
 *   "sip:alice@example.com:call-001" → SIP dialog key
 *
 * isInitialEvent: nếu false và không tìm thấy entity có cùng convergenceName
 *   → event được dropped per spec §7.5.5 (không tạo entity mới)
 *   Ví dụ: USSD Continue message đến nhưng Begin chưa được xử lý
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
