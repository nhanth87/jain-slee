/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import com.microjainslee.api.Sbb;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.core.VirtualThreadSbbEntityPool;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Production P2.3 / P3 — Distributed SBB Entity Pool with selective HA
 * checkpoint / replication.
 *
 * <p>This class is a <b>composition</b>-based wrapper around
 * {@link VirtualThreadSbbEntityPool}. Hot path create/delete stays local:
 * ISPN is touched only via {@link #checkpoint(String)} (debounce + generation)
 * or legacy {@code jainslee.sbb.checkpoint.on-release=true}.
 *
 * <ol>
 *   <li><b>Checkpoint.</b> Explicit {@link #checkpoint} (or RA/dialog boundary)
 *       builds a {@link SbbEntitySnapshot} (heap {@code @CmpField} + profile
 *       refs) and puts it into {@code "sbb-entity-state"} ({@link CacheMode#REPL_ASYNC}).</li>
 *   <li><b>Release.</b> Local pool release; if the entity was ever checkpointed,
 *       asynchronously remove the ISPN entry (tombstone). No put on release
 *       unless legacy on-release is enabled.</li>
 *   <li><b>Reconstruct on acquire.</b> Local miss → ISPN get → applySnapshot.</li>
 * </ol>
 *
 * <h2>Why composition and not inheritance</h2>
 * {@link VirtualThreadSbbEntityPool} is declared {@code final} to
 * prevent embedders from accidentally depending on its concrete
 * surface. The wrapper preserves the same method names so
 * {@code MicroSleeContainer.eventRouter.bindSbbEntityPool(...)} can
 * be pointed at this instance without code changes &mdash; the
 * container only ever calls {@code acquire} / {@code release} /
 * {@code releaseById} / {@code findEntity} / {@code size} /
 * {@code getMin} / {@code getMax} / {@code shutdown}.
 *
 * <h2>Wire-in</h2>
 * The class is loaded reflectively by
 * {@code MicroSleeContainer.bindDistributedSbbPool(Object)}; the
 * kernel keeps its compile-time boundary with {@code jainslee-cluster}.
 * The reflective contract requires a {@code (int, int, boolean, ClusterManager)}
 * constructor &mdash; the same constructor exposed below.
 *
 * <h2>Marshalling</h2>
 * CMP field values stored in {@link SbbEntitySnapshot} must be
 * {@link java.io.Serializable} and match {@link MarshallingAllowList}
 * ({@code com.microjainslee.*}, {@code com.example.*}, {@code java.*}, arrays).
 * {@link #takeSnapshot} validates each field before the snapshot is built;
 * non-conforming values fail fast with {@link IllegalArgumentException}.
 * SBBs that hold non-serializable state (sockets, buffers, …) must keep those
 * fields out of {@code @CmpField} accessors.
 */
public final class DistributedSbbEntityPool {

    /** Name of the Infinispan cache that stores per-entity CMP snapshots. */
    public static final String CACHE_NAME = "sbb-entity-state";

    private static final Logger LOG = LogManager.getLogger(DistributedSbbEntityPool.class);

    private final VirtualThreadSbbEntityPool delegate;
    private final Cache<String, SbbEntitySnapshot> stateCache;
    private final ClusterManager clusterManager;
    private final SbbCheckpointConfig checkpointConfig;
    /** Entities that have successfully checkpointed at least once. */
    private final ConcurrentMap<String, Boolean> checkpointedIds = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastCheckpointNanos = new ConcurrentHashMap<>();

    /**
     * Build a distributed SBB entity pool.
     *
     * @param min        minimum number of idle {@code VirtualThreadSbbEntityPool} slots
     *                   (must be {@code >= 0})
     * @param max        maximum total number of in-flight entities
     *                   (must be {@code >= 1})
     * @param perVirtualThread when {@code true} the underlying pool uses a
     *                         virtual-thread-per-task executor; when
     *                         {@code false} it falls back to a cached
     *                         platform-thread pool
     * @param clusterMgr the cluster manager that owns the Infinispan cache
     *                   (must be non-null)
     */
    public DistributedSbbEntityPool(int min, int max, boolean perVirtualThread,
                                    ClusterManager clusterMgr) {
        this(min, max, perVirtualThread, clusterMgr, SbbCheckpointConfig.fromSystemProperties());
    }

    public DistributedSbbEntityPool(int min, int max, boolean perVirtualThread,
                                    ClusterManager clusterMgr,
                                    SbbCheckpointConfig checkpointConfig) {
        Objects.requireNonNull(clusterMgr, "clusterMgr");
        this.clusterManager = clusterMgr;
        this.checkpointConfig = checkpointConfig == null
                ? SbbCheckpointConfig.fromSystemProperties()
                : checkpointConfig;
        this.delegate = new VirtualThreadSbbEntityPool(min, max, perVirtualThread);
        this.stateCache = clusterMgr.<String, SbbEntitySnapshot>getCache(
                CACHE_NAME, CacheMode.REPL_ASYNC);
        LOG.info("DistributedSbbEntityPool ready: min={} max={} perVT={} cache={} mode={} node={} "
                        + "persistOnRelease={} debounceMs={}",
                min, max, perVirtualThread, CACHE_NAME,
                stateCache.getCacheConfiguration().clustering().cacheMode(),
                clusterMgr.getNodeId(),
                this.checkpointConfig.persistOnRelease(),
                this.checkpointConfig.debounceMs());
    }

    /** @return the cluster manager that owns the {@code sbb-entity-state} cache. */
    public ClusterManager getClusterManager() {
        return clusterManager;
    }

    /** @return the Infinispan cache that holds per-entity snapshots. */
    public Cache<String, SbbEntitySnapshot> getStateCache() {
        return stateCache;
    }

    // ---------------------------------------------------------------
    // Local-pool façade
    // ---------------------------------------------------------------

    public VirtualThreadSbbEntityPool.SbbEntity acquire(String sbbId, Supplier<Sbb> factory) {
        Objects.requireNonNull(sbbId, "sbbId");
        Objects.requireNonNull(factory, "factory");
        // 1) Local first.
        VirtualThreadSbbEntityPool.SbbEntity local = delegate.findEntity(sbbId);
        if (local != null) {
            return local;
        }
        // 2) Try cluster cache; reconstruct if found.
        SbbEntitySnapshot snapshot = stateCache.get(sbbId);
        if (snapshot != null) {
            Sbb sbb = factory.get();
            applySnapshot(snapshot, sbb);
            // The 3-arg acquire(sbbId, entityId, sbb) overload is
            // the only path on VirtualThreadSbbEntityPool that takes
            // a pre-built Sbb instance.
            return delegate.acquire(sbbId, 0L, sbb);
        }
        // 3) Cold path - local pool creates a fresh entity.
        return delegate.acquire(sbbId, factory);
    }

    public VirtualThreadSbbEntityPool.SbbEntity acquire(String sbbId, long entityId, Sbb sbb) {
        Objects.requireNonNull(sbbId, "sbbId");
        Objects.requireNonNull(sbb, "sbb");
        return delegate.acquire(sbbId, entityId, sbb);
    }

    public void release(VirtualThreadSbbEntityPool.SbbEntity entity) {
        if (entity == null) {
            return;
        }
        if (checkpointConfig.persistOnRelease()) {
            persistSnapshot(entity, null);
        } else {
            invalidateIfCheckpointed(entity.getSbbId());
        }
        clearLocalCheckpointMeta(entity.getSbbId());
        delegate.release(entity);
    }

    /**
     * Persist a snapshot then release by id. Mirrors
     * {@link VirtualThreadSbbEntityPool#releaseById(String)}.
     */
    public void releaseById(String sbbId) {
        if (sbbId == null) {
            return;
        }
        VirtualThreadSbbEntityPool.SbbEntity entity = delegate.findEntity(sbbId);
        if (checkpointConfig.persistOnRelease()) {
            if (entity != null) {
                persistSnapshot(entity, null);
            }
        } else {
            invalidateIfCheckpointed(sbbId);
        }
        clearLocalCheckpointMeta(sbbId);
        delegate.releaseById(sbbId);
    }

    /**
     * Explicit HA checkpoint (debounce + generation). Safe to call from
     * app code or TCAP Begin/Continue boundary. No-op when entity is not
     * local.
     *
     * @return {@code true} when a snapshot was written (or debounce skipped
     *         a redundant write after a prior successful checkpoint)
     */
    public boolean checkpoint(String sbbId) {
        return checkpoint(sbbId, null);
    }

    /**
     * @param profileRefs optional {@code table/name} refs (ProfileFacility
     *                    loads data on peer hydrate)
     */
    public boolean checkpoint(String sbbId, Set<String> profileRefs) {
        if (sbbId == null) {
            return false;
        }
        VirtualThreadSbbEntityPool.SbbEntity entity = delegate.findEntity(sbbId);
        if (entity == null) {
            return false;
        }
        long now = System.nanoTime();
        long debounceNs = checkpointConfig.debounceMs() * 1_000_000L;
        if (debounceNs > 0L) {
            Long last = lastCheckpointNanos.get(sbbId);
            if (last != null && (now - last) < debounceNs && checkpointedIds.containsKey(sbbId)) {
                return true; // coalesced
            }
        }
        boolean ok = persistSnapshot(entity, profileRefs);
        if (ok) {
            lastCheckpointNanos.put(sbbId, now);
        }
        return ok;
    }

    public boolean wasCheckpointed(String sbbId) {
        return sbbId != null && checkpointedIds.containsKey(sbbId);
    }

    public SbbCheckpointConfig checkpointConfig() {
        return checkpointConfig;
    }

    public VirtualThreadSbbEntityPool.SbbEntity findEntity(String sbbId) {
        return delegate.findEntity(sbbId);
    }

    public int size() {
        return delegate.size();
    }

    public int getMin() {
        return delegate.getMin();
    }

    public int getMax() {
        return delegate.getMax();
    }

    public int idleSlotCount() {
        return delegate.idleSlotCount();
    }

    public void shutdown() {
        delegate.shutdown();
    }

    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    // ---------------------------------------------------------------
    // Snapshot logic
    // ---------------------------------------------------------------

    /**
     * Reflectively scan the {@code @CmpField} annotated accessors on
     * the SBB instance and build a {@link SbbEntitySnapshot} for the
     * given entity id.
     *
     * @param sbbId the SBB entity id (must be non-null)
     * @param sbb   the SBB instance to read from (must be non-null)
     * @return a fully-populated snapshot suitable for serialization
     */
    public SbbEntitySnapshot takeSnapshot(String sbbId, Sbb sbb) {
        return buildSnapshot(sbbId, sbb, Collections.emptySet());
    }

    public SbbEntitySnapshot takeSnapshot(String sbbId, Sbb sbb, Set<String> profileRefs) {
        return buildSnapshot(sbbId, sbb, profileRefs == null ? Collections.emptySet() : profileRefs);
    }

    private SbbEntitySnapshot buildSnapshot(String sbbId, Sbb sbb, Set<String> profileRefs) {
        Objects.requireNonNull(sbbId, "sbbId");
        Objects.requireNonNull(sbb, "sbb");
        Class<?> klass = sbb.getClass();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Method m : findCmpAccessors(klass)) {
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                continue;
            }
            CmpField ann = m.getAnnotation(CmpField.class);
            try {
                m.setAccessible(true);
                Object value = m.invoke(sbb);
                MarshallingAllowList.assertMarshallable("@CmpField '" + ann.value() + "'", value);
                values.put(ann.value(), value);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException(
                        "Failed to read @CmpField '" + ann.value()
                                + "' via " + m + " on " + klass.getName(), ex);
            }
        }
        return new SbbEntitySnapshot(
                klass.getName(),
                sbbId,
                values,
                resolveAttachedAciNames(sbbId),
                System.currentTimeMillis(),
                nextGeneration(sbbId),
                profileRefs);
    }

    /**
     * Reflectively write {@code snapshot.cmpFieldValues} back to the
     * {@code @CmpField} annotated setter accessors on {@code target}.
     *
     * <p>The {@code snapshot.sbbClassFqn} is sanity-checked against
     * {@code target.getClass().getName()}; a mismatch raises
     * {@link IllegalArgumentException} so we never silently cross-
     * populate unrelated SBB classes.
     *
     * <p><b>Sprint S9.2.</b> After the CMP fields have been written,
     * the freshly reconstructed SBB is re-attached to every ACI named
     * in {@code snapshot.attachedAciNames} so the routing topology is
     * restored to the same set the entity had on the producing node.
     *
     * @param snapshot the snapshot to apply (must be non-null)
     * @param target   the SBB instance to write into (must be non-null)
     */
    public void applySnapshot(SbbEntitySnapshot snapshot, Sbb target) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(target, "target");
        if (!snapshot.getSbbClassFqn().equals(target.getClass().getName())) {
            throw new IllegalArgumentException(
                    "Snapshot class mismatch: snapshot=" + snapshot.getSbbClassFqn()
                            + " target=" + target.getClass().getName());
        }
        Class<?> klass = target.getClass();
        Map<String, Method> settersByName = new LinkedHashMap<>();
        for (Method m : findCmpAccessors(klass)) {
            CmpField ann = m.getAnnotation(CmpField.class);
            if (m.getParameterCount() == 1) {
                settersByName.put(ann.value(), m);
            }
        }
        for (Map.Entry<String, Object> e : snapshot.getCmpFieldValues().entrySet()) {
            String fieldName = e.getKey();
            Object value = e.getValue();
            Method setter = settersByName.get(fieldName);
            if (setter == null) {
                LOG.warn("applySnapshot: no setter for @CmpField '{}' on {}; skipping",
                        fieldName, klass.getName());
                continue;
            }
            try {
                setter.setAccessible(true);
                setter.invoke(target, value);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new IllegalStateException(
                        "Failed to write @CmpField '" + fieldName
                                + "' via " + setter + " on " + klass.getName(), ex);
            }
        }
        // Sprint S9.2 — re-attach the reconstructed SBB to every ACI it
        // was attached to on the producing node. The microSleeContainer
        // reference is supplied through setContainer() at bind time so
        // the cluster module does not pull a compile-time edge to
        // jainslee-core. When no container is bound (the off-line test
        // path) the re-attach is skipped.
        Object container = containerRef.get();
        if (container != null) {
            reattachSbb(container, snapshot.getSbbId(), snapshot.getAttachedAciNames());
        } else if (!snapshot.getAttachedAciNames().isEmpty()) {
            LOG.debug("applySnapshot: no MicroSleeContainer bound; skipping ACI re-attach for sbbId={}",
                    snapshot.getSbbId());
        }
    }

    /**
     * Persist a snapshot of {@code entity} into the cluster cache.
     *
     * @return {@code true} on success
     */
    private boolean persistSnapshot(VirtualThreadSbbEntityPool.SbbEntity entity, Set<String> profileRefs) {
        try {
            SbbEntitySnapshot snap = profileRefs == null
                    ? takeSnapshot(entity.getSbbId(), entity.getSbb())
                    : takeSnapshot(entity.getSbbId(), entity.getSbb(), profileRefs);
            stateCache.put(entity.getSbbId(), snap);
            checkpointedIds.put(entity.getSbbId(), Boolean.TRUE);
            return true;
        } catch (RuntimeException re) {
            LOG.warn("persistSnapshot('{}') failed: {}", entity.getSbbId(), re.toString());
            return false;
        }
    }

    private void invalidateIfCheckpointed(String sbbId) {
        if (!checkpointedIds.containsKey(sbbId)) {
            return;
        }
        try {
            stateCache.remove(sbbId);
        } catch (RuntimeException re) {
            LOG.warn("invalidateIfCheckpointed('{}') failed: {}", sbbId, re.toString());
        }
    }

    private void clearLocalCheckpointMeta(String sbbId) {
        checkpointedIds.remove(sbbId);
        generations.remove(sbbId);
        lastCheckpointNanos.remove(sbbId);
    }

    private long nextGeneration(String sbbId) {
        return generations.computeIfAbsent(sbbId, id -> new AtomicLong(0L)).incrementAndGet();
    }

    /**
     * Enumerate the {@code @CmpField} annotated accessor methods on
     * {@code klass} (getters + setters, declared up to {@link Object}).
     * Iteration order is JVM-dependent but stable for a given class
     * loader, which is good enough for a deterministic snapshot.
     */
    private static List<Method> findCmpAccessors(Class<?> klass) {
        List<Method> result = new ArrayList<>();
        Class<?> cursor = klass;
        while (cursor != null && cursor != Object.class) {
            for (Method m : cursor.getDeclaredMethods()) {
                if (m.isAnnotationPresent(CmpField.class)) {
                    result.add(m);
                }
            }
            cursor = cursor.getSuperclass();
        }
        return result;
    }

    /**
     * Sprint S9.4 — supply the {@link com.microjainslee.core.MicroSleeContainer}
     * reference so {@link #takeSnapshot} can populate the
     * {@link SbbEntitySnapshot#getAttachedAciNames()} field with the
     * ACI names the entity is currently attached to.
     *
     * <p>Type-erased on purpose: the cluster module does not depend on
     * jainslee-core at compile time. The container is invoked through
     * reflection by {@link #resolveAttachedAciNames} and
     * {@link #reattachSbb}. Stored in an {@link java.util.concurrent.atomic.AtomicReference}
     * so the kernel can rebind it across start/stop cycles without
     * breaking in-flight snapshots.
     */
    private final java.util.concurrent.atomic.AtomicReference<Object> containerRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Bind the live container reference so subsequent
     * {@code takeSnapshot} calls populate
     * {@link SbbEntitySnapshot#getAttachedAciNames()}. Pass {@code null}
     * to clear (the snapshot path will then record an empty set).
     *
     * @param container the live {@code MicroSleeContainer}, or {@code null}
     */
    public void bindContainer(Object container) {
        containerRef.set(container);
        if (container != null) {
            LOG.debug("DistributedSbbEntityPool: container reference bound");
        } else {
            LOG.debug("DistributedSbbEntityPool: container reference cleared");
        }
    }

    /**
     * @return the currently bound container reference, or {@code null}
     *         when no container has been wired (off-line test path).
     */
    public Object getContainer() {
        return containerRef.get();
    }

    /**
     * Sprint S9.4 — reflectively invoke
     * {@code MicroSleeContainer.getAttachedAciNames(sbbId)} and return
     * the result. Returns an empty set when no container is bound or
     * the reflection call fails; a snapshot failure must never escape
     * because this is on the entity-release hot path.
     */
    private Set<String> resolveAttachedAciNames(String sbbId) {
        Object container = containerRef.get();
        if (container == null) {
            return new LinkedHashSet<>();
        }
        try {
            Object result = container.getClass()
                    .getMethod("getAttachedAciNames", String.class)
                    .invoke(container, sbbId);
            if (result instanceof Set) {
                return new LinkedHashSet<String>((Set<String>) result);
            }
            return new LinkedHashSet<>();
        } catch (Exception ex) {
            LOG.debug("resolveAttachedAciNames('{}') failed: {}", sbbId, ex.toString());
            return new LinkedHashSet<>();
        }
    }

    /**
     * Sprint S9.2 — drive the
     * {@code MicroSleeContainer.reattachToAcis(SbbLocalObject, Set<String>)}
     * API reflectively so this class stays free of a compile-time
     * dependency on jainslee-core. A failure is logged at WARN and
     * never thrown — the snapshot path is best-effort by design.
     *
     * @param container the kernel (must be non-null)
     * @param sbbId     the SBB entity id (used for diagnostics only)
     * @param aciNames  the ACI names to re-attach to (may be empty)
     */
    private void reattachSbb(Object container, String sbbId, Set<String> aciNames) {
        if (aciNames == null || aciNames.isEmpty()) {
            return;
        }
        try {
            Class<?> sbbLocalObjectCls = Class.forName("com.microjainslee.api.SbbLocalObject");
            Object localObject = container.getClass()
                    .getMethod("getSbbLocalObject", String.class)
                    .invoke(container, sbbId);
            if (localObject == null) {
                LOG.warn("applySnapshot: no SbbLocalObject registered for sbbId={} - skipping re-attach",
                        sbbId);
                return;
            }
            Object count = container.getClass()
                    .getMethod("reattachToAcis", sbbLocalObjectCls, Set.class)
                    .invoke(container, localObject, new LinkedHashSet<String>(aciNames));
            LOG.info("[Cluster] re-attached sbbId={} to {} ACI(s)", sbbId, count);
        } catch (Exception ex) {
            LOG.warn("applySnapshot: re-attach threw for sbbId={}: {}", sbbId, ex.toString());
        }
    }
}
