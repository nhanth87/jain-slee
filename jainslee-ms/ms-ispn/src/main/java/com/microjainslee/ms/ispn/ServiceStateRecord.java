/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.ispn;

import com.microjainslee.ms.api.ServiceState;

import java.io.Serializable;

/** Value stored in {@code slee.service.state} replicated cache. */
public final class ServiceStateRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String serviceName;
    private final ServiceState state;
    private final String nodeId;
    private final long timestampMs;

    public ServiceStateRecord(String serviceName, ServiceState state, String nodeId, long timestampMs) {
        this.serviceName = serviceName;
        this.state = state;
        this.nodeId = nodeId;
        this.timestampMs = timestampMs;
    }

    public String serviceName() {
        return serviceName;
    }

    public ServiceState state() {
        return state;
    }

    public String nodeId() {
        return nodeId;
    }

    public long timestampMs() {
        return timestampMs;
    }
}
