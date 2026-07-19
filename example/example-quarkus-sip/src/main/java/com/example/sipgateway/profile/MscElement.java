/*
 * micro-jainslee 1.2.0 — example application (example-quarkus-sip)
 *
 * Network-element profile stub — MSC/VLR row keyed by mscId (Phase 5).
 */

package com.example.sipgateway.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * MSC/VLR network-element profile — separate concern from subscriber rows.
 * Subscriber {@link TelecomSubscriber} references {@code currentMscId}.
 */
public final class MscElement extends ProfileAbstractCmp {

    public static final String TABLE_NAME = "MscElement";

    public String getMscId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("mscId"));
    }

    public void setMscId(String mscId) {
        ProfileAccessorInvoker.setValue(this, findSetter("mscId", String.class), mscId);
    }

    public String getVlrAddress() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("vlrAddress"));
    }

    public void setVlrAddress(String vlrAddress) {
        ProfileAccessorInvoker.setValue(this, findSetter("vlrAddress", String.class), vlrAddress);
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return MscElement.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return MscElement.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
