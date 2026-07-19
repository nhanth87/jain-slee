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

import com.microjainslee.api.DurableProfileStore;
import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileAbstractCmp;
import com.microjainslee.api.ProfileAddedEvent;
import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileEventSink;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileFieldTypes;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileMutation;
import com.microjainslee.api.ProfileNotFoundException;
import com.microjainslee.api.ProfileRemovedEvent;
import com.microjainslee.api.ProfileTable;
import com.microjainslee.api.ProfileTablePort;
import com.microjainslee.api.ProfileUpdatedEvent;
import com.microjainslee.api.SLEEException;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.UnrecognizedProfileTableNameException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * In-memory implementation of the JAIN-SLEE 1.1 {@link ProfileFacility}.
 *
 * <p>Backed by a {@link ConcurrentHashMap} of {@link InMemoryProfileTable}
 * instances keyed by table name. Created lazily on
 * {@link #createProfileTable(String)} or implicitly on
 * {@link #createProfile(String, String, Class)}.
 *
 * <h3>Phase 1 additions (C4, C5, C7, C8, §10.5, §10.6, §10.8, §10.12)</h3>
 * <ul>
 *   <li><b>Default profiles (§10.5)</b> — {@link #setDefaultProfile} stores the
 *       CMP field snapshot <em>and</em> the profile class; {@link #createFromDefault}
 *       instantiates a new row from that snapshot.</li>
 *   <li><b>Secondary indexes (§10.6, §10.8)</b> — {@link #registerIndex} /
 *       {@link #findProfilesByAttribute}. Unregistered attribute throws (no silent scan).</li>
 *   <li><b>Atomic ops (C4)</b> — {@link #addToLong} / {@link #updateField} /
 *       {@link #compareAndSetField}.</li>
 *   <li><b>Profile events (C5)</b> — opt-in via {@link #enableEvents};
 *       coalescing buffer + virtual-thread drain, <em>never</em> blocks mutator.</li>
 *   <li><b>C8 stale LO</b> — shared per-row liveness flag in
 *       {@link InMemoryProfileTable}; removal sets flag to false, all outstanding
 *       {@link SimpleProfileLocalObject}s throw {@link ProfileNotFoundException}.</li>
 *   <li><b>flushSync stub</b> — no-op for in-memory mode; Phase 2 will add
 *       write-behind semantics.</li>
 * </ul>
 *
 * @author Tran Nhan (nhanth87)
 */
public final class InMemoryProfileFacility implements ProfileTablePort, ProfileFieldAccess {

    private static final Logger LOG = LogManager.getLogger(InMemoryProfileFacility.class);

    private final ConcurrentHashMap<String, InMemoryProfileTable> tables =
            new ConcurrentHashMap<String, InMemoryProfileTable>();

    // -----------------------------------------------------------------------
    // Phase 1 §10.5 — default profile specs per table.
    // Stores both the field snapshot (C7-safe JDK types) and the profile class
    // so createFromDefault can instantiate the correct type.
    // -----------------------------------------------------------------------

    /** Holds a default-profile field snapshot + the profile class. */
    private static final class DefaultSpec {
        final Class<? extends Profile> profileClass;
        final Map<String, Object> fieldSnapshot;

        DefaultSpec(Class<? extends Profile> profileClass, Map<String, Object> fieldSnapshot) {
            this.profileClass = profileClass;
            this.fieldSnapshot = fieldSnapshot;
        }
    }

    private final ConcurrentHashMap<String, DefaultSpec> defaultSpecs =
            new ConcurrentHashMap<String, DefaultSpec>();

    // -----------------------------------------------------------------------
    // Phase 1 C5 — coalescing event queue + VT drain.
    // Per-table registered sinks; per-(table+profile) latest event buffer.
    // -----------------------------------------------------------------------

    /** per-table registered sinks; table absent = events disabled */
    private final ConcurrentHashMap<String, ProfileEventSink> eventSinks =
            new ConcurrentHashMap<String, ProfileEventSink>();

    /**
     * Coalescing buffer for CMP field {@link ProfileUpdatedEvent}s only.
     * Structure: tableName → (profileName → latest update event).
     * Only the last update per profile is kept between drain cycles.
     * <p>
     * {@link ProfileAddedEvent} and {@link ProfileRemovedEvent} are NEVER
     * coalesced — they are delivered via the per-table lifecycle queue so
     * that quick create-then-remove sequences produce both events.
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ProfileUpdatedEvent>> coalescingBuffers =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, ProfileUpdatedEvent>>();

    /**
     * Lifecycle event queue (Add/Remove) per table.
     * Unbounded, FIFO — never coalesced.
     */
    private final ConcurrentHashMap<String, java.util.Queue<SleeEvent>> lifecycleQueues =
            new ConcurrentHashMap<String, java.util.Queue<SleeEvent>>();

    /** Non-blocking signal: permits bounded to avoid unlimited growth. */
    private final Semaphore drainTrigger = new Semaphore(0);
    private final AtomicBoolean drainRunning = new AtomicBoolean(false);
    private final AtomicReference<Thread> drainThread = new AtomicReference<>(null);

    // -----------------------------------------------------------------------
    // Phase 2 — write-behind dirty-set + flusher VT
    // -----------------------------------------------------------------------

    /** Optional durable backend; null = memory-only mode. */
    private volatile DurableProfileStore durableStore;

    /**
     * Dirty set: profile IDs written since the last flush cycle.
     * Uses a CHM key-set so add/remove are O(1) and thread-safe.
     */
    private final Set<ProfileID> dirtySet = ConcurrentHashMap.newKeySet();

    /** Background flusher virtual thread (null when no durable store). */
    private volatile Thread flusherThread;
    private final AtomicBoolean flusherRunning = new AtomicBoolean(false);

    /** Default write-behind interval: 100 ms (documented RPO). */
    private static final long FLUSH_INTERVAL_MS = 100L;

    public InMemoryProfileFacility() {
        ProfileFieldStoreLocator.set(this);
        LOG.debug("InMemoryProfileFacility constructed and registered with ProfileFieldStoreLocator");
    }

    // -----------------------------------------------------------------------
    // Core CRUD
    // -----------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public ProfileTable getProfileTable(String tableName) {
        if (tableName == null) {
            return null;
        }
        return tables.get(tableName);
    }

    /** {@inheritDoc} */
    @Override
    public ProfileLocalObject createProfile(String tableName, String profileName,
                                            Class<? extends Profile> profileClass)
            throws UnrecognizedProfileTableNameException,
                   ProfileAlreadyExistsException,
                   SLEEException {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (profileName == null) {
            throw new IllegalArgumentException("profileName is required");
        }
        if (profileClass == null) {
            throw new IllegalArgumentException("profileClass is required");
        }
        InMemoryProfileTable table = tables.get(tableName);
        if (table == null) {
            throw new UnrecognizedProfileTableNameException(
                    "No profile table named '" + tableName + "'");
        }
        if (table.containsProfile(profileName)) {
            throw new ProfileAlreadyExistsException(
                    "Profile '" + profileName + "' already exists in table '" + tableName + "'");
        }
        Profile profile = instantiateProfile(profileClass);
        if (profile instanceof ProfileAbstractCmp) {
            ((ProfileAbstractCmp) profile).bindProfile(tableName, profileName);
        }
        if (!table.put(profileName, profile)) {
            throw new ProfileAlreadyExistsException(
                    "Profile '" + profileName + "' already exists in table '" + tableName + "'");
        }
        // C5: queue added event (non-blocking)
        queueEvent(tableName, profileName,
                new ProfileAddedEvent(new ProfileID(tableName, profileName), System.currentTimeMillis()));
        return table.getProfile(profileName);
    }

    /** {@inheritDoc} */
    @Override
    public ProfileLocalObject getProfile(ProfileID id) {
        if (id == null) {
            return null;
        }
        InMemoryProfileTable table = tables.get(id.getProfileTableName());
        if (table == null) {
            return null;
        }
        return table.getProfile(id.getProfileName());
    }

    /** {@inheritDoc} */
    @Override
    public void removeProfile(ProfileID id) throws UnrecognizedProfileTableNameException {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        InMemoryProfileTable table = tables.get(id.getProfileTableName());
        if (table == null) {
            throw new UnrecognizedProfileTableNameException(
                    "No profile table named '" + id.getProfileTableName() + "'");
        }
        Profile removed = table.remove(id.getProfileName());
        if (removed != null) {
            // C5: queue removed event
            queueEvent(id.getProfileTableName(), id.getProfileName(),
                    new ProfileRemovedEvent(id, System.currentTimeMillis()));
        }
    }

    /** {@inheritDoc} */
    @Override
    public void createProfileTable(String tableName) {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        InMemoryProfileTable prior = tables.putIfAbsent(tableName, new InMemoryProfileTable(tableName));
        if (prior == null) {
            LOG.debug("Created profile table '{}'", tableName);
        } else {
            LOG.debug("Profile table '{}' already exists; createProfileTable is a no-op", tableName);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void removeProfileTable(String tableName) {
        if (tableName == null) {
            return;
        }
        InMemoryProfileTable removed = tables.remove(tableName);
        defaultSpecs.remove(tableName);
        eventSinks.remove(tableName);
        coalescingBuffers.remove(tableName);
        lifecycleQueues.remove(tableName);
        if (removed != null) {
            LOG.debug("Removed profile table '{}' ({} rows)", tableName, removed.getProfileCount());
        }
    }

    /** {@inheritDoc} */
    @Override
    public Set<String> getProfileTableNames() {
        return Collections.unmodifiableSet(tables.keySet());
    }

    // -----------------------------------------------------------------------
    // Phase 1 — Default profiles (§10.5)
    // -----------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public void setDefaultProfile(String tableName, Profile defaultProfile)
            throws UnrecognizedProfileTableNameException {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (defaultProfile == null) {
            throw new IllegalArgumentException("defaultProfile is required");
        }
        InMemoryProfileTable table = tables.get(tableName);
        if (table == null) {
            throw new UnrecognizedProfileTableNameException(
                    "No profile table named '" + tableName + "'");
        }
        ProfileID id = defaultProfile.getProfileID();
        if (id == null || !tableName.equals(id.getProfileTableName())) {
            throw new IllegalArgumentException(
                    "defaultProfile must be bound to table '" + tableName + "'");
        }
        Map<String, Object> snap = table.snapshotFields(id.getProfileName());
        defaultSpecs.put(tableName,
                new DefaultSpec(defaultProfile.getClass(),
                        snap != null ? new LinkedHashMap<>(snap) : new LinkedHashMap<>()));
        LOG.debug("Default profile set for table '{}' ({} fields, class={})",
                tableName, snap != null ? snap.size() : 0,
                defaultProfile.getClass().getSimpleName());
    }

    /** {@inheritDoc} */
    @Override
    public ProfileLocalObject createFromDefault(String tableName, String profileName)
            throws UnrecognizedProfileTableNameException,
                   ProfileAlreadyExistsException,
                   SLEEException {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (profileName == null) {
            throw new IllegalArgumentException("profileName is required");
        }
        InMemoryProfileTable table = tables.get(tableName);
        if (table == null) {
            throw new UnrecognizedProfileTableNameException(
                    "No profile table named '" + tableName + "'");
        }
        DefaultSpec spec = defaultSpecs.get(tableName);
        if (spec == null) {
            throw new IllegalStateException(
                    "No default profile registered for table '" + tableName
                    + "'. Call setDefaultProfile() before createFromDefault().");
        }
        ProfileLocalObject plo = createProfile(tableName, profileName, spec.profileClass);
        // Apply field snapshot from default.
        if (!spec.fieldSnapshot.isEmpty()) {
            for (Map.Entry<String, Object> entry : spec.fieldSnapshot.entrySet()) {
                try {
                    table.writeField(profileName, entry.getKey(), entry.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Row gone by race (extremely unlikely) — skip field.
                }
            }
        }
        LOG.debug("Created profile '{}' in table '{}' from default ({} fields cloned)",
                profileName, tableName, spec.fieldSnapshot.size());
        return plo;
    }

    // -----------------------------------------------------------------------
    // Phase 1 — Secondary indexes (§10.6, §10.8)
    // -----------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public void registerIndex(String tableName, String attributeName)
            throws UnrecognizedProfileTableNameException {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (attributeName == null) {
            throw new IllegalArgumentException("attributeName is required");
        }
        InMemoryProfileTable table = tables.get(tableName);
        if (table == null) {
            throw new UnrecognizedProfileTableNameException(
                    "No profile table named '" + tableName + "'");
        }
        table.registerIndex(attributeName);
        LOG.debug("Registered index on '{}' for table '{}'", attributeName, tableName);
    }

    /** {@inheritDoc} */
    @Override
    public Collection<ProfileLocalObject> findProfilesByAttribute(
            String tableName, String attributeName, Object value)
            throws UnrecognizedProfileTableNameException {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (attributeName == null) {
            throw new IllegalArgumentException("attributeName is required");
        }
        InMemoryProfileTable table = tables.get(tableName);
        if (table == null) {
            throw new UnrecognizedProfileTableNameException(
                    "No profile table named '" + tableName + "'");
        }
        // Throws IllegalStateException if not indexed (§10.6 — no silent scan).
        Set<String> profileNames = table.findByAttribute(attributeName, value);
        if (profileNames.isEmpty()) {
            return Collections.emptyList();
        }
        Collection<ProfileLocalObject> result = new ArrayList<>(profileNames.size());
        for (String name : profileNames) {
            ProfileLocalObject plo = table.getProfile(name);
            if (plo != null) {
                result.add(plo);
            }
        }
        return Collections.unmodifiableCollection(result);
    }

    // -----------------------------------------------------------------------
    // Phase 1 — Convenience query
    // -----------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public boolean profileExists(ProfileID id) {
        if (id == null) {
            return false;
        }
        InMemoryProfileTable table = tables.get(id.getProfileTableName());
        return table != null && table.containsProfile(id.getProfileName());
    }

    // -----------------------------------------------------------------------
    // Phase 1 — Atomic counter operations (C4)
    // -----------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public long addToLong(ProfileID id, String field, long delta)
            throws ProfileNotFoundException, UnrecognizedProfileTableNameException {
        InMemoryProfileTable table = requireTable(id);
        requireRow(table, id);
        long result = table.addToLong(id.getProfileName(), field, delta);
        queueEvent(id.getProfileTableName(), id.getProfileName(),
                new ProfileUpdatedEvent(id, field, System.currentTimeMillis()));
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public Object updateField(ProfileID id, String field, UnaryOperator<Object> fn)
            throws ProfileNotFoundException, UnrecognizedProfileTableNameException {
        if (fn == null) {
            throw new IllegalArgumentException("fn is required");
        }
        InMemoryProfileTable table = requireTable(id);
        requireRow(table, id);
        Object result = table.updateField(id.getProfileName(), field, fn);
        queueEvent(id.getProfileTableName(), id.getProfileName(),
                new ProfileUpdatedEvent(id, field, System.currentTimeMillis()));
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public boolean compareAndSetField(ProfileID id, String field, Object expect, Object update)
            throws ProfileNotFoundException, UnrecognizedProfileTableNameException {
        ProfileFieldTypes.assertAllowed(field, update);
        InMemoryProfileTable table = requireTable(id);
        requireRow(table, id);
        boolean swapped = table.compareAndSetField(id.getProfileName(), field, expect, update);
        if (swapped) {
            queueEvent(id.getProfileTableName(), id.getProfileName(),
                    new ProfileUpdatedEvent(id, field, System.currentTimeMillis()));
        }
        return swapped;
    }

    // -----------------------------------------------------------------------
    // Phase 1 — Profile lifecycle events (C5, §10.12)
    // -----------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public void enableEvents(String tableName, ProfileEventSink sink)
            throws UnrecognizedProfileTableNameException {
        if (tableName == null) {
            throw new IllegalArgumentException("tableName is required");
        }
        if (sink == null) {
            throw new IllegalArgumentException("sink is required");
        }
        if (!tables.containsKey(tableName)) {
            throw new UnrecognizedProfileTableNameException(
                    "No profile table named '" + tableName + "'");
        }
        eventSinks.put(tableName, sink);
        coalescingBuffers.put(tableName, new ConcurrentHashMap<String, ProfileUpdatedEvent>());
        lifecycleQueues.put(tableName, new java.util.concurrent.ConcurrentLinkedQueue<>());
        startDrainIfNeeded();
        LOG.debug("Profile events enabled for table '{}' sink={}", tableName,
                sink.getClass().getSimpleName());
    }

    /** {@inheritDoc} */
    @Override
    public void disableEvents(String tableName) {
        if (tableName != null) {
            eventSinks.remove(tableName);
            coalescingBuffers.remove(tableName);
            lifecycleQueues.remove(tableName);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 2 — ProfileFieldAccess (hot-path read/write routed through facility)
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * Hot-path read: resolves table and delegates to the in-memory row map.
     * O(1), non-blocking, no IO.
     */
    @Override
    public Object readField(ProfileID id, String fieldName) {
        if (id == null || fieldName == null) {
            return null;
        }
        InMemoryProfileTable table = tables.get(id.getProfileTableName());
        if (table == null) {
            return null;
        }
        return table.readField(id.getProfileName(), fieldName);
    }

    /**
     * {@inheritDoc}
     * Hot-path write: delegates to the in-memory row map, marks the profile
     * dirty for write-behind flushing when a durable store is installed,
     * and enqueues a C5 update event (non-blocking).
     *
     * <h3>C3 — per-delivery undo log</h3>
     * <p>When called during an event delivery (i.e. when
     * {@link ActivityContextTransactionRegistry#current()} returns an active
     * {@link SbbTransactionContext}), the old field value is captured and pushed
     * onto the transaction's undo stack <em>before</em> the write executes.
     * On rollback each write is reversed in LIFO order.
     * Writes outside an active delivery (bootstrap, RA threads, management)
     * are auto-committed immediately — no undo entry is created.
     */
    @Override
    public void writeField(ProfileID id, String fieldName, Object value) {
        if (id == null) {
            throw new IllegalStateException("id is required");
        }
        if (fieldName == null) {
            throw new IllegalStateException("fieldName is required");
        }
        InMemoryProfileTable table = tables.get(id.getProfileTableName());
        if (table == null) {
            throw new IllegalStateException("No profile table: " + id.getProfileTableName());
        }
        // C3 — capture old value for transactional undo before the write.
        SbbTransactionContext tx = ActivityContextTransactionRegistry.current();
        if (tx != null && tx.isActive()) {
            Object oldValue = table.readField(id.getProfileName(), fieldName);
            tx.recordProfileWrite(id, fieldName, oldValue);
        }
        // C7 validation + index maintenance happens inside table.writeField
        table.writeField(id.getProfileName(), fieldName, value);
        // Mark dirty for write-behind (O(1), non-blocking)
        if (durableStore != null) {
            dirtySet.add(id);
        }
        // C5 event notification (non-blocking coalescing buffer)
        notifyFieldWrite(id.getProfileTableName(), id.getProfileName(), fieldName);
    }

    // -----------------------------------------------------------------------
    // Phase 2 — Synchronous flush (real semantics)
    // -----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * In durable mode: drains the dirty set to the durable store synchronously.
     * In memory-only mode: no-op (correct — nothing to flush).
     */
    @Override
    public void flushSync(long timeout, TimeUnit unit) {
        if (durableStore == null) {
            return;
        }
        try {
            drainDirty();
        } catch (Exception ex) {
            LOG.error("flushSync failed: {}", ex.getMessage(), ex);
        }
    }

    /**
     * Flush dirty set synchronously, returning {@code true} on success.
     * Used by {@link com.microjainslee.core.MicroSleeContainer#stop()} for C1
     * ordering (quiesce → flush → clear).
     *
     * @param timeoutMs maximum time to wait (currently unused; drain is synchronous
     *                  and bounded by durable-store latency)
     * @return {@code true} when flush succeeded or there is no durable store,
     *         {@code false} when the flush threw an exception
     */
    public boolean flushSyncBoolean(long timeoutMs) {
        if (durableStore == null) {
            return true;
        }
        try {
            drainDirty();
            return true;
        } catch (Exception ex) {
            LOG.error("flushSyncBoolean failed: {}", ex.getMessage(), ex);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Package-private accessors used by the reflective accessor bridge.
    // -----------------------------------------------------------------------

    /**
     * Look up a table by name without going through the public
     * {@link #getProfileTable(String)} contract.
     */
    public InMemoryProfileTable findTableInternal(String tableName) {
        if (tableName == null) {
            return null;
        }
        return tables.get(tableName);
    }

    /**
     * C5 notification hook called by {@link com.microjainslee.api.ProfileAccessorInvoker#setValue}
     * after every successful CMP field write. Enqueues an update event into the coalescing
     * buffer; <em>never blocks</em> (O(1), non-blocking).
     *
     * @param tableName   the table that owns the profile
     * @param profileName the profile (primary-key) name that was mutated
     * @param fieldName   the CMP field that was written
     */
    public void notifyFieldWrite(String tableName, String profileName, String fieldName) {
        if (tableName == null || profileName == null) {
            return;
        }
        queueEvent(tableName, profileName,
                new ProfileUpdatedEvent(new ProfileID(tableName, profileName), fieldName,
                        System.currentTimeMillis()));
    }

    // -----------------------------------------------------------------------
    // Phase 2 — durable store installation + write-behind flusher lifecycle
    // -----------------------------------------------------------------------

    /**
     * Install the durable backend for write-behind persistence.
     * Starts the background flusher VT with the default 100 ms interval.
     * Safe to call before or after {@link com.microjainslee.core.MicroSleeContainer#start()}.
     *
     * @param store the durable backend; {@code null} disables durable mode and
     *              stops the flusher if running
     */
    public void setDurableStore(DurableProfileStore store) {
        this.durableStore = store;
        if (store != null) {
            startFlusher();
            LOG.info("DurableProfileStore installed: {}", store.getClass().getSimpleName());
        } else {
            stopFlusher();
            LOG.info("DurableProfileStore cleared; reverted to memory-only mode");
        }
    }

    /** @return {@code true} when a durable store is installed (write-behind active). */
    public boolean isDurableMode() {
        return durableStore != null;
    }

    /**
     * Start the write-behind flusher virtual thread if not already running.
     * Idempotent.
     */
    public void startFlusher() {
        if (durableStore == null) {
            return;
        }
        if (flusherRunning.compareAndSet(false, true)) {
            Thread t = Thread.ofVirtual()
                    .name("profile-wb-flusher")
                    .start(this::flusherLoop);
            flusherThread = t;
            LOG.debug("Write-behind flusher started (interval={}ms)", FLUSH_INTERVAL_MS);
        }
    }

    /**
     * Interrupt the flusher VT, wait for it to exit, then do a final drain.
     * Idempotent; safe to call from {@link #shutdown()}.
     */
    public void stopFlusher() {
        if (flusherRunning.compareAndSet(true, false)) {
            Thread t = flusherThread;
            flusherThread = null;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(2_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            // Final drain attempt after the loop exits
            try {
                drainDirty();
            } catch (Exception ex) {
                LOG.warn("Final drain on flusher stop failed: {}", ex.getMessage());
            }
            LOG.debug("Write-behind flusher stopped");
        }
    }

    /** Rehydrate all existing tables from the durable store (eager, C2). */
    public void rehydrateFromDurableStore() {
        if (durableStore == null) {
            return;
        }
        int rowsLoaded = 0;
        for (Map.Entry<String, InMemoryProfileTable> entry : tables.entrySet()) {
            String tableName = entry.getKey();
            InMemoryProfileTable table = entry.getValue();
            try {
                Map<String, Map<String, Object>> tableData = durableStore.loadTable(tableName);
                for (Map.Entry<String, Map<String, Object>> row : tableData.entrySet()) {
                    String profileName = row.getKey();
                    if (!table.containsProfile(profileName)) {
                        // Re-create a bare profile shell and restore field state
                        com.microjainslee.api.ProfileAbstractCmp shell =
                                new com.microjainslee.api.ProfileAbstractCmp() {};
                        shell.bindProfile(tableName, profileName);
                        if (table.put(profileName, shell)) {
                            for (Map.Entry<String, Object> field : row.getValue().entrySet()) {
                                table.writeField(profileName, field.getKey(), field.getValue());
                            }
                            rowsLoaded++;
                        }
                    }
                }
            } catch (UnsupportedOperationException ignored) {
                // store doesn't support loadTable — skip eager rehydration for this table
            } catch (Exception ex) {
                LOG.warn("Rehydration failed for table '{}': {}", tableName, ex.getMessage());
            }
        }
        if (rowsLoaded > 0) {
            LOG.info("Rehydrated {} profile rows from durable store", rowsLoaded);
        }
    }

    /** Flusher loop: sleeps FLUSH_INTERVAL_MS, drains dirty set, repeat. */
    private void flusherLoop() {
        while (flusherRunning.get()) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS);
                if (!dirtySet.isEmpty()) {
                    drainDirty();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                LOG.error("Write-behind flush cycle failed: {}", ex.getMessage(), ex);
                // Continue; next cycle will retry
            }
        }
    }

    /**
     * Drain the dirty set to the durable store.
     * <p>
     * Safety: each ID is removed from the dirty set <em>before</em> the store
     * call so that new writes arriving after we snapshot (but before we remove)
     * re-add to dirty and get picked up on the next cycle.  If the store throws,
     * the entire batch is re-added to dirty.
     */
    void drainDirty() {
        if (durableStore == null || dirtySet.isEmpty()) {
            return;
        }
        // Snapshot and optimistically remove
        List<ProfileID> batch = new ArrayList<>(dirtySet);
        for (ProfileID id : batch) {
            dirtySet.remove(id);
        }
        List<ProfileMutation> mutations = new ArrayList<>(batch.size());
        for (ProfileID id : batch) {
            InMemoryProfileTable table = tables.get(id.getProfileTableName());
            if (table == null || !table.containsProfile(id.getProfileName())) {
                mutations.add(ProfileMutation.delete(id));
            } else {
                Map<String, Object> snap = table.snapshotFields(id.getProfileName());
                if (snap == null) {
                    mutations.add(ProfileMutation.delete(id));
                } else {
                    mutations.add(ProfileMutation.upsert(id, snap));
                }
            }
        }
        if (mutations.isEmpty()) {
            return;
        }
        try {
            durableStore.storeBatch(mutations);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Write-behind: flushed {} mutations", mutations.size());
            }
        } catch (Exception ex) {
            // Re-queue for next cycle
            dirtySet.addAll(batch);
            throw ex;
        }
    }

    /**
     * Drop every table and release the {@link ProfileFieldStoreLocator} binding.
     * Tests and shutdown hooks call this for a clean state.
     * <p>
     * <b>C1 contract:</b> callers MUST call {@link #flushSyncBoolean(long)} first
     * and only invoke this method after a successful flush (or when in memory-only
     * mode where there is nothing to flush).
     */
    public void shutdown() {
        stopFlusher();
        stopDrain();
        tables.clear();
        defaultSpecs.clear();
        eventSinks.clear();
        coalescingBuffers.clear();
        lifecycleQueues.clear();
        dirtySet.clear();
        ProfileFieldStoreLocator.set(null);
        ProfileFieldStoreLocator.clearGlobal(this);
        LOG.debug("InMemoryProfileFacility shut down");
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private Profile instantiateProfile(Class<? extends Profile> profileClass) throws SLEEException {
        try {
            Constructor<? extends Profile> ctor = profileClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException nsme) {
            throw new SLEEException(
                    "Profile class " + profileClass.getName() + " must declare a no-arg constructor",
                    nsme);
        } catch (ReflectiveOperationException roe) {
            throw new SLEEException(
                    "Failed to instantiate profile class " + profileClass.getName()
                    + ": " + roe.getMessage(), roe);
        }
    }

    private InMemoryProfileTable requireTable(ProfileID id)
            throws UnrecognizedProfileTableNameException {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        InMemoryProfileTable table = tables.get(id.getProfileTableName());
        if (table == null) {
            throw new UnrecognizedProfileTableNameException(
                    "No profile table named '" + id.getProfileTableName() + "'");
        }
        return table;
    }

    private void requireRow(InMemoryProfileTable table, ProfileID id)
            throws ProfileNotFoundException {
        if (!table.containsProfile(id.getProfileName())) {
            throw new ProfileNotFoundException(id);
        }
    }

    // -----------------------------------------------------------------------
    // Phase 1 C5 — coalescing queue + VT drain
    // -----------------------------------------------------------------------

    /**
     * Enqueue {@code event} for delivery.
     *
     * <p>{@link ProfileUpdatedEvent} is coalesced per profileName (only the last
     * update between drain cycles is delivered). {@link ProfileAddedEvent} and
     * {@link ProfileRemovedEvent} are NEVER coalesced — they go into the
     * per-table lifecycle queue so quick create-then-remove sequences produce
     * both events.
     */
    private void queueEvent(String tableName, String profileName, SleeEvent event) {
        if (!eventSinks.containsKey(tableName)) {
            return;
        }
        if (event instanceof ProfileUpdatedEvent) {
            ConcurrentHashMap<String, ProfileUpdatedEvent> buf = coalescingBuffers.get(tableName);
            if (buf != null) {
                buf.put(profileName, (ProfileUpdatedEvent) event);
            }
        } else {
            // Add/Remove: always deliver, FIFO, never coalesced.
            java.util.Queue<SleeEvent> q = lifecycleQueues.get(tableName);
            if (q != null) {
                q.add(event);
            }
        }
        // Signal drain thread — bounded to avoid permit accumulation.
        if (drainTrigger.availablePermits() < 1024) {
            drainTrigger.release();
        }
    }

    private void startDrainIfNeeded() {
        if (drainRunning.compareAndSet(false, true)) {
            Thread t = Thread.ofVirtual()
                    .name("profile-event-drain")
                    .start(this::drainLoop);
            drainThread.set(t);
        }
    }

    private void stopDrain() {
        if (drainRunning.compareAndSet(true, false)) {
            drainTrigger.release(); // unblock parked drain thread
            Thread t = drainThread.getAndSet(null);
            if (t != null) {
                t.interrupt();
            }
        }
    }

    /** VT drain loop: blocks on trigger, drains all buffers, repeats until stopped. */
    private void drainLoop() {
        while (drainRunning.get()) {
            try {
                drainTrigger.tryAcquire(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            drainAllBuffers();
        }
        drainAllBuffers(); // final drain on shutdown
    }

    private void drainAllBuffers() {
        for (Map.Entry<String, ProfileEventSink> entry : eventSinks.entrySet()) {
            String tableName = entry.getKey();
            ProfileEventSink sink = entry.getValue();

            // 1. Lifecycle events (Add/Remove): FIFO, never coalesced.
            java.util.Queue<SleeEvent> lcq = lifecycleQueues.get(tableName);
            if (lcq != null) {
                SleeEvent lcEvt;
                while ((lcEvt = lcq.poll()) != null) {
                    try {
                        dispatchEvent(sink, lcEvt);
                    } catch (Exception ex) {
                        LOG.warn("ProfileEventSink threw during lifecycle drain for {}: {}",
                                tableName, ex.getMessage());
                    }
                }
            }

            // 2. Coalesced update events (only last update per profile per cycle).
            ConcurrentHashMap<String, ProfileUpdatedEvent> buf = coalescingBuffers.get(tableName);
            if (buf != null && !buf.isEmpty()) {
                for (String profileName : new ArrayList<>(buf.keySet())) {
                    ProfileUpdatedEvent evt = buf.remove(profileName);
                    if (evt == null) {
                        continue;
                    }
                    try {
                        sink.onProfileUpdated(evt);
                    } catch (Exception ex) {
                        LOG.warn("ProfileEventSink threw during update drain for {}/{}: {}",
                                tableName, profileName, ex.getMessage());
                    }
                }
            }
        }
    }

    private static void dispatchEvent(ProfileEventSink sink, SleeEvent event) {
        if (event instanceof ProfileAddedEvent) {
            sink.onProfileAdded((ProfileAddedEvent) event);
        } else if (event instanceof ProfileUpdatedEvent) {
            sink.onProfileUpdated((ProfileUpdatedEvent) event);
        } else if (event instanceof ProfileRemovedEvent) {
            sink.onProfileRemoved((ProfileRemovedEvent) event);
        }
    }
}
