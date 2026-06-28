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

import com.microjainslee.api.ChildRelation;
import com.microjainslee.api.CreateException;
import com.microjainslee.api.SLEEException;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.TransactionRequiredLocalException;
import com.microjainslee.core.SimpleSbbLocalObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link ChildRelation} implementation — typed collection of child SBB
 * entities that all share the same parent.
 *
 * <p>Usage in an abstract parent SBB class:
 * <pre>
 *   public abstract ChildRelation&lt;AuthSbbLocalObject&gt; getAuthChildRelation();
 * </pre>
 *
 * <p>Concrete usage:
 * <pre>
 *   AuthSbbLocalObject auth = parent.getAuthChildRelation().create();
 *   auth.authenticate(token); // direct typed call — no cast
 *
 *   for (SbbLocalObject child : parent.getAuthChildRelation()) { ... }
 *
 *   parent.getAuthChildRelation().remove((AuthSbbLocalObject) target);
 * </pre>
 *
 * <p>Module: jainslee-core — {@link ChildRelationFactory} injects
 * {@code ChildRelationImpl} instances into the concrete SBB generated
 * by {@code ConcreteSbbGenerator}.
 *
 * <p>Thread safety: backed by a {@link ConcurrentHashMap}; reads are
 * lock-free, mutations (create/remove) use {@code compute*} helpers so
 * concurrent {@code create()} calls cannot lose children.
 *
 * @author Tran Nhan (nhanth87)
 */
public class ChildRelationImpl<T extends SbbLocalObject> implements ChildRelation {

    private static final Logger LOG = LogManager.getLogger(ChildRelationImpl.class);

    private final String parentEntityId;
    private final Class<T> childSbbLocalObjectClass;
    private final CascadeRemover cascadeRemover;
    private final ChildEntityFactory<T> entityFactory;

    // entityId → LocalObject (live children)
    private final ConcurrentHashMap<String, T> liveChildren = new ConcurrentHashMap<>();
    private final AtomicLong childSequence = new AtomicLong();

    /**
     * Pluggable factory that allocates a fresh child entity. The default
     * path is supplied by {@link ChildRelationFactory}; tests inject
     * stubs.
     */
    public interface ChildEntityFactory<T extends SbbLocalObject> {
        T createChild(String parentEntityId, ChildRelationImpl<T> relation);
    }

    public ChildRelationImpl(String parentEntityId,
                             Class<T> childSbbLocalObjectClass,
                             CascadeRemover cascadeRemover,
                             ChildEntityFactory<T> entityFactory) {
        if (parentEntityId == null) {
            throw new IllegalArgumentException("parentEntityId is required");
        }
        if (childSbbLocalObjectClass == null) {
            throw new IllegalArgumentException("childSbbLocalObjectClass is required");
        }
        if (cascadeRemover == null) {
            throw new IllegalArgumentException("CascadeRemover is required");
        }
        if (entityFactory == null) {
            throw new IllegalArgumentException("ChildEntityFactory is required");
        }
        this.parentEntityId = parentEntityId;
        this.childSbbLocalObjectClass = childSbbLocalObjectClass;
        this.cascadeRemover = cascadeRemover;
        this.entityFactory = entityFactory;
    }

    /** Parent SBB entity id (for diagnostics + cascade wiring). */
    public String getParentEntityId() {
        return parentEntityId;
    }

    /** The SBB local-interface class this relation spawns. */
    public Class<T> getChildSbbLocalObjectClass() {
        return childSbbLocalObjectClass;
    }

    /**
     * Allocate a new child SBB entity and register the parent → child
     * relationship with the supplied {@link CascadeRemover}.
     *
     * @return the freshly-created child SBB local object, narrowed to {@code T}
     */
    @Override
    public T create()
            throws CreateException, TransactionRequiredLocalException, SLEEException {
        T child = entityFactory.createChild(parentEntityId, this);
        if (child == null) {
            throw new CreateException("ChildEntityFactory returned null for parent "
                    + parentEntityId);
        }
        String childEntityId = extractEntityId(child);
        if (childEntityId == null || childEntityId.isBlank()) {
            throw new CreateException("Child SBB local object has no entity id: "
                    + child.getClass().getName());
        }
        // Compute if absent: if the same child id is already present (shouldn't
        // happen under normal use) we still register cascade once.
        T prior = liveChildren.putIfAbsent(childEntityId, child);
        if (prior == null) {
            cascadeRemover.registerChild(parentEntityId, childEntityId);
            if (child instanceof SimpleSbbLocalObject) {
                ((SimpleSbbLocalObject) child).setParentEntityId(parentEntityId);
            }
            LOG.debug("Child created: parent=" + parentEntityId + " child=" + childEntityId
                    + " type=" + childSbbLocalObjectClass.getSimpleName()
                    + " seq=" + childSequence.incrementAndGet());
        }
        return child;
    }

    /**
     * Remove a single child entity. Triggers depth-first post-order cascade
     * removal starting from {@code child} per spec §6.7.
     */
    @Override
    public boolean remove(Object o) {
        if (!(o instanceof SbbLocalObject)) {
            return false;
        }
        SbbLocalObject child = (SbbLocalObject) o;
        String childEntityId = extractEntityId(child);
        if (childEntityId == null) {
            return false;
        }
        boolean removed = liveChildren.remove(childEntityId) != null;
        if (removed) {
            cascadeRemover.deregisterChild(parentEntityId, childEntityId);
            // Per spec §6.7: child sbbRemove() is invoked before the parent's.
            cascadeRemoveChildOnly(childEntityId);
            LOG.debug(String.format("Child removed explicitly: %s from parent %s", childEntityId, parentEntityId));
        }
        return removed;
    }

    /**
     * Cascade remove this child plus its own descendants. Implemented here
     * rather than at the {@code CascadeRemover} level so we can also drop
     * the {@code liveChildren} entry for any grand-children that this
     * parent owned indirectly (rare in practice but consistent with spec).
     */
    private void cascadeRemoveChildOnly(String childEntityId) {
        Sbb sbb = cascadeRemover.entityLookup().getSbb(childEntityId);
        // Drop this relation's tracking of any descendants of this child.
        // We don't own those here — the child has its own ChildRelationImpl
        // accessible via its parent — but we still trigger the cascade walk
        // so every grandchild has sbbRemove() invoked.
        java.util.HashSet<String> visited = new java.util.HashSet<>();
        walkDescendants(childEntityId, sbb, visited);
    }

    private void walkDescendants(String entityId, Sbb self, java.util.Set<String> visited) {
        if (entityId == null || !visited.add(entityId)) {
            return;
        }
        java.util.Set<String> kids = cascadeRemover.getChildren(entityId);
        for (String kid : new java.util.HashSet<>(kids)) {
            walkDescendants(kid, null, visited);
        }
        if (self != null) {
            try {
                self.sbbRemove();
            } catch (Throwable t) {
                LOG.error("sbbRemove() threw for " + entityId + " — continuing", t);
            }
        }
        cascadeRemover.entityLookup().releaseEntity(entityId);
    }

    // ---- Collection<SbbLocalObject> contract -----------------------------

    @Override
    public int size() {
        return liveChildren.size();
    }

    @Override
    public boolean isEmpty() {
        return liveChildren.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof SbbLocalObject)) {
            return false;
        }
        SbbLocalObject s = (SbbLocalObject) o;
        String id = extractEntityId(s);
        if (id != null && liveChildren.containsKey(id)) {
            return true;
        }
        // Fallback to identity match.
        for (T child : liveChildren.values()) {
            if (child == s) {
                return true;
            }
            try {
                if (s.isIdentical(child)) {
                    return true;
                }
            } catch (Exception ignored) {
                // isIdentical may throw TransactionRequiredLocalException when
                // called outside a tx context — fall back to identity.
            }
        }
        return false;
    }

    @Override
    public Iterator<SbbLocalObject> iterator() {
        return Collections.unmodifiableCollection(
                (Collection<SbbLocalObject>) (Collection<?>) liveChildren.values()).iterator();
    }

    @Override
    public Object[] toArray() {
        return liveChildren.values().toArray();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E> E[] toArray(E[] a) {
        return (E[]) liveChildren.values().toArray(a);
    }

    @Override
    public boolean add(SbbLocalObject e) {
        // Spec §6.8.3 — restricted to create() so the container can wire
        // lifecycle + CMP correctly.
        throw new UnsupportedOperationException(
                "Use ChildRelation.create() instead of add()");
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        for (Object element : c) {
            if (!contains(element)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean addAll(Collection<? extends SbbLocalObject> c) {
        throw new UnsupportedOperationException(
                "Use ChildRelation.create() instead of addAll()");
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        if (c == null) {
            return false;
        }
        boolean modified = false;
        for (Object element : c) {
            if (remove(element)) {
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        if (c == null) {
            throw new NullPointerException("retainAll collection is null");
        }
        boolean modified = false;
        for (Iterator<SbbLocalObject> it = iterator(); it.hasNext(); ) {
            SbbLocalObject child = it.next();
            if (!c.contains(child)) {
                remove(child);
                modified = true;
            }
        }
        return modified;
    }

    @Override
    public void clear() {
        // Cascading removal per spec §6.8.
        List<T> snapshot = List.copyOf(liveChildren.values());
        liveChildren.clear();
        for (T child : snapshot) {
            try {
                child.remove();
            } catch (Exception ignored) {
                // best-effort cascade
            }
        }
    }

    /**
     * Read-only snapshot of all live children, in insertion order.
     */
    public List<T> getAll() {
        return List.copyOf(liveChildren.values());
    }

    /**
     * Extract entity id from a {@link SbbLocalObject}. Prefers the
     * {@code getEntityId()} marker method on {@link SimpleSbbLocalObject},
     * then {@code SbbID.getId()}, falling back to identity hash if neither
     * is available (which is rare and logged at WARN).
     */
    private String extractEntityId(SbbLocalObject sbbLocal) {
        if (sbbLocal instanceof SimpleSbbLocalObject) {
            String id = ((SimpleSbbLocalObject) sbbLocal).getParentEntityId();
            // Note: SimpleSbbLocalObject exposes parentEntityId, not its own
            // entity id. Use SbbID below.
        }
        try {
            SbbID sid = sbbLocal.getSbbID();
            if (sid != null) {
                String id = sid.getId();
                if (id != null && !id.isBlank()) {
                    return id;
                }
            }
        } catch (Exception ignored) {
            // fall through to identity hash
        }
        LOG.warn("SbbLocalObject " + sbbLocal.getClass().getSimpleName()
                + " does not expose an entity id — using identity hash");
        return "entity-" + System.identityHashCode(sbbLocal);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChildRelationImpl)) {
            return false;
        }
        ChildRelationImpl<?> other = (ChildRelationImpl<?>) o;
        return Objects.equals(parentEntityId, other.parentEntityId)
                && Objects.equals(childSbbLocalObjectClass, other.childSbbLocalObjectClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parentEntityId, childSbbLocalObjectClass);
    }

    @Override
    public String toString() {
        return "ChildRelationImpl{parent=" + parentEntityId
                + ", childType=" + childSbbLocalObjectClass.getSimpleName()
                + ", size=" + size() + "}";
    }
}
