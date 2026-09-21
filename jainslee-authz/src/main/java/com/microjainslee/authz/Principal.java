/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.authz;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An authenticated caller.
 *
 * @param subject     stable subject id (user id)
 * @param tenantId    tenant the subject belongs to; {@code null} only for
 *                    platform-level principals
 * @param roles       granted role names (not expanded — see {@link RoleHierarchy})
 * @param permissions permissions granted directly (in addition to role grants)
 * @param status      account status; only ACTIVE passes {@link AuthzPolicy#requireActive}
 * @param expiresAt   credential expiry, {@code null} = no expiry
 * @param attributes  free-form claims (department ids, clearance …)
 */
public record Principal(
        String subject,
        String tenantId,
        Set<String> roles,
        Set<String> permissions,
        AccountStatus status,
        Instant expiresAt,
        Map<String, Object> attributes) {

    public Principal {
        Objects.requireNonNull(subject, "subject");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        status = status == null ? AccountStatus.PENDING_APPROVAL : status;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static Principal of(String subject, String tenantId, String... roles) {
        return new Principal(subject, tenantId, Set.of(roles), Set.of(),
                AccountStatus.ACTIVE, null, Map.of());
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public Object attribute(String name) {
        return attributes.get(name);
    }
}
