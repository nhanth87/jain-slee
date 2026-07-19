/*
 * micro-jainslee 1.2.0 — example application (example-quarkus-sip)
 *
 * Network-element profile stub — HLR row keyed by hlrId (Phase 5).
 */

package com.example.sipgateway.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * HLR network-element profile — separate table from subscriber rows.
 * Subscriber {@link TelecomSubscriber} references {@code currentHlrId}.
 */
public final class HlrElement extends ProfileAbstractCmp {

    public static final String TABLE_NAME = "HlrElement";

    public String getHlrId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("hlrId"));
    }

    public void setHlrId(String hlrId) {
        ProfileAccessorInvoker.setValue(this, findSetter("hlrId", String.class), hlrId);
    }

    public String getGlobalTitle() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("globalTitle"));
    }

    public void setGlobalTitle(String globalTitle) {
        ProfileAccessorInvoker.setValue(this, findSetter("globalTitle", String.class), globalTitle);
    }

    public String getRealm() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("realm"));
    }

    public void setRealm(String realm) {
        ProfileAccessorInvoker.setValue(this, findSetter("realm", String.class), realm);
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return HlrElement.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return HlrElement.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
