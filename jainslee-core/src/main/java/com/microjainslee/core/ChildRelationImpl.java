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

import com.microjainslee.api.ChildRelation;
import com.microjainslee.api.CreateException;
import com.microjainslee.api.SLEEException;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.TransactionRequiredLocalException;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Default {@link ChildRelation} implementation.
 *
 * <p>JAIN-SLEE 1.1 §6.8 says a child relation is a typed collection of
 * {@link SbbLocalObject}s that share the same parent. {@link #create()}
 * delegates to the injected {@link ChildRelationFactory}, and
 * {@link #remove(Object)} cascades the removal down to the child so
 * a parent remove() tears down the entire entity sub-tree as required
 * by §6.8 + §2.2.8.
 *
 * <p>All mutation is guarded by {@code synchronized(this)}; reads
 * iterate a snapshot so iteration never throws
 * {@link java.util.ConcurrentModificationException} even when the
 * parent SBB is concurrently firing events.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class ChildRelationImpl implements ChildRelation {

    private final String parentSbbId;
    private final ChildRelationFactory factory;
    private final Set<SbbLocalObject> children = new LinkedHashSet<SbbLocalObject>();

    public ChildRelationImpl(String parentSbbId, ChildRelationFactory factory) {
        if (parentSbbId == null) {
            throw new IllegalArgumentException("parentSbbId is required");
        }
        if (factory == null) {
            throw new IllegalArgumentException("ChildRelationFactory is required");
        }
        this.parentSbbId = parentSbbId;
        this.factory = factory;
    }

    /** Parent SBB id (for diagnostics). */
    public String getParentSbbId() {
        return parentSbbId;
    }

    @Override
    public SbbLocalObject create()
            throws CreateException, TransactionRequiredLocalException, SLEEException {
        SbbLocalObject child;
        synchronized (this) {
            child = factory.createChild(parentSbbId);
            if (child == null) {
                throw new CreateException("ChildRelationFactory returned null for parent "
                        + parentSbbId);
            }
            children.add(child);
        }
        return child;
    }

    // ---- Collection<SbbLocalObject> contract -----------------------------

    @Override
    public int size() {
        synchronized (this) {
            return children.size();
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean contains(Object o) {
        if (!(o instanceof SbbLocalObject)) {
            return false;
        }
        synchronized (this) {
            for (SbbLocalObject child : children) {
                if (child == o) {
                    return true;
                }
                try {
                    if (child.isIdentical((SbbLocalObject) o)) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // isIdentical may throw TransactionRequiredLocalException
                    // when called outside a tx context — fall back to identity.
                }
            }
            return false;
        }
    }

    @Override
    public Iterator<SbbLocalObject> iterator() {
        synchronized (this) {
            return new java.util.LinkedHashSet<SbbLocalObject>(children).iterator();
        }
    }

    @Override
    public Object[] toArray() {
        synchronized (this) {
            return children.toArray();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        synchronized (this) {
            return children.toArray(a);
        }
    }

    @Override
    public boolean add(SbbLocalObject e) {
        // Per spec §6.8.3 add/addAll on a ChildRelation must throw
        // UnsupportedOperationException — child creation is restricted to
        // create() so the container can wire lifecycle + CMP correctly.
        throw new UnsupportedOperationException(
                "Use ChildRelation.create() instead of add()");
    }

    @Override
    public boolean remove(Object o) {
        if (!(o instanceof SbbLocalObject)) {
            return false;
        }
        SbbLocalObject child = (SbbLocalObject) o;
        boolean removed;
        synchronized (this) {
            removed = children.remove(child);
        }
        if (removed) {
            try {
                child.remove();
            } catch (Exception ignored) {
                // cascading remove is best-effort; the spec says failures
                // here should be logged but must not abort the parent.
            }
        }
        return removed;
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        if (c == null) {
            return false;
        }
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
            throw new NullPointerException();
        }
        boolean modified = false;
        synchronized (this) {
            Iterator<SbbLocalObject> it = children.iterator();
            while (it.hasNext()) {
                SbbLocalObject child = it.next();
                if (!c.contains(child)) {
                    it.remove();
                    try {
                        child.remove();
                    } catch (Exception ignored) {
                        // best-effort cascade
                    }
                    modified = true;
                }
            }
        }
        return modified;
    }

    @Override
    public void clear() {
        // Cascading removal per spec §6.8 — clear() removes every child.
        java.util.List<SbbLocalObject> snapshot;
        synchronized (this) {
            snapshot = new java.util.ArrayList<SbbLocalObject>(children);
            children.clear();
        }
        for (SbbLocalObject child : snapshot) {
            try {
                child.remove();
            } catch (Exception ignored) {
                // best-effort cascade
            }
        }
    }
}