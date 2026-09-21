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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Requirements declared on one handler type ({@link RequiresRole},
 * {@link RequiresPermission}, {@link AllowInactive} on the class or its
 * {@code onEvent} method), resolved once and checked per event.
 *
 * <p>Use inside an SBB so container features bound to the SBB class
 * ({@code @InjectRa}, pooling) keep working:
 *
 * <pre>{@code
 * @RequiresRole(Roles.TENANT_ADMIN)
 * public final class CompanyAdminSbb implements Sbb, SleeEventHandler {
 *     private static final EventAuthorizer AUTHZ = EventAuthorizer.of(CompanyAdminSbb.class);
 *
 *     public void onEvent(SleeEvent ev, ActivityContextInterface aci) {
 *         Principal p;
 *         try {
 *             p = AUTHZ.authorize(policy, principalOf(ev));
 *         } catch (AuthzException denied) {
 *             reply(ev, denied.httpStatus(), denied.code());
 *             return;
 *         }
 *         ...
 *     }
 * }
 * }</pre>
 */
public final class EventAuthorizer {

    private final String requiredRole;
    private final String[] requiredPermissions;
    private final boolean allowInactive;

    private EventAuthorizer(String requiredRole, String[] requiredPermissions, boolean allowInactive) {
        this.requiredRole = requiredRole;
        this.requiredPermissions = requiredPermissions;
        this.allowInactive = allowInactive;
    }

    public static EventAuthorizer of(Class<?> handlerType) {
        Method onEvent = findOnEvent(handlerType);
        RequiresRole role = pick(onEvent, handlerType, RequiresRole.class);
        RequiresPermission perms = pick(onEvent, handlerType, RequiresPermission.class);
        return new EventAuthorizer(
                role == null ? null : role.value(),
                perms == null ? new String[0] : perms.value().clone(),
                pick(onEvent, handlerType, AllowInactive.class) != null);
    }

    /** Programmatic equivalent of the annotations. */
    public static EventAuthorizer requiring(String role, String... permissions) {
        return new EventAuthorizer(role, permissions.clone(), false);
    }

    /**
     * Checks the principal against the declared requirements and returns it.
     *
     * @throws AuthzException when any requirement fails
     */
    public Principal authorize(AuthzPolicy policy, Principal p) {
        if (allowInactive) {
            policy.requireAuthenticated(p);
        } else {
            policy.authenticate(p);
        }
        if (requiredRole != null) {
            policy.requireRole(p, requiredRole);
        }
        for (String perm : requiredPermissions) {
            policy.requirePermission(p, perm);
        }
        return p;
    }

    public String requiredRole() {
        return requiredRole;
    }

    public String[] requiredPermissions() {
        return requiredPermissions.clone();
    }

    public boolean allowInactive() {
        return allowInactive;
    }

    private static Method findOnEvent(Class<?> type) {
        try {
            return type.getMethod("onEvent", SleeEvent.class, ActivityContextInterface.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static <A extends Annotation> A pick(Method m, Class<?> type, Class<A> ann) {
        A onMethod = m == null ? null : m.getAnnotation(ann);
        return onMethod != null ? onMethod : type.getAnnotation(ann);
    }
}
