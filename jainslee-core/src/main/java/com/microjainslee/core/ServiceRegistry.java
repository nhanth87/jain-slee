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

import com.microjainslee.api.ServiceID;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JAIN-SLEE 1.1 §13 — minimal in-memory service lifecycle registry.
 */
public final class ServiceRegistry {

    private final ConcurrentHashMap<ServiceID, ServiceState> services =
            new ConcurrentHashMap<ServiceID, ServiceState>();

    public synchronized void activate(ServiceID serviceID) {
        if (serviceID == null) {
            throw new IllegalArgumentException("serviceID is required");
        }
        ServiceState current = services.get(serviceID);
        if (current == ServiceState.ACTIVE) {
            return;
        }
        if (current == ServiceState.STOPPING) {
            throw new IllegalStateException("Cannot activate service while stopping: " + serviceID);
        }
        services.put(serviceID, ServiceState.ACTIVE);
    }

    public synchronized void stop(ServiceID serviceID) {
        if (serviceID == null) {
            throw new IllegalArgumentException("serviceID is required");
        }
        ServiceState current = services.get(serviceID);
        if (current == null || current == ServiceState.INACTIVE) {
            return;
        }
        services.put(serviceID, ServiceState.STOPPING);
        services.put(serviceID, ServiceState.INACTIVE);
    }

    public ServiceState getState(ServiceID serviceID) {
        if (serviceID == null) {
            throw new IllegalArgumentException("serviceID is required");
        }
        ServiceState state = services.get(serviceID);
        return state == null ? ServiceState.INACTIVE : state;
    }

    public boolean isActive(ServiceID serviceID) {
        return getState(serviceID) == ServiceState.ACTIVE;
    }

    public Map<ServiceID, ServiceState> snapshot() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<ServiceID, ServiceState>(services));
    }
}
