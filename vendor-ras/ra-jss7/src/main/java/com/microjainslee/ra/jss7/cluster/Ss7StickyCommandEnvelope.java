/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.jss7.cluster;

import com.microjainslee.ra.jss7.command.Ss7Command;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Wire envelope for sticky outbound forwarding over
 * {@link com.microjainslee.cluster.Ss7DialogCacheNames#RA_STICKY_COMMANDS}.
 */
public final class Ss7StickyCommandEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String envelopeId;
    private final String targetNodeId;
    private final String sourceNodeId;
    private final Ss7Command command;
    private final long createdAtEpochMs;

    public Ss7StickyCommandEnvelope(
            String envelopeId,
            String targetNodeId,
            String sourceNodeId,
            Ss7Command command,
            long createdAtEpochMs) {
        this.envelopeId = Objects.requireNonNull(envelopeId, "envelopeId");
        this.targetNodeId = Objects.requireNonNull(targetNodeId, "targetNodeId");
        this.sourceNodeId = Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        this.command = Objects.requireNonNull(command, "command");
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public static Ss7StickyCommandEnvelope of(
            String targetNodeId, String sourceNodeId, Ss7Command command) {
        return new Ss7StickyCommandEnvelope(
                UUID.randomUUID().toString(),
                targetNodeId,
                sourceNodeId,
                command,
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

    public Ss7Command command() {
        return command;
    }

    public long createdAtEpochMs() {
        return createdAtEpochMs;
    }
}
