/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import com.microjainslee.api.Sbb;
import com.microjainslee.api.annotations.CmpField;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test fixture for {@link DistributedSbbEntityPool} - a concrete SBB
 * with public getter/setter pairs annotated {@link CmpField} so the
 * reflective snapshot logic in the pool can find them without
 * abstract-method gymnastics.
 *
 * <p>The fields are plain Java properties (public getters/setters on
 * plain fields) so test code can read them back directly without
 * going through {@link com.microjainslee.core.CmpBackedSbb}. The
 * kernel in jainslee-core has its own CmpBackedSbb which is the
 * production way; for the cluster skeleton we just need an SBB whose
 * {@code @CmpField}-annotated accessors the reflective scan can
 * call.
 *
 * <p><b>R&amp;D only &mdash; never for production.</b>
 */
public class CounterSbb implements Sbb {

    // @CmpField can only target METHODS, not fields.
    private int balance;

    private String msisdn;

    private int attempts;

    public CounterSbb() {
    }

    @CmpField("balance")
    public int getBalance() {
        return balance;
    }

    @CmpField("balance")
    public void setBalance(int balance) {
        this.balance = balance;
    }

    @CmpField("msisdn")
    public String getMsisdn() {
        return msisdn;
    }

    @CmpField("msisdn")
    public void setMsisdn(String msisdn) {
        this.msisdn = msisdn;
    }

    @CmpField("attempts")
    public int getAttempts() {
        return attempts;
    }

    @CmpField("attempts")
    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    /**
     * Expose the CMP field values as a plain map so tests can compare
     * snapshot contents without going through reflection.
     */
    public Map<String, Object> cmpValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("balance", balance);
        m.put("msisdn", msisdn);
        m.put("attempts", attempts);
        return m;
    }
}
