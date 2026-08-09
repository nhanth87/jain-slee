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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Portable RA session / dialog meta for ISPN (no protocol stack types).
 *
 * <p>String attrs only — SIP peers, HTTP URLs, Diameter session ids, etc.
 */
public final class RaSessionMeta implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String activityId;
    private final String raName;
    private final String status;
    private final Map<String, String> attrs;
    private final long updatedAtEpochMs;

    public RaSessionMeta(
            String activityId,
            String raName,
            String status,
            Map<String, String> attrs,
            long updatedAtEpochMs) {
        this.activityId = Objects.requireNonNull(activityId, "activityId");
        this.raName = Objects.requireNonNull(raName, "raName");
        this.status = status == null ? "Active" : status;
        if (attrs == null || attrs.isEmpty()) {
            this.attrs = Collections.emptyMap();
        } else {
            this.attrs = Collections.unmodifiableMap(new LinkedHashMap<>(attrs));
        }
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    public String activityId() {
        return activityId;
    }

    public String raName() {
        return raName;
    }

    public String status() {
        return status;
    }

    public Map<String, String> attrs() {
        return attrs;
    }

    public String attr(String key) {
        return attrs.get(key);
    }

    public long updatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public RaSessionMeta withStatus(String newStatus, long nowMs) {
        return new RaSessionMeta(activityId, raName, newStatus, attrs, nowMs);
    }

    public RaSessionMeta withAttrs(Map<String, String> newAttrs, long nowMs) {
        return new RaSessionMeta(activityId, raName, status, newAttrs, nowMs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RaSessionMeta that)) {
            return false;
        }
        return updatedAtEpochMs == that.updatedAtEpochMs
                && activityId.equals(that.activityId)
                && raName.equals(that.raName)
                && status.equals(that.status)
                && attrs.equals(that.attrs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(activityId, raName, status, attrs, updatedAtEpochMs);
    }

    @Override
    public String toString() {
        return "RaSessionMeta[activityId=" + activityId
                + ", raName=" + raName
                + ", status=" + status
                + ", attrs=" + attrs.size()
                + ']';
    }
}
