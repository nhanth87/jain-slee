/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.AlarmFacility;
import com.microjainslee.api.AlarmLevel;
import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileNotFoundException;
import com.microjainslee.api.SLEEException;
import com.microjainslee.api.UnrecognizedProfileTableNameException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * Phase 3 — {@code ProfileAttachment} helper.
 *
 * <p>Provides a concise API for SBBs to bind their session state to profile
 * rows and survive entity pool eviction or JVM restart:
 *
 * <ul>
 *   <li>{@link #require(String, String)} — get an existing row or throw.</li>
 *   <li>{@link #getOrCreateFromDefault(String, String, Class)} — get-or-create a row.
 *       TODO(Phase2): switch to facility.createFromDefault() once default profiles are
 *       fully wired in the write-behind path.</li>
 *   <li>{@link #checkpoint(String, String, String)} — persist a free-form JSON string
 *       into the {@code checkpointJson} field of a session profile row. Failure logs
 *       ERROR (Contract C9 — never swallowed) and raises an alarm when an
 *       {@link AlarmFacility} is configured.</li>
 *   <li>{@link #restoreCheckpoint(String, String)} — read the last saved JSON string.</li>
 * </ul>
 *
 * <h3>C9 contract (checkpoint failure is always visible)</h3>
 * <p>Unlike {@code cmpPersist()} which silently best-efforts, any failure inside
 * {@link #checkpoint} is reported with {@code LOG.error(...)} and optionally raised as
 * an operator alarm. A {@link CheckpointException} (an unchecked {@link RuntimeException})
 * is thrown so the SBB handler sees the failure and the C3 undo path can roll back any
 * profile writes made during the same delivery.
 *
 * <h3>Thread safety</h3>
 * <p>This class is stateless (no mutable fields); instances may be shared freely
 * across SBBs and called from any thread. The underlying {@link ProfileFacility}
 * must be thread-safe (which {@link InMemoryProfileFacility} is).
 *
 * @author Tran Nhan (nhanth87)
 * @see PROFILE-IMPLEMENTATION-PLAN.md Phase 3 §5.4
 */
public final class ProfileAttachment {

    /** Well-known field name for session checkpoint JSON (§10, D4). */
    public static final String CHECKPOINT_FIELD = "checkpointJson";

    private static final Logger LOG = LogManager.getLogger(ProfileAttachment.class);

    private final ProfileFacility facility;
    /** Optional; when {@code null} alarms are suppressed (warn instead). */
    private final AlarmFacility alarmFacility;

    /**
     * Create a {@code ProfileAttachment} without alarm support.
     *
     * @param facility the profile facility to operate on (must not be {@code null})
     */
    public ProfileAttachment(ProfileFacility facility) {
        this(facility, null);
    }

    /**
     * Create a {@code ProfileAttachment} with optional alarm support.
     *
     * @param facility      the profile facility to operate on (must not be {@code null})
     * @param alarmFacility optional alarm facility; {@code null} = warn instead of alarm
     */
    public ProfileAttachment(ProfileFacility facility, AlarmFacility alarmFacility) {
        if (facility == null) {
            throw new IllegalArgumentException("facility is required");
        }
        this.facility = facility;
        this.alarmFacility = alarmFacility;
    }

    // -----------------------------------------------------------------------
    // Core helpers
    // -----------------------------------------------------------------------

    /**
     * Require an existing profile row. Throws {@link ProfileNotFoundException}
     * when the row is absent rather than returning {@code null}.
     *
     * @param table the profile table name
     * @param key   the profile key (primary key)
     * @return the live {@link ProfileLocalObject}
     * @throws ProfileNotFoundException if the row does not exist
     */
    public ProfileLocalObject require(String table, String key) throws ProfileNotFoundException {
        if (table == null) {
            throw new IllegalArgumentException("table is required");
        }
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        ProfileLocalObject plo = facility.getProfile(new ProfileID(table, key));
        if (plo == null || plo.isInvalidated()) {
            throw new ProfileNotFoundException(new ProfileID(table, key));
        }
        return plo;
    }

    /**
     * Get an existing profile row or create a new one.
     *
     * <p>If a row for {@code (table, key)} already exists it is returned as-is.
     * Otherwise a blank row is created using {@code type} as the CMP class.
     *
     * <p><b>TODO(Phase1 default):</b> once {@code facility.createFromDefault(table, key, type)}
     * is fully wired in the write-behind path, delegate to that method so newly created rows
     * inherit the default field values registered by the app's bootstrap.
     *
     * @param table the profile table name
     * @param key   the profile key (primary key)
     * @param type  the Profile subclass to use when creating a new row
     * @return the live {@link ProfileLocalObject}
     * @throws UnrecognizedProfileTableNameException if {@code table} does not exist
     * @throws SLEEException                         for system-level failures
     */
    public ProfileLocalObject getOrCreateFromDefault(String table, String key,
                                                     Class<? extends Profile> type)
            throws UnrecognizedProfileTableNameException, SLEEException {
        if (table == null) {
            throw new IllegalArgumentException("table is required");
        }
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        ProfileLocalObject existing = facility.getProfile(new ProfileID(table, key));
        if (existing != null && !existing.isInvalidated()) {
            return existing;
        }
        // TODO(Phase1 default): use facility.createFromDefault(table, key, type) once
        // the default-profile write-behind flush path is fully wired (Phase 2).
        // For now, create a blank row. The caller (bootstrap / SBB.sbbActivate) is
        // responsible for populating required fields from its own recovery logic.
        try {
            return facility.createProfile(table, key, type);
        } catch (ProfileAlreadyExistsException race) {
            // Concurrent create — fetch what the winner created.
            ProfileLocalObject won = facility.getProfile(new ProfileID(table, key));
            if (won != null && !won.isInvalidated()) {
                return won;
            }
            throw new SLEEException("Profile row appeared and vanished in a tight window: "
                    + table + "/" + key, race);
        }
    }

    // -----------------------------------------------------------------------
    // Session checkpoint (C9: failures are never silent)
    // -----------------------------------------------------------------------

    /**
     * Persist a free-form JSON string into the {@value #CHECKPOINT_FIELD} field of
     * the specified session profile row.
     *
     * <p><b>Contract C9:</b> any failure (table missing, row missing, write error) is
     * logged at {@code ERROR} level <em>before</em> propagating. When an
     * {@link AlarmFacility} is configured an operator alarm is also raised. A
     * {@link CheckpointException} is thrown so the SBB handler fails and the C3
     * undo-log can restore profile fields written earlier in the same delivery.
     *
     * <p>If the session row does not yet exist, this method creates it automatically
     * using a bare {@link com.microjainslee.api.ProfileAbstractCmp}-compatible row so
     * the write can proceed without requiring the caller to pre-provision.
     *
     * @param sessionTable table that holds the session row (e.g. {@code "SubscriberSession"})
     * @param key          the profile key
     * @param json         the checkpoint payload (must not be {@code null})
     * @throws CheckpointException if the checkpoint write fails for any reason
     */
    public void checkpoint(String sessionTable, String key, String json) {
        if (sessionTable == null) {
            throw new IllegalArgumentException("sessionTable is required");
        }
        if (key == null) {
            throw new IllegalArgumentException("key is required");
        }
        if (json == null) {
            throw new IllegalArgumentException("json is required");
        }
        try {
            // Fast path: write directly via the internal table when possible.
            if (facility instanceof InMemoryProfileFacility imf) {
                InMemoryProfileTable table = imf.findTableInternal(sessionTable);
                if (table == null) {
                    throw new IllegalStateException(
                            "Checkpoint target table '" + sessionTable + "' does not exist. "
                            + "Ensure the session table is created during bootstrap.");
                }
                if (!table.containsProfile(key)) {
                    // Auto-create a minimal session row so the field write can proceed.
                    com.microjainslee.api.ProfileAbstractCmp bare = new BareSessionProfile();
                    bare.bindProfile(sessionTable, key);
                    if (!table.put(key, bare)) {
                        // Concurrent creation — that is fine; the row now exists.
                    }
                }
                table.writeField(key, CHECKPOINT_FIELD, json);
                LOG.debug("[ProfileAttachment] checkpoint written: table={} key={} bytes={}",
                        sessionTable, key, json.length());
                return;
            }
            // Generic fallback: go through the public ProfileFacility API.
            ProfileLocalObject plo = facility.getProfile(new ProfileID(sessionTable, key));
            if (plo == null) {
                throw new IllegalStateException(
                        "No session row for '" + key + "' in table '" + sessionTable + "'. "
                        + "Call getOrCreateFromDefault() before checkpoint().");
            }
            Profile profile = plo.getProfile();
            if (profile == null) {
                throw new IllegalStateException("ProfileLocalObject returned a null Profile.");
            }
            profile.setCmpField(CHECKPOINT_FIELD, json);
            LOG.debug("[ProfileAttachment] checkpoint written (fallback): table={} key={}",
                    sessionTable, key);
        } catch (CheckpointException ce) {
            throw ce;
        } catch (Exception ex) {
            String msg = "[ProfileAttachment][C9] CHECKPOINT WRITE FAILED — session recovery data lost! "
                    + "table=" + sessionTable + " key=" + key + " error=" + ex.getMessage();
            LOG.error(msg, ex);
            raiseAlarm("profile.checkpoint.failure", sessionTable + "/" + key,
                    AlarmLevel.CRITICAL, msg);
            throw new CheckpointException(msg, ex);
        }
    }

    /**
     * Read the last saved JSON checkpoint for the given session profile row.
     *
     * @param sessionTable the session profile table name
     * @param key          the profile key
     * @return the last checkpoint JSON, or {@link Optional#empty()} if no checkpoint
     *         has been written or the row does not exist
     */
    public Optional<String> restoreCheckpoint(String sessionTable, String key) {
        if (sessionTable == null || key == null) {
            return Optional.empty();
        }
        try {
            // Fast path via internal table.
            if (facility instanceof InMemoryProfileFacility imf) {
                InMemoryProfileTable table = imf.findTableInternal(sessionTable);
                if (table == null || !table.containsProfile(key)) {
                    return Optional.empty();
                }
                Object raw = table.readField(key, CHECKPOINT_FIELD);
                return raw instanceof String s ? Optional.of(s) : Optional.empty();
            }
            // Generic fallback.
            ProfileLocalObject plo = facility.getProfile(new ProfileID(sessionTable, key));
            if (plo == null || plo.isInvalidated()) {
                return Optional.empty();
            }
            Profile profile = plo.getProfile();
            if (profile == null) {
                return Optional.empty();
            }
            Object raw = profile.getCmpField(CHECKPOINT_FIELD);
            return raw instanceof String s ? Optional.of(s) : Optional.empty();
        } catch (Exception ex) {
            LOG.warn("[ProfileAttachment] restoreCheckpoint read failed: table={} key={} error={}",
                    sessionTable, key, ex.getMessage());
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    /**
     * Unchecked exception thrown when {@link #checkpoint} cannot persist the
     * JSON payload (Contract C9 — never silently swallowed).
     */
    public static final class CheckpointException extends RuntimeException {
        public CheckpointException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Minimal Profile CMP used when auto-creating a bare session row via the
     * internal table API. Holds no typed accessors; the {@value #CHECKPOINT_FIELD}
     * field is written directly to the field map.
     *
     * <p>This is NOT part of the public API. Apps should define their own typed
     * profile (e.g. {@code SessionProfile}) with a {@code checkpointJson} field.
     */
    private static final class BareSessionProfile extends com.microjainslee.api.ProfileAbstractCmp {
        // No typed accessors: the checkpoint field is written directly via
        // InMemoryProfileTable.writeField in checkpoint() above.
        // getCmpFieldNames() returns an empty array for this bare type.
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private void raiseAlarm(String type, String instance, AlarmLevel level, String message) {
        AlarmFacility af = this.alarmFacility;
        if (af == null) {
            LOG.warn("[ProfileAttachment] no AlarmFacility bound — alarm suppressed: {} {} {}",
                    type, instance, message);
            return;
        }
        try {
            af.raise(type, instance, level, message);
        } catch (Exception ex) {
            LOG.warn("[ProfileAttachment] alarmFacility.raise failed: {}", ex.getMessage());
        }
    }
}
