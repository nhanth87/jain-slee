/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.profile.infinispan;

import com.microjainslee.api.DurableProfileStore;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileMutation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Phase 4 — durable {@link DurableProfileStore} backed by an embedded
 * Infinispan LOCAL cache persisted through a Soft-Index File Store (SIFS).
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li>One Infinispan {@link Cache} per profile <b>table</b>, keyed by profile
 *       name, valued by the CMP field map:
 *       {@code Cache<String /*profileName*&#47;, HashMap<String,Object> /*fields*&#47;>}.</li>
 *   <li>{@link CacheMode#LOCAL} — no JGroups transport. Cluster replication is
 *       a later bonus; v1 is single-JVM durability only.</li>
 *   <li>Each cache has a {@code SoftIndexFileStore} rooted under
 *       {@code baseDir/<table>/{data,index}} so rows survive a JVM restart.
 *       {@code passivation=false} (the profile <em>is</em> the hot store; SIFS
 *       is a pure durability layer) and {@code preload=true} so a table can be
 *       rehydrated eagerly on start (Contract C2).</li>
 * </ul>
 *
 * <h2>Marshalling (Contract C7)</h2>
 * The cache manager uses a {@link JavaSerializationMarshaller} with an
 * allow-list restricted to {@code java.*} classes and array types. Profile
 * field values are JDK-only by contract, so marshalling never references an
 * application class and therefore survives a Quarkus live-reload classloader
 * swap without {@code ClassCastException}.
 *
 * <h2>Blocking-IO rule (Contract C6)</h2>
 * Every method here may perform disk IO and MUST only be invoked from the
 * write-behind flusher / lifecycle threads — never from an SBB event handler or
 * an RA event-loop thread.
 *
 * <p>Instances are thread-safe. Call {@link #close()} (or {@link #stop()}) on
 * shutdown to flush and release the file store.
 *
 * @author Tran Nhan (nhanth87)
 * @since 1.2.0
 */
public final class InfinispanProfileStore implements DurableProfileStore, AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(InfinispanProfileStore.class);

    /** Allow-list regexps for the JDK-only field-value contract (C7). */
    private static final String ALLOW_JAVA = "java\\..*";   // java.util.*, java.lang.*, ...
    private static final String ALLOW_ARRAY = "\\[.*";       // byte[] => "[B", String[] => "[Ljava..."

    private final Path baseDir;
    private final boolean syncWrites;
    private final EmbeddedCacheManager cacheManager;
    private final ConcurrentMap<String, Cache<String, HashMap<String, Object>>> tableCaches =
            new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * Create a store rooted at {@code baseDir} with synchronous writes enabled
     * (strongest durability). Equivalent to
     * {@code new InfinispanProfileStore(baseDir, null, true)}.
     *
     * @param baseDir root directory for all per-table SIFS files; created on
     *                demand. Must not be {@code null}.
     */
    public InfinispanProfileStore(Path baseDir) {
        this(baseDir, null, true);
    }

    /**
     * Create a store.
     *
     * @param baseDir    root directory for all per-table SIFS files; created on
     *                   demand. Must not be {@code null}.
     * @param nodeName   cache-manager node name (JMX / log id); a stable value
     *                   derived from {@code baseDir} is used when {@code null}
     *                   or blank
     * @param syncWrites when {@code true} the SIFS flushes each write before
     *                   returning (durable RPO=0 on clean and unclean stop);
     *                   when {@code false} writes are batched and flushed on a
     *                   clean {@link #close()} only
     */
    public InfinispanProfileStore(Path baseDir, String nodeName, boolean syncWrites) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir").toAbsolutePath();
        this.syncWrites = syncWrites;
        String node = (nodeName == null || nodeName.isBlank())
                ? "profile-store-" + Integer.toHexString(this.baseDir.hashCode())
                : nodeName;
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        global.nonClusteredDefault();
        global.transport().nodeName(node);
        global.cacheManagerName("micro-jainslee-profiles-" + node);
        // JDK-only Java serialization; no protostream schema, no app classes.
        global.serialization()
                .marshaller(new JavaSerializationMarshaller())
                .allowList()
                .addRegexp(ALLOW_JAVA)
                .addRegexp(ALLOW_ARRAY);
        this.cacheManager = new DefaultCacheManager(global.build());
        LOG.info("InfinispanProfileStore started: baseDir={} node={} syncWrites={}",
                this.baseDir, node, syncWrites);
    }

    // ------------------------------------------------------------------
    // ProfileStore
    // ------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public Map<String, Object> loadFields(ProfileID id) {
        Objects.requireNonNull(id, "id");
        HashMap<String, Object> stored = cache(id.getProfileTableName()).get(id.getProfileName());
        // SPI contract: null when the row is not present in this store.
        return stored == null ? null : new HashMap<>(stored);
    }

    /** {@inheritDoc} */
    @Override
    public void storeFields(ProfileID id, Map<String, Object> fields) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(fields, "fields");
        cache(id.getProfileTableName()).put(id.getProfileName(), copyOf(fields));
    }

    /** {@inheritDoc} */
    @Override
    public void remove(ProfileID id) {
        Objects.requireNonNull(id, "id");
        cache(id.getProfileTableName()).remove(id.getProfileName());
    }

    // ------------------------------------------------------------------
    // DurableProfileStore
    // ------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public void storeBatch(List<ProfileMutation> mutations) {
        Objects.requireNonNull(mutations, "mutations");
        for (ProfileMutation m : mutations) {
            ProfileID id = m.getId();
            switch (m.getType()) {
                case UPSERT -> cache(id.getProfileTableName())
                        .put(id.getProfileName(), copyOf(m.getFields()));
                case DELETE -> cache(id.getProfileTableName())
                        .remove(id.getProfileName());
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("storeBatch applied {} mutations", mutations.size());
        }
    }

    /**
     * List the profile (row) names currently persisted for a table.
     *
     * <p>Implementation-specific extension used for eager rehydration
     * (Contract C2). {@code preload=true} keeps the in-memory key set
     * consistent with the on-disk store.
     *
     * @param tableName profile table name (must not be {@code null})
     * @return an unmodifiable set of profile names; empty when none. Never
     *         {@code null}.
     */
    public Set<String> listProfileNames(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        // preload=true keeps the in-memory key set consistent with the store.
        return Set.copyOf(cache(tableName).keySet());
    }

    /**
     * Load every persisted row of a table in one shot (eager rehydration, C2).
     *
     * @param tableName profile table name (must not be {@code null})
     * @return a map of {@code profileName -> fieldMap}; empty when the table has
     *         no persisted rows. Never {@code null}.
     */
    @Override
    public Map<String, Map<String, Object>> loadTable(String tableName) {
        Objects.requireNonNull(tableName, "tableName");
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<String, HashMap<String, Object>> e : cache(tableName).entrySet()) {
            out.put(e.getKey(), new HashMap<>(e.getValue()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Flush and stop the underlying cache manager and its file stores. Safe to
     * call more than once; safe to call from a shutdown hook.
     */
    public void stop() {
        close();
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (cacheManager.getStatus() == ComponentStatus.RUNNING) {
                // Stops every cache first, flushing each SIFS to disk.
                cacheManager.stop();
            }
        } catch (RuntimeException re) {
            LOG.warn("InfinispanProfileStore stop failed: {}", re.getMessage());
        } finally {
            tableCaches.clear();
        }
        LOG.info("InfinispanProfileStore stopped: baseDir={}", baseDir);
    }

    /**
     * @return the underlying Infinispan {@link EmbeddedCacheManager}. Exposed
     *         for advanced integration tests; embedders should prefer the
     *         {@link DurableProfileStore} contract.
     */
    public EmbeddedCacheManager getCacheManager() {
        return cacheManager;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Cache<String, HashMap<String, Object>> cache(String tableName) {
        if (closed) {
            throw new IllegalStateException("InfinispanProfileStore is closed");
        }
        Objects.requireNonNull(tableName, "tableName");
        return tableCaches.computeIfAbsent(tableName, this::defineTableCache);
    }

    private Cache<String, HashMap<String, Object>> defineTableCache(String tableName) {
        String cacheName = "profile-table-" + tableName;
        String safe = sanitize(tableName);
        String dataDir = baseDir.resolve(safe).resolve("data").toString();
        String indexDir = baseDir.resolve(safe).resolve("index").toString();

        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.clustering().cacheMode(CacheMode.LOCAL);
        cb.persistence()
                // Profile is the hot store already; SIFS is a durability layer,
                // never an eviction target — so passivation stays off.
                .passivation(false)
                .addSoftIndexFileStore()
                .dataLocation(dataDir)
                .indexLocation(indexDir)
                .shared(false)
                // Rehydrate the whole table into memory on start (Contract C2)
                // and keep keySet()/entrySet() consistent with disk.
                .preload(true)
                .syncWrites(syncWrites);

        cacheManager.defineConfiguration(cacheName, cb.build());
        Cache<String, HashMap<String, Object>> cache = cacheManager.getCache(cacheName);
        LOG.debug("Defined durable profile cache '{}' data={} index={}", cacheName, dataDir, indexDir);
        return cache;
    }

    /** Store field maps as a concrete, {@link java.io.Serializable} HashMap. */
    private static HashMap<String, Object> copyOf(Map<String, Object> fields) {
        return new HashMap<>(fields);
    }

    /** Map a table name to a filesystem-safe directory component. */
    private static String sanitize(String tableName) {
        StringBuilder sb = new StringBuilder(tableName.length());
        for (int i = 0; i < tableName.length(); i++) {
            char c = tableName.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '-' || c == '_') ? c : '_');
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }
}
