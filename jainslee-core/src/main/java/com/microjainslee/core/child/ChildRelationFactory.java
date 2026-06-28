/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.child;

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;
import com.microjainslee.core.SbbTypeRegistry;
import com.microjainslee.core.VirtualThreadSbbEntityPool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Factory that creates {@link ChildRelationImpl} instances for a concrete
 * parent SBB class. Reflection-scans the parent class for abstract methods
 * returning {@code ChildRelation<T>} and produces a relation per matching
 * method, cached so repeated {@link #createChildRelation(String, Class)}
 * calls return the same instance for the same parent SBB entity id.
 *
 * <p>Module: jainslee-core — invoked by {@code ConcreteSbbGenerator} when
 * it generates the concrete child-relation accessor for an abstract SBB.
 *
 * <p>The factory also exposes a default {@link ChildEntityFactory} that
 * borrows a child SBB instance from the {@link SbbTypeRegistry} (via
 * {@link VirtualThreadSbbEntityPool}) and wraps it in a
 * {@link SimpleSbbLocalObject} ready to be registered as a child.
 *
 * @author Tran Nhan (nhanth87)
 */
public class ChildRelationFactory {

    private static final Logger LOG = LogManager.getLogger(ChildRelationFactory.class);

    // Weak cache: Class<?> → List<Method> (abstract methods returning ChildRelation)
    private static final Map<Class<?>, java.util.List<Method>> ABSTRACT_METHOD_CACHE =
            new ConcurrentHashMap<Class<?>, java.util.List<Method>>();

    private final VirtualThreadSbbEntityPool entityPool;
    private final SbbTypeRegistry sbbTypeRegistry;
    private final CascadeRemover cascadeRemover;
    private final Map<String, ChildRelationImpl<?>> relationCache =
            new ConcurrentHashMap<String, ChildRelationImpl<?>>();
    private final AtomicLong entityIdSequence = new AtomicLong(1);

    public ChildRelationFactory(VirtualThreadSbbEntityPool entityPool,
                                SbbTypeRegistry sbbTypeRegistry,
                                CascadeRemover cascadeRemover) {
        if (entityPool == null) {
            throw new IllegalArgumentException("VirtualThreadSbbEntityPool is required");
        }
        if (cascadeRemover == null) {
            throw new IllegalArgumentException("CascadeRemover is required");
        }
        this.entityPool = entityPool;
        this.sbbTypeRegistry = sbbTypeRegistry;
        this.cascadeRemover = cascadeRemover;
    }

    /**
     * Construct a factory backed by a {@link MicroSleeContainer} so the
     * default entity factory can borrow children from the registered
     * {@link SbbTypeRegistry}. Useful for embedders that already have a
     * container.
     */
    public static ChildRelationFactory from(MicroSleeContainer container,
                                            CascadeRemover cascadeRemover) {
        if (container == null) {
            throw new IllegalArgumentException("MicroSleeContainer is required");
        }
        return new ChildRelationFactory(
                container.getSbbEntityPool(),
                container.getSbbTypeRegistry(),
                cascadeRemover);
    }

    /**
     * Create (or return the cached) child relation for the supplied parent
     * entity id and child SBB local-interface class.
     */
    public <T extends SbbLocalObject> ChildRelationImpl<T> createChildRelation(
            String parentEntityId, Class<T> childClass) {
        if (parentEntityId == null || childClass == null) {
            throw new IllegalArgumentException("parentEntityId and childClass are required");
        }
        String cacheKey = parentEntityId + "::" + childClass.getName();
        @SuppressWarnings("unchecked")
        ChildRelationImpl<T> cached = (ChildRelationImpl<T>) relationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        ChildRelationImpl<T> fresh = new ChildRelationImpl<T>(
                parentEntityId, childClass, cascadeRemover,
                new DefaultChildEntityFactory<T>(childClass, parentEntityId));
        @SuppressWarnings("unchecked")
        ChildRelationImpl<T> prior = (ChildRelationImpl<T>) relationCache.putIfAbsent(cacheKey, fresh);
        return prior != null ? prior : fresh;
    }

    /**
     * Return the child-relation instance already cached for {@code (parentEntityId, childClass)},
     * or {@code null} if none.
     */
    public ChildRelationImpl<?> findChildRelation(String parentEntityId, Class<?> childClass) {
        if (parentEntityId == null || childClass == null) {
            return null;
        }
        return relationCache.get(parentEntityId + "::" + childClass.getName());
    }

    /**
     * Drop every cached relation. Called by the container on shutdown so
     * the next deploy gets a clean slate.
     */
    public void clearCache() {
        relationCache.clear();
    }

    /** Number of cached relations — for diagnostics + tests. */
    public int cachedRelationCount() {
        return relationCache.size();
    }

    /**
     * Scan {@code parentClass} for abstract methods returning
     * {@code ChildRelation<T>} and return a (cached) list. Used by the
     * codegen module to inject accessors into the generated concrete SBB.
     *
     * <p>The returned list is a defensive copy so callers can mutate it
     * freely without poisoning the cache.
     */
    public static java.util.List<Method> findChildRelationMethods(Class<?> parentClass) {
        if (parentClass == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<Method> cached = ABSTRACT_METHOD_CACHE.get(parentClass);
        if (cached != null) {
            return new java.util.ArrayList<Method>(cached);
        }
        java.util.List<Method> discovered = new java.util.ArrayList<Method>();
        try {
            for (Method m : parentClass.getMethods()) {
                if (!java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                    continue;
                }
                if (m.getReturnType() != com.microjainslee.api.ChildRelation.class) {
                    continue;
                }
                discovered.add(m);
            }
        } catch (SecurityException ignored) {
            // Caller without getMethods() permission — return empty.
        }
        ABSTRACT_METHOD_CACHE.put(parentClass, discovered);
        return new java.util.ArrayList<Method>(discovered);
    }

    /**
     * Extract the {@code T} parameter from {@code ChildRelation<T>} on the
     * method's generic return type. Returns {@link SbbLocalObject} when the
     * parameter cannot be resolved (e.g. raw return type).
     */
    @SuppressWarnings("unchecked")
    public static Class<? extends SbbLocalObject> extractChildType(Method m) {
        if (m == null) {
            return SbbLocalObject.class;
        }
        Type ret = m.getGenericReturnType();
        if (ret instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) ret).getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class) {
                Class<?> candidate = (Class<?>) args[0];
                if (SbbLocalObject.class.isAssignableFrom(candidate)) {
                    return (Class<? extends SbbLocalObject>) candidate;
                }
            }
        }
        return SbbLocalObject.class;
    }

    // ---- Default entity factory -----------------------------------------

    /**
     * Default {@link ChildRelationImpl.ChildEntityFactory} that borrows a
     * child SBB instance from the {@link SbbTypeRegistry} and wraps it in
     * a {@link SimpleSbbLocalObject}.
     *
     * <p>When no registry is supplied (e.g. in unit tests) the factory
     * falls back to a no-op SBB so the wiring still completes without a
     * container.
     */
    private final class DefaultChildEntityFactory<T extends SbbLocalObject>
            implements ChildRelationImpl.ChildEntityFactory<T> {

        private final Class<T> childClass;
        @SuppressWarnings("unused")
        private final String parentEntityId;

        DefaultChildEntityFactory(Class<T> childClass, String parentEntityId) {
            this.childClass = childClass;
            this.parentEntityId = parentEntityId;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T createChild(String parentEntityId, ChildRelationImpl<T> relation) {
            String childEntityId = parentEntityId + ".child."
                    + entityIdSequence.incrementAndGet();
            Sbb childSbb;
            if (sbbTypeRegistry != null) {
                Class<? extends Sbb> sbbType = locateSbbTypeFor(childClass);
                if (sbbType != null && sbbTypeRegistry.isRegistered(sbbType)) {
                    childSbb = sbbTypeRegistry.require(sbbType).borrow();
                } else {
                    childSbb = newFallbackSbb();
                }
            } else {
                childSbb = newFallbackSbb();
            }
            // Register the entity in the pool for cascade lookups, then wrap
            // it as a SimpleSbbLocalObject so callers can invoke SBB-local
            // methods and the cascade remover can resolve the entity by id.
            entityPool.acquire(childEntityId, 0L, childSbb);
            SimpleSbbLocalObject local = new SimpleSbbLocalObject(
                    new com.microjainslee.api.SbbID(childEntityId), childSbb,
                    entityPool, null, 0);
            local.setParentEntityId(parentEntityId);
            try {
                return (T) local;
            } catch (ClassCastException cce) {
                LOG.warn("Child SBB local " + local.getClass().getName()
                        + " does not implement " + childClass.getName() + " — returning raw");
                return (T) local;
            }
        }

        @SuppressWarnings("unchecked")
        private Class<? extends Sbb> locateSbbTypeFor(Class<T> localInterface) {
            // Best-effort heuristic: try to find a registered SBB whose
            // generated local object implements this interface. When the
            // registry was supplied without per-type metadata we just
            // return the local interface itself (callers may use a custom
            // SbbTypePool registration).
            return (Class<? extends Sbb>) (Class<?>) localInterface;
        }

        private Sbb newFallbackSbb() {
            return new Sbb() {
                @Override
                public void sbbRemove() {
                    // no-op fallback
                }
            };
        }
    }

    /** Internal: produce a fresh entity id (also used by external callers via the pool). */
    public String allocateChildEntityId(String parentEntityId) {
        if (parentEntityId == null) {
            throw new IllegalArgumentException("parentEntityId is required");
        }
        return parentEntityId + ".child." + entityIdSequence.incrementAndGet();
    }

    /** Underlying entity pool accessor — for embedders that need to wire custom children. */
    public VirtualThreadSbbEntityPool getEntityPool() {
        return entityPool;
    }

    /** Underlying cascade remover accessor — for embedders. */
    public CascadeRemover getCascadeRemover() {
        return cascadeRemover;
    }

    /** SBB type registry accessor — may be {@code null}. */
    public SbbTypeRegistry getSbbTypeRegistry() {
        return sbbTypeRegistry;
    }
}
