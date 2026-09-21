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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import org.junit.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AuthzPolicyTest {

    private static final String A = "tenant-a";
    private static final String B = "tenant-b";

    private final List<Decision> audit = new ArrayList<>();
    private final AuthzPolicy policy = AuthzPolicy.builder()
            .grant(Roles.USER, "chat:use", "billing:read:self")
            .grant(Roles.TENANT_ADMIN, "users:approve", "billing:*")
            .grant(Roles.PLATFORM_ADMIN, "*")
            .audit(audit::add)
            .build();

    private static Principal user(String tenant) {
        return Principal.of("u-" + tenant, tenant, Roles.USER);
    }

    private static Principal admin(String tenant) {
        return Principal.of("a-" + tenant, tenant, Roles.TENANT_ADMIN);
    }

    private static final Principal PLATFORM = Principal.of("root", null, Roles.PLATFORM_ADMIN);

    private static AuthzException.Reason reasonOf(Runnable r) {
        try {
            r.run();
        } catch (AuthzException e) {
            return e.reason();
        }
        fail("expected AuthzException");
        return null;
    }

    @Test
    public void hierarchyIsTransitive() {
        assertTrue(policy.hasRole(PLATFORM, Roles.USER));
        assertTrue(policy.hasRole(admin(A), Roles.USER));
        assertFalse(policy.hasRole(user(A), Roles.TENANT_ADMIN));
        assertEquals(AuthzException.Reason.MISSING_ROLE,
                reasonOf(() -> policy.requireRole(user(A), Roles.TENANT_ADMIN)));
    }

    @Test
    public void permissionsFollowRolesAndWildcards() {
        assertTrue(policy.hasPermission(admin(A), "chat:use"));          // inherited from USER
        assertTrue(policy.hasPermission(admin(A), "billing:read:tenant")); // billing:*
        assertFalse(policy.hasPermission(user(A), "billing:read:tenant"));
        assertTrue(policy.hasPermission(PLATFORM, "anything:at:all"));
        assertFalse(AuthzPolicy.implies("billing:*", "billingx"));
    }

    @Test
    public void tenantIsolation() {
        policy.requireTenantAccess(user(A), A);
        assertEquals(AuthzException.Reason.CROSS_TENANT,
                reasonOf(() -> policy.requireTenantAccess(admin(A), B)));
        assertEquals(AuthzException.Reason.CROSS_TENANT,
                reasonOf(() -> policy.requireTenantAccess(admin(A), (String) null)));
        policy.requireTenantAccess(PLATFORM, B);
        policy.requireTenantAccess(PLATFORM, (String) null);
    }

    @Test
    public void effectiveTenantIgnoresSpoofedTenantForNonPlatform() {
        assertEquals(A, policy.effectiveTenant(user(A), null));
        assertEquals(A, policy.effectiveTenant(user(A), A));
        assertEquals(AuthzException.Reason.CROSS_TENANT,
                reasonOf(() -> policy.effectiveTenant(admin(A), B)));
        assertEquals(B, policy.effectiveTenant(PLATFORM, B));
    }

    @Test
    public void unapprovedAndExpiredAccountsAreRejected() {
        Principal pending = new Principal("p", A, Set.of(Roles.USER), Set.of(),
                AccountStatus.PENDING_APPROVAL, null, Map.of());
        assertEquals(AuthzException.Reason.ACCOUNT_NOT_ACTIVE, reasonOf(() -> policy.authenticate(pending)));
        assertEquals(AuthzException.Reason.UNAUTHENTICATED, reasonOf(() -> policy.authenticate(null)));

        AuthzPolicy fixedClock = AuthzPolicy.builder()
                .clock(Clock.fixed(Instant.parse("2026-09-19T00:00:00Z"), ZoneOffset.UTC)).build();
        Principal expired = new Principal("e", A, Set.of(Roles.USER), Set.of(),
                AccountStatus.ACTIVE, Instant.parse("2026-09-18T00:00:00Z"), Map.of());
        assertEquals(AuthzException.Reason.EXPIRED, reasonOf(() -> fixedClock.authenticate(expired)));
        assertEquals(401, new AuthzException(AuthzException.Reason.EXPIRED, "x").httpStatus());
    }

    @Test
    public void selfOrManager() {
        Principal u = user(A);
        policy.requireSelfOr(u, u.subject(), A, Roles.TENANT_ADMIN);
        policy.requireSelfOr(admin(A), u.subject(), A, Roles.TENANT_ADMIN);
        assertEquals(AuthzException.Reason.CROSS_TENANT,
                reasonOf(() -> policy.requireSelfOr(admin(B), u.subject(), A, Roles.TENANT_ADMIN)));
        assertEquals(AuthzException.Reason.MISSING_ROLE,
                reasonOf(() -> policy.requireSelfOr(user(A), "other", A, Roles.TENANT_ADMIN)));
    }

    @Test
    public void deniesAreAudited() {
        audit.clear();
        reasonOf(() -> policy.requireRole(user(A), Roles.PLATFORM_ADMIN));
        Decision last = audit.get(audit.size() - 1);
        assertFalse(last.allowed());
        assertEquals("role:" + Roles.PLATFORM_ADMIN, last.action());
    }

    @Test
    public void customHierarchyAndCycleDetection() {
        RoleHierarchy h = RoleHierarchy.parse("owner > manager > agent; auditor > agent");
        assertTrue(h.satisfies(Set.of("owner"), "agent"));
        assertFalse(h.satisfies(Set.of("auditor"), "manager"));
        try {
            RoleHierarchy.parse("a > b > a");
            fail("cycle must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @RequiresRole(Roles.TENANT_ADMIN)
    static final class AdminHandler implements SleeEventHandler {
        final AtomicBoolean called = new AtomicBoolean();

        @Override
        @RequiresPermission("users:approve")
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            called.set(true);
        }
    }

    @Test
    public void annotationsAreEnforcedByDecorator() throws Exception {
        AtomicReference<Principal> current = new AtomicReference<>();
        AtomicReference<AuthzException> denied = new AtomicReference<>();
        AdminHandler target = new AdminHandler();
        SleeEventHandler h = AuthorizingEventHandler.wrap(target, policy,
                ev -> current.get(), (ev, aci, ex) -> denied.set(ex));

        current.set(user(A));
        h.onEvent(null, null);
        assertFalse(target.called.get());
        assertEquals(AuthzException.Reason.MISSING_ROLE, denied.get().reason());

        current.set(admin(A));
        h.onEvent(null, null);
        assertTrue(target.called.get());

        EventAuthorizer ea = EventAuthorizer.of(AdminHandler.class);
        assertEquals(Roles.TENANT_ADMIN, ea.requiredRole());
        assertEquals("users:approve", ea.requiredPermissions()[0]);
    }
}
