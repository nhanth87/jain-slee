/*
 * micro-jainslee 1.2.0 — example application (example-quarkus-sip)
 *
 * SIP dialog session profile — Phase 3 checkpoint / recovery (G3).
 * Not part of jainslee-api; lives in the example app.
 */

package com.example.sipgateway.profile;

import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAccessorInvoker;

import java.lang.reflect.Method;

/**
 * Per-dialog session row keyed by {@code callId}.
 *
 * <p>Checkpoint JSON is written here so a new SBB entity can resume the same SIP
 * dialog after pool eviction / JVM restart — demonstrating Phase 3 Goal G3:
 * "SBB chết → hot reload từ profile".
 *
 * <p>Table name: {@value #TABLE_NAME}
 *
 * <h3>Fields</h3>
 * <ul>
 *   <li>{@code callId} — SIP Call-ID (primary key, informational copy)</li>
 *   <li>{@code dialogState} — current dialog state machine value (as String;
 *       C7 whitelist allows only JDK types)</li>
 *   <li>{@code fromUri} — SIP From URI (for resume logging)</li>
 *   <li>{@code toUri} — SIP To URI (for resume logging)</li>
 *   <li>{@code checkpointJson} — free-form JSON snapshot written by
 *       {@link com.microjainslee.core.ProfileAttachment#checkpoint}</li>
 * </ul>
 */
public final class SipDialogSessionProfile extends ProfileAbstractCmp {

    public static final String TABLE_NAME = "SipDialogSession";

    // -----------------------------------------------------------------------
    // callId
    // -----------------------------------------------------------------------

    public String getCallId() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("callId"));
    }

    public void setCallId(String callId) {
        ProfileAccessorInvoker.setValue(this, findSetter("callId", String.class), callId);
    }

    // -----------------------------------------------------------------------
    // dialogState
    // -----------------------------------------------------------------------

    public String getDialogState() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("dialogState"));
    }

    public void setDialogState(String dialogState) {
        ProfileAccessorInvoker.setValue(this, findSetter("dialogState", String.class), dialogState);
    }

    // -----------------------------------------------------------------------
    // fromUri
    // -----------------------------------------------------------------------

    public String getFromUri() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("fromUri"));
    }

    public void setFromUri(String fromUri) {
        ProfileAccessorInvoker.setValue(this, findSetter("fromUri", String.class), fromUri);
    }

    // -----------------------------------------------------------------------
    // toUri
    // -----------------------------------------------------------------------

    public String getToUri() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("toUri"));
    }

    public void setToUri(String toUri) {
        ProfileAccessorInvoker.setValue(this, findSetter("toUri", String.class), toUri);
    }

    // -----------------------------------------------------------------------
    // checkpointJson — written by ProfileAttachment.checkpoint (Phase 3 C9)
    // -----------------------------------------------------------------------

    public String getCheckpointJson() {
        return (String) ProfileAccessorInvoker.getValue(this, findGetter("checkpointJson"));
    }

    public void setCheckpointJson(String checkpointJson) {
        ProfileAccessorInvoker.setValue(this,
                findSetter("checkpointJson", String.class), checkpointJson);
    }

    // -----------------------------------------------------------------------
    // Reflection helpers (same pattern as TelecomSubscriber / HlrElement)
    // -----------------------------------------------------------------------

    private static Method findGetter(String field) {
        String name = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return SipDialogSessionProfile.class.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no getter for " + field, e);
        }
    }

    private static Method findSetter(String field, Class<?> type) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        try {
            return SipDialogSessionProfile.class.getDeclaredMethod(name, type);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("no setter for " + field, e);
        }
    }
}
