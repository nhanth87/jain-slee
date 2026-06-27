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

/**
 * JAIN-SLEE 1.1 §11 — {@code SleeEndpointPort} implementation for embedded RAs.
 * Publishes events to the Disruptor ring without blocking the caller thread.
 */
public final class SleeEndpointPortImpl implements com.microjainslee.api.SleeEndpointPort {

    private final MicroSleeContainer container;
    private final String entityName;

    public SleeEndpointPortImpl(MicroSleeContainer container, String entityName) {
        if (container == null) {
            throw new IllegalArgumentException("container is required");
        }
        if (entityName == null || entityName.trim().isEmpty()) {
            throw new IllegalArgumentException("entityName is required");
        }
        this.container = container;
        this.entityName = entityName;
    }

    @Override
    public com.microjainslee.api.ActivityContextInterface startActivity(
            com.microjainslee.api.ActivityContextHandle handle, Object activity) {
        if (handle == null) {
            throw new IllegalArgumentException("handle is required");
        }
        String aciName = handle.getId();
        return container.createActivityContext(aciName);
    }

    @Override
    public void endActivity(com.microjainslee.api.ActivityContextHandle handle) {
        if (handle == null) {
            return;
        }
        container.getActivityContextNamingFacility().unbind(handle.getId());
    }

    @Override
    public void fireEvent(com.microjainslee.api.ActivityContextHandle handle,
                          com.microjainslee.api.SleeEvent event) {
        if (handle == null || event == null) {
            return;
        }
        com.microjainslee.api.ActivityContextInterface aci =
                container.getActivityContextNamingFacility().lookup(handle.getId());
        if (aci == null) {
            throw new IllegalStateException("Unknown activity handle: " + handle.getId());
        }
        container.routeEvent(event, aci);
    }

    public String getEntityName() {
        return entityName;
    }
}
