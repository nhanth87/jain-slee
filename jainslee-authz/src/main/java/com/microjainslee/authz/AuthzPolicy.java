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

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Multi-tenant authorization policy — immutable, thread-safe, shared by all
 * SBBs.
 *
 * <p>Every {@code require*} method either returns normally or throws
 * {@link AuthzException}; every decision is reported to the
 * {@link AuthzAuditSink}. Checks fail closed: a {@code null} principal is
 * {@link AuthzException.Reason#UNAUTHENTICATED}.
 *
 * <p>Permissions support a trailing wildcard: a grant of {@code billing:*}
 * satisfies {@code billing:read}; {@code *} satisfies everything.
 *
 * <pre>{@code
 * AuthzPolicy policy = AuthzPolicy.builder()
 *         .hierarchy(RoleHierarchy.DEFAULT)
 *         .crossTenantRole(Roles.PLATFORM_ADMIN)
 *         .grant(Roles.USER, "chat:use", "billing:read:self")
 *         .grant(Roles.TENANT_ADMIN, "users:approve", "billing:read:tenant")
 *         .grant(Roles.PLATFORM_ADMIN, "*")
 *         .build();
 *
 * Principal p = policy.authenticate(resolver.resolve(token).orElse(null));
 * policy.requireRole(p, Roles.TENANT_ADMIN);
 * policy.requireTenantAccess(p, company.tenantId());
 * }</pre>
 */
public final class AuthzPolicy {

    private final RoleHierarchy hierarchy;
    private final Map<String, Set<String>> grants;
    private final Set<String> crossTenantRoles;
    private final AuthzAuditSink audit;
    private final Clock clock;

    private AuthzPolicy(Builder b) {
        this.hierarchy = b.hierarchy;
        Map<String, Set<String>> g = new HashMap<>();
        b.grants.forEach((role, perms) -> g.put(role, Set.copyOf(perms)));
        this.grants = Map.copyOf(g);
        this.crossTenantRoles = Set.copyOf(b.crossTenantRoles);
        this.audit = b.audit;
        this.clock = b.clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RoleHierarchy hierarchy() {
        return hierarchy;
    }

    // ---- authentication gates ------------------------------------------------

    /**
     * Non-null, unexpired, ACTIVE principal — the gate for every normal
     * request. Returns the principal for fluent use.
     */
    public Principal authenticate(Principal p) {
        requireAuthenticated(p);
        requireActive(p);
        return p;
    }

    public void requireAuthenticated(Principal p) {
        if (p == null) {
            deny(null, "authenticated", AuthzException.Reason.UNAUTHENTICATED, "no credentials");
        }
        if (p.isExpired(clock.instant())) {
            deny(p, "authenticated", AuthzException.Reason.EXPIRED, "credentials expired");
        }
    }

    /** Only approved accounts may use the service. */
    public void requireActive(Principal p) {
        requireAuthenticated(p);
        if (p.status() != AccountStatus.ACTIVE) {
            deny(p, "active", AuthzException.Reason.ACCOUNT_NOT_ACTIVE,
                    "account is " + p.status());
        }
        allow(p, "active");
    }

    // ---- roles & permissions -------------------------------------------------

    public boolean hasRole(Principal p, String role) {
        return p != null && hierarchy.satisfies(p.roles(), role);
    }

    public void requireRole(Principal p, String role) {
        requireAuthenticated(p);
        if (!hasRole(p, role)) {
            deny(p, "role:" + role, AuthzException.Reason.MISSING_ROLE,
                    "requires role " + role);
        }
        allow(p, "role:" + role);
    }

    /** All permissions of the principal: direct grants plus grants of every expanded role. */
    public Set<String> effectivePermissions(Principal p) {
        Set<String> out = new HashSet<>(p.permissions());
        for (String role : hierarchy.expand(p.roles())) {
            out.addAll(grants.getOrDefault(role, Set.of()));
        }
        return out;
    }

    public boolean hasPermission(Principal p, String permission) {
        if (p == null) {
            return false;
        }
        for (String granted : effectivePermissions(p)) {
            if (implies(granted, permission)) {
                return true;
            }
        }
        return false;
    }

    public void requirePermission(Principal p, String permission) {
        requireAuthenticated(p);
        if (!hasPermission(p, permission)) {
            deny(p, "perm:" + permission, AuthzException.Reason.MISSING_PERMISSION,
                    "requires permission " + permission);
        }
        allow(p, "perm:" + permission);
    }

    static boolean implies(String granted, String required) {
        if (granted.equals("*") || granted.equals(required)) {
            return true;
        }
        if (granted.endsWith(":*")) {
            String prefix = granted.substring(0, granted.length() - 1);
            return required.startsWith(prefix);
        }
        return false;
    }

    // ---- tenancy ---------------------------------------------------------------

    public boolean canCrossTenants(Principal p) {
        if (p == null) {
            return false;
        }
        for (String role : crossTenantRoles) {
            if (hierarchy.satisfies(p.roles(), role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The principal may touch a resource of {@code resourceTenantId}: same
     * tenant, or a cross-tenant role. A {@code null} resource tenant means a
     * platform-wide resource and requires a cross-tenant role.
     */
    public void requireTenantAccess(Principal p, String resourceTenantId) {
        requireAuthenticated(p);
        boolean same = resourceTenantId != null && resourceTenantId.equals(p.tenantId());
        if (!same && !canCrossTenants(p)) {
            deny(p, "tenant:" + resourceTenantId, AuthzException.Reason.CROSS_TENANT,
                    "tenant " + p.tenantId() + " may not access tenant " + resourceTenantId);
        }
        allow(p, "tenant:" + resourceTenantId);
    }

    public void requireTenantAccess(Principal p, TenantScoped resource) {
        requireTenantAccess(p, resource == null ? null : resource.tenantId());
    }

    /**
     * Tenant to act on: the principal's own tenant, or — for cross-tenant
     * roles only — an explicitly requested one. Use this instead of trusting
     * a tenant id from a request body.
     */
    public String effectiveTenant(Principal p, String requestedTenantId) {
        requireAuthenticated(p);
        if (requestedTenantId == null || requestedTenantId.isBlank()
                || requestedTenantId.equals(p.tenantId())) {
            return p.tenantId();
        }
        requireTenantAccess(p, requestedTenantId);
        return requestedTenantId;
    }

    /** The subject itself, or a role that manages it (e.g. tenant-admin over own users). */
    public void requireSelfOr(Principal p, String subjectId, String subjectTenantId, String managerRole) {
        requireAuthenticated(p);
        if (p.subject().equals(subjectId)) {
            allow(p, "self:" + subjectId);
            return;
        }
        requireRole(p, managerRole);
        requireTenantAccess(p, subjectTenantId);
    }

    // ---- audit ---------------------------------------------------------------------

    private void allow(Principal p, String action) {
        audit.record(new Decision(p, action, true, null, null));
    }

    private void deny(Principal p, String action, AuthzException.Reason reason, String detail) {
        audit.record(new Decision(p, action, false, reason, detail));
        throw new AuthzException(reason, detail);
    }

    public static final class Builder {

        private RoleHierarchy hierarchy = RoleHierarchy.DEFAULT;
        private final Map<String, Set<String>> grants = new HashMap<>();
        private final Set<String> crossTenantRoles = new HashSet<>(Set.of(Roles.PLATFORM_ADMIN));
        private AuthzAuditSink audit = AuthzAuditSink.NONE;
        private Clock clock = Clock.systemUTC();

        public Builder hierarchy(RoleHierarchy h) {
            this.hierarchy = h;
            return this;
        }

        public Builder grant(String role, String... permissions) {
            grants.computeIfAbsent(role, r -> new HashSet<>()).addAll(Set.of(permissions));
            return this;
        }

        /** Replaces the default cross-tenant role set ({@code platform-admin}). */
        public Builder crossTenantRoles(String... roles) {
            crossTenantRoles.clear();
            crossTenantRoles.addAll(Set.of(roles));
            return this;
        }

        public Builder crossTenantRole(String role) {
            crossTenantRoles.add(role);
            return this;
        }

        public Builder audit(AuthzAuditSink sink) {
            this.audit = sink;
            return this;
        }

        public Builder clock(Clock c) {
            this.clock = c;
            return this;
        }

        public AuthzPolicy build() {
            return new AuthzPolicy(this);
        }
    }
}
