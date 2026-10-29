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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityContextNamingFacility;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JBoss-free activity context naming facility for embedded deployments.
 */
public final class InMemoryActivityContextNamingFacility implements ActivityContextNamingFacility {

    private final ConcurrentHashMap<String, ActivityContextInterface> contexts =
            new ConcurrentHashMap<String, ActivityContextInterface>();
    /** Reverse index by ACI identity → a bind key (O(1) {@link #resolveName}). */
    private final ConcurrentHashMap<Identity, String> names = new ConcurrentHashMap<Identity, String>();
    /** ACIs ever bound under more than one name — the only ones whose unbind needs a scan. */
    private final java.util.Set<Identity> multiBound = ConcurrentHashMap.newKeySet();

    /** Identity-keyed wrapper: ACIs are matched by reference, as {@link #resolveName} always did. */
    private record Identity(ActivityContextInterface aci) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Identity other && other.aci == aci;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(aci);
        }
    }

    @Override
    public void bind(String name, ActivityContextInterface aci) {
        if (name == null || aci == null) {
            throw new IllegalArgumentException("name and aci are required");
        }
        ActivityContextInterface previous = contexts.put(name, aci);
        if (previous != null && previous != aci) {
            forgetName(previous, name);
        }
        Identity id = new Identity(aci);
        String other = names.put(id, name);
        if (other != null && !other.equals(name)) {
            multiBound.add(id);
        }
    }

    public ActivityContextInterface lookup(String name) {
        return contexts.get(name);
    }

    public java.util.Collection<ActivityContextInterface> getBoundContexts() {
        return java.util.Collections.unmodifiableCollection(contexts.values());
    }

    public void unbind(String name) {
        ActivityContextInterface removed = contexts.remove(name);
        if (removed != null) {
            forgetName(removed, name);
        }
    }

    /** {@code name} no longer refers to {@code aci}; keep another key if it still has one. */
    private void forgetName(ActivityContextInterface aci, String name) {
        Identity id = new Identity(aci);
        boolean wasKey = names.remove(id, name);
        if (!multiBound.contains(id)) {
            return;
        }
        // Rare: the same ACI bound under several names — keep one of the others, if any.
        for (Map.Entry<String, ActivityContextInterface> e : contexts.entrySet()) {
            if (e.getValue() == aci) {
                if (wasKey) {
                    names.putIfAbsent(id, e.getKey());
                }
                return;
            }
        }
        multiBound.remove(id);
    }

    /**
     * Production P3 — reverse lookup: the bind key under which the given
     * activity context is registered, or {@code null} when unbound.
     * <p>
     * Needed because pooled ACIs (from {@code ActivityContextPool}) carry a
     * final placeholder name field ("__pool__") that never reflects the
     * caller-facing bind name — only this facility knows the real key.
     */
    public String resolveName(ActivityContextInterface aci) {
        if (aci == null) {
            return null;
        }
        String name = names.get(new Identity(aci));
        // bind/unbind race: only report a key that still maps to this ACI.
        return name != null && contexts.get(name) == aci ? name : null;
    }

    public void clear() {
        contexts.clear();
        names.clear();
        multiBound.clear();
    }
}
