/*
 * micro-jainslee 1.2.0 — example application (example-quarkus-helloworld-web)
 */

package com.example.helloworld.quarkus.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * Thin app-user row for the HelloWorld reference — optional slice alongside
 * {@link SessionProfile}. Telecom subscriber fields live in the SIP example
 * ({@code example-quarkus-sip/.../profile/TelecomSubscriber}).
 */
public final class AppUserProfile extends ProfileAbstractCmp {

    public static final String TABLE_NAME = "AppUser";

    public String getUserId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("userId"));
    }

    public void setUserId(String userId) {
        ProfileAccessorInvoker.setValue(this, findSetter("userId", String.class), userId);
    }

    public String getDisplayName() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("displayName"));
    }

    public void setDisplayName(String displayName) {
        ProfileAccessorInvoker.setValue(this, findSetter("displayName", String.class), displayName);
    }

    /** Optional MSISDN link — secondary index demo when {@code registerIndex} is available. */
    public String getMsisdn() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("msisdn"));
    }

    public void setMsisdn(String msisdn) {
        ProfileAccessorInvoker.setValue(this, findSetter("msisdn", String.class), msisdn);
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return AppUserProfile.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return AppUserProfile.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
