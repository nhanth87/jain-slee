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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Production P2.3 - Distributed SBB Entity Pool with state snapshot /
 * replication.
 *
 * <p>This class is a <b>composition</b>-based wrapper around
 * {@link VirtualThreadSbbEntityPool}. The pool class in
 * {@code jainslee-core} is declared {@code final} so we cannot extend
 * it; we therefore hold it as a private final field and forward the
 * lifecycle / acquire / release calls to it. The two extra
 * responsibilities of this class are:
 *
 * <ol>
 *   <li><b>Snapshot on release.</b> When an entity is released from
 *       the local pool we reflectively scan its {@link CmpField}
 *       accessor methods, build a {@link SbbEntitySnapshot}, and push
 *       it into the {@code "sbb-entity-state"} Infinispan cache. The
 *       cache is opened in {@link CacheMode#REPL_ASYNC} so writes are
 *       replicated asynchronously to peer nodes - we never want a
 *       hot-path release call to block on a JGroups round-trip.</li>
 *   <li><b>Reconstruct on acquire.</b> When an entity is requested on
 *       a node that does not have it locally we first consult the
 *       cluster cache; if a snapshot is present we instantiate a
 *       fresh SBB through the factory and reflectively apply the
 *       snapshot back to it (writing the {@code @CmpField} accessors)
 *       before handing the entity out. If the cache has no snapshot
 *       the call falls back to the standard local-pool acquisition
 *       path.</li>
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
 * <h2>Marshalling caveat</h2>
 * CMP field values stored in {@link SbbEntitySnapshot} must be
 * {@link java.io.Serializable} because the default Infinispan
 * marshaller is Java serialization. SBBs that hold non-serializable
 * CMP state (e.g. {@link java.nio.ByteBuffer}, file handles, network
 * sockets) must mark those fields {@code transient} in the accessor
 * or store them through {@link com.microjainslee.core.CmpBackedSbb}'s
 * own store rather than relying on the snapshot.
 *
 * <p><b>R&amp;D only &mdash; never for production.</b>
 */
public final class DistributedSbbEntityPool {

    /** Name of the Infinispan cache that stores per-entity CMP snapshots. */
    public static final String CACHE_NAME = "sbb-entity-state";

    private static final Logger LOG = LogManager.getLogger(DistributedSbbEntityPool.class);

    private final VirtualThreadSbbEntityPool delegate;
    private final Cache<String, SbbEntitySnapshot> stateCache;
    private final ClusterManager clusterManager;

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
        Objects.requireNonNull(clusterMgr, "clusterMgr");
        this.clusterManager = clusterMgr;
        this.delegate = new VirtualThreadSbbEntityPool(min, max, perVirtualThread);
        // REPL_ASYNC: every node holds a full copy of the cache and
        // writes are replicated asynchronously. This is the right
        // trade-off for SBB entity state: reads (acquire fallback)
        // are local and fast; writes (release) do not block the hot
        // path on a JGroups round-trip.
        this.stateCache = clusterMgr.<String, SbbEntitySnapshot>getCache(
                CACHE_NAME, CacheMode.REPL_ASYNC);
        LOG.info("DistributedSbbEntityPool ready: min={} max={} perVT={} cache={} mode={} node={}",
                min, max, perVirtualThread, CACHE_NAME,
                stateCache.getCacheConfiguration().clustering().cacheMode(),
                clusterMgr.getNodeId());
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
        persistSnapshot(entity);
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
        if (entity != null) {
            persistSnapshot(entity);
        }
        delegate.releaseById(sbbId);
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
        Objects.requireNonNull(sbbId, "sbbId");
        Objects.requireNonNull(sbb, "sbb");
        Class<?> klass = sbb.getClass();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Method m : findCmpAccessors(klass)) {
            // Getters only - setters require a value and would never
            // produce the field's current state. We also skip the
            // rare case where a @CmpField is declared with no args
            // AND no return type (e.g. a void accessor) - we treat
            // it as a non-readable accessor.
            if (m.getParameterCount() != 0 || m.getReturnType() == void.class) {
                continue;
            }
            CmpField ann = m.getAnnotation(CmpField.class);
            try {
                m.setAccessible(true);
                values.put(ann.value(), m.invoke(sbb));
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
                System.currentTimeMillis());
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
     * Called from {@link #release(VirtualThreadSbbEntityPool.SbbEntity)}
     * and {@link #releaseById(String)} so any subsequent acquire on a
     * peer node can reconstruct the entity.
     */
    private void persistSnapshot(VirtualThreadSbbEntityPool.SbbEntity entity) {
        try {
            SbbEntitySnapshot snap = takeSnapshot(entity.getSbbId(), entity.getSbb());
            stateCache.put(entity.getSbbId(), snap);
        } catch (RuntimeException re) {
            // Best effort - a failure to persist must not abort the
            // release path. We log and move on; the entity is released
            // locally and a peer-node acquire will simply fall back to
            // a fresh cold-start (which is the same behaviour as if no
            // snapshot had ever existed).
            LOG.warn("persistSnapshot('{}') failed: {}", entity.getSbbId(), re.toString());
        }
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
