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

    @Override
    public void bind(String name, ActivityContextInterface aci) {
        if (name == null || aci == null) {
            throw new IllegalArgumentException("name and aci are required");
        }
        contexts.put(name, aci);
    }

    public ActivityContextInterface lookup(String name) {
        return contexts.get(name);
    }

    public java.util.Collection<ActivityContextInterface> getBoundContexts() {
        return java.util.Collections.unmodifiableCollection(contexts.values());
    }

    public void unbind(String name) {
        contexts.remove(name);
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
        for (Map.Entry<String, ActivityContextInterface> e : contexts.entrySet()) {
            if (e.getValue() == aci) {
                return e.getKey();
            }
        }
        return null;
    }

    public void clear() {
        contexts.clear();
    }
}
