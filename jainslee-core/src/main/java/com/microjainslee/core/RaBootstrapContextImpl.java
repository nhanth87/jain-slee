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

import com.microjainslee.api.ActivityContextHandle;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JAIN-SLEE 1.1 §11.2 — minimal RA bootstrap context for embedded deployments.
 */
public final class RaBootstrapContextImpl implements ResourceAdaptorContext {

    private final MicroSleeContainer container;
    private final String entityName;
    private final SleeEndpointPortImpl endpoint;
    private final ConcurrentHashMap<Object, ActivityContextHandle> handlesByActivity =
            new ConcurrentHashMap<Object, ActivityContextHandle>();
    private final AtomicLong handleSequence = new AtomicLong();
    private volatile ResourceAdaptor resourceAdaptor;

    public RaBootstrapContextImpl(MicroSleeContainer container, String entityName) {
        if (container == null) {
            throw new IllegalArgumentException("container is required");
        }
        if (entityName == null || entityName.trim().isEmpty()) {
            throw new IllegalArgumentException("entityName is required");
        }
        this.container = container;
        this.entityName = entityName;
        this.endpoint = new SleeEndpointPortImpl(container, entityName);
    }

    public String getEntityName() {
        return entityName;
    }

    public MicroSleeContainer getContainer() {
        return container;
    }

    @Override
    public void setResourceAdaptor(ResourceAdaptor ra) {
        this.resourceAdaptor = ra;
    }

    public ResourceAdaptor getResourceAdaptor() {
        return resourceAdaptor;
    }

    @Override
    public com.microjainslee.api.SleeEndpointPort getSleeEndpointPort() {
        return endpoint;
    }

    public SleeEndpointPortImpl getEndpoint() {
        return endpoint;
    }

    @Override
    public ActivityContextHandle createActivityContextHandle(Object activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity is required");
        }
        ActivityContextHandle existing = handlesByActivity.get(activity);
        if (existing != null) {
            return existing;
        }
        String handleId = entityName + ":ach:" + handleSequence.incrementAndGet();
        SimpleActivityContextHandle handle = new SimpleActivityContextHandle(handleId);
        container.createActivityContext(handleId);
        ActivityContextHandle prior = handlesByActivity.putIfAbsent(activity, handle);
        return prior != null ? prior : handle;
    }

    @Override
    public ActivityContextHandle getActivityContextHandle(Object activity) {
        if (activity == null) {
            throw new IllegalArgumentException("activity is required");
        }
        return handlesByActivity.get(activity);
    }
}
