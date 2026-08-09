/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Sticky outbound envelope for any RA — payload must be marshallable
 * ({@code com.microjainslee.*} / {@code java.*}).
 */
public final class RaStickyCommandEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String envelopeId;
    private final String targetNodeId;
    private final String sourceNodeId;
    private final String activityId;
    private final Serializable payload;
    private final long createdAtEpochMs;

    public RaStickyCommandEnvelope(
            String envelopeId,
            String targetNodeId,
            String sourceNodeId,
            String activityId,
            Serializable payload,
            long createdAtEpochMs) {
        this.envelopeId = Objects.requireNonNull(envelopeId, "envelopeId");
        this.targetNodeId = Objects.requireNonNull(targetNodeId, "targetNodeId");
        this.sourceNodeId = Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        this.activityId = Objects.requireNonNull(activityId, "activityId");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.createdAtEpochMs = createdAtEpochMs;
        MarshallingAllowList.assertMarshallable("sticky.payload", payload);
    }

    public static RaStickyCommandEnvelope of(
            String targetNodeId,
            String sourceNodeId,
            String activityId,
            Serializable payload) {
        return new RaStickyCommandEnvelope(
                UUID.randomUUID().toString(),
                targetNodeId,
                sourceNodeId,
                activityId,
                payload,
                System.currentTimeMillis());
    }

    public String envelopeId() {
        return envelopeId;
    }

    public String targetNodeId() {
        return targetNodeId;
    }

    public String sourceNodeId() {
        return sourceNodeId;
    }

    public String activityId() {
        return activityId;
    }

    public Serializable payload() {
        return payload;
    }

    public long createdAtEpochMs() {
        return createdAtEpochMs;
    }
}
