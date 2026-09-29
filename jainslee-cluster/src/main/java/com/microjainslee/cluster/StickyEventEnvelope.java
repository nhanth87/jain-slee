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
 * ADR 0007 D2 — the inbound counterpart of {@link RaStickyCommandEnvelope}.
 *
 * <p>
 * The existing sticky fabric forwards <b>commands out</b> to the node that owns
 * a live connection. It had no mirror for <b>events in</b>, which is why the
 * canonical scenario could not work: a TCP-CONTINUE lands on the SS7 node while
 * the client's HTTP socket sits on the HTTP node, so
 * {@code acnf.lookup(activityId)} finds no local binding and the delivery dies
 * with {@code IllegalStateException: Unknown activity handle}.
 *
 * <p>
 * The payload is a <b>portable POJO</b>, never a protocol stack type. The
 * marshalling allow-list is restricted to {@code com.microjainslee.*} and
 * {@code java.*}, so {@code org.restcomm.*} (jSS7 {@code MAPMessage}) cannot
 * travel; RAs must supply a decoded, serializable form. The
 * {@code TcapDialogSnapshotPayload} pattern already does this for TCAP dialogs.
 */
public final class StickyEventEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String envelopeId;
    private final String targetNodeId;
    private final String sourceNodeId;
    private final String activityId;
    private final String raName;
    private final String eventType;
    private final Serializable payload;
    private final long createdAtEpochMs;

    private StickyEventEnvelope(String envelopeId, String targetNodeId, String sourceNodeId,
                                String activityId, String raName, String eventType,
                                Serializable payload, long createdAtEpochMs) {
        this.envelopeId = envelopeId;
        this.targetNodeId = targetNodeId;
        this.sourceNodeId = sourceNodeId;
        this.activityId = activityId;
        this.raName = raName;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public static StickyEventEnvelope of(String targetNodeId, String sourceNodeId, String activityId,
                                         String raName, String eventType, Serializable payload) {
        return of(targetNodeId, sourceNodeId, activityId, raName, eventType, payload,
                System.currentTimeMillis());
    }

    /**
     * @param createdAtEpochMs override the creation stamp — used by tests to age
     *                         an envelope without sleeping.
     */
    public static StickyEventEnvelope of(String targetNodeId, String sourceNodeId, String activityId,
                                         String raName, String eventType, Serializable payload,
                                         long createdAtEpochMs) {
        Objects.requireNonNull(targetNodeId, "targetNodeId");
        Objects.requireNonNull(activityId, "activityId");
        MarshallingAllowList.assertMarshallable("sticky-event.payload", payload);
        return new StickyEventEnvelope(UUID.randomUUID().toString(), targetNodeId, sourceNodeId,
                activityId, raName, eventType, payload, createdAtEpochMs);
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

    /** The activity-context name — the correlation key end to end (ADR 0007 D1). */
    public String activityId() {
        return activityId;
    }

    public String raName() {
        return raName;
    }

    /** Simple class name of the original {@code SleeEvent}, for logging/assertion. */
    public String eventType() {
        return eventType;
    }

    public Serializable payload() {
        return payload;
    }

    public long createdAtEpochMs() {
        return createdAtEpochMs;
    }

    /**
     * Stale envelopes are dropped: a response forwarded to a node that is no
     * longer the owner must not be delivered into an unrelated activity.
     */
    public boolean isStale(long nowEpochMs, long maxAgeMs) {
        return nowEpochMs - createdAtEpochMs > maxAgeMs;
    }

    @Override
    public String toString() {
        return "StickyEventEnvelope[activity=" + activityId
                + ", ra=" + raName
                + ", type=" + eventType
                + ", " + sourceNodeId + " → " + targetNodeId
                + ", ageMs=" + (System.currentTimeMillis() - createdAtEpochMs) + ']';
    }
}
