/*
 * micro-jainslee 1.2.0 — example application (example-quarkus-sip)
 *
 * Reference telecom subscriber profile stub (Phase 5). Full field set is
 * documented in design-ideas/advancedprofile.md — this is a minimal CMP for
 * the SIP gateway example; not part of jainslee-api.
 */

package com.example.sipgateway.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * Per-subscriber telecom row — MSISDN primary key, IMSI and serving NE refs.
 * Register {@code msisdn} secondary index via {@code facility.registerIndex}
 * when Phase 1 index support is on the classpath.
 */
public final class TelecomSubscriber extends ProfileAbstractCmp {

    public static final String TABLE_NAME = "TelecomSubscriber";

    public String getMsisdn() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("msisdn"));
    }

    public void setMsisdn(String msisdn) {
        ProfileAccessorInvoker.setValue(this, findSetter("msisdn", String.class), msisdn);
    }

    public String getImsi() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("imsi"));
    }

    public void setImsi(String imsi) {
        ProfileAccessorInvoker.setValue(this, findSetter("imsi", String.class), imsi);
    }

    public String getCurrentHlrId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("currentHlrId"));
    }

    public void setCurrentHlrId(String currentHlrId) {
        ProfileAccessorInvoker.setValue(this, findSetter("currentHlrId", String.class), currentHlrId);
    }

    public String getCurrentMscId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("currentMscId"));
    }

    public void setCurrentMscId(String currentMscId) {
        ProfileAccessorInvoker.setValue(this, findSetter("currentMscId", String.class), currentMscId);
    }

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return TelecomSubscriber.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return TelecomSubscriber.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
