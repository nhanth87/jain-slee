/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * Subscriber profile seeded at bootstrap. {@link com.example.ussddemo.spring.sbbs.HttpServerSbb}
 * looks up menu tier by MSISDN before routing to the SS7 leg.
 */
public final class UssdSubscriberProfile extends ProfileAbstractCmp {

    public String getMsisdn() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("msisdn"));
    }

    public void setMsisdn(String msisdn) {
        ProfileAccessorInvoker.setValue(this, findSetter("msisdn", String.class), msisdn);
    }

    public int getMenuTier() {
        Integer v = (Integer) ProfileAccessorInvoker.getValue(this, findGetter("menuTier"));
        return v == null ? 1 : v;
    }

    public void setMenuTier(int menuTier) {
        ProfileAccessorInvoker.setValue(this, findSetter("menuTier", int.class), menuTier);
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return UssdSubscriberProfile.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return UssdSubscriberProfile.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
