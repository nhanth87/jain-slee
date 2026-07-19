/*
 * micro-jainslee 1.2.0 — example application (example-quarkus-helloworld-web)
 *
 * Reference Session profile for crash-recovery checkpoints (Phase 5).
 * Domain models live in the example app — not in jainslee-api.
 */

package com.example.helloworld.quarkus.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * Session checkpoint row — durable recovery state keyed by {@code profileKey}.
 * See {@code docs/en/profile-programming-model.md} §8.
 */
public final class SessionProfile extends ProfileAbstractCmp {

    /** Logical table name (SubscriberSession slice in the programming model). */
    public static final String TABLE_NAME = "SubscriberSession";

    public String getProfileKey() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("profileKey"));
    }

    public void setProfileKey(String profileKey) {
        ProfileAccessorInvoker.setValue(this, findSetter("profileKey", String.class), profileKey);
    }

    public String getLastActivityId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("lastActivityId"));
    }

    public void setLastActivityId(String lastActivityId) {
        ProfileAccessorInvoker.setValue(this, findSetter("lastActivityId", String.class), lastActivityId);
    }

    public String getCheckpointJson() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("checkpointJson"));
    }

    public void setCheckpointJson(String checkpointJson) {
        ProfileAccessorInvoker.setValue(this, findSetter("checkpointJson", String.class), checkpointJson);
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return SessionProfile.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return SessionProfile.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
