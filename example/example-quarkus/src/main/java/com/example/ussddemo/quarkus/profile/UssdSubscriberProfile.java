/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.profile;

import com.microjainslee.api.ProfileAbstractCmp;

import java.lang.reflect.Method;

/** Subscriber profile seeded at bootstrap; tier drives menu depth in the SS7 SBB. */
public final class UssdSubscriberProfile extends ProfileAbstractCmp {

    public static final String TABLE_NAME = "ussd-subscriber";

    public String getMsisdn() {
        return (String) read("msisdn");
    }

    public void setMsisdn(String msisdn) {
        write("msisdn", msisdn);
    }

    public int getMenuTier() {
        Integer v = (Integer) read("menuTier");
        return v == null ? 1 : v;
    }

    public void setMenuTier(int menuTier) {
        write("menuTier", menuTier);
    }

    private Object read(String field) {
        try {
            String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Method g = UssdSubscriberProfile.class.getDeclaredMethod("get" + cap);
            return com.microjainslee.api.ProfileAccessorInvoker.getValue(this, g);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private void write(String field, Object value) {
        try {
            String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
            Method g = UssdSubscriberProfile.class.getDeclaredMethod("get" + cap);
            Method s = UssdSubscriberProfile.class.getDeclaredMethod(
                    "set" + cap, g.getReturnType());
            com.microjainslee.api.ProfileAccessorInvoker.setValue(this, s, value);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
