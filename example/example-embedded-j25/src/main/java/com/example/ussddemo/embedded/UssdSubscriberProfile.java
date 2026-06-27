/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.embedded;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * In-memory subscriber profile: MSISDN maps to a menu tier used by
 * {@code HttpServerSbb} when routing toward the SS7 ingress leg.
 */
public final class UssdSubscriberProfile extends ProfileAbstractCmp {

    public String getMsisdn() {
        return (String) ProfileAccessorInvoker.getValue(this, getter("msisdn"));
    }

    public void setMsisdn(String msisdn) {
        ProfileAccessorInvoker.setValue(this, setter("msisdn", String.class), msisdn);
    }

    public String getTier() {
        return (String) ProfileAccessorInvoker.getValue(this, getter("tier"));
    }

    public void setTier(String tier) {
        ProfileAccessorInvoker.setValue(this, setter("tier", String.class), tier);
    }

    private static Method getter(String field) {
        String capitalized = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return UssdSubscriberProfile.class.getDeclaredMethod("get" + capitalized);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method setter(String field, Class<?> type) {
        String capitalized = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return UssdSubscriberProfile.class.getDeclaredMethod("set" + capitalized, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
