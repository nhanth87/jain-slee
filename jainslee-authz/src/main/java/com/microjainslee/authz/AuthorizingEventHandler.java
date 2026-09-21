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

import java.util.function.Function;

/**
 * Decorator for plain {@link SleeEventHandler}s (routers, listeners that are
 * not container-managed SBBs): enforces the delegate type's
 * {@link RequiresRole} / {@link RequiresPermission} / {@link AllowInactive}
 * before delegating. For SBBs use {@link EventAuthorizer} inside
 * {@code onEvent} so {@code @InjectRa} and pooling still see the SBB class.
 */
public final class AuthorizingEventHandler implements SleeEventHandler {

    /** Called instead of the delegate when authorization fails. */
    @FunctionalInterface
    public interface DenialHandler {
        void onDenied(SleeEvent event, ActivityContextInterface aci, AuthzException denial) throws Exception;
    }

    private final SleeEventHandler delegate;
    private final AuthzPolicy policy;
    private final Function<SleeEvent, Principal> principalOf;
    private final DenialHandler onDenied;
    private final EventAuthorizer authorizer;

    private AuthorizingEventHandler(SleeEventHandler delegate, AuthzPolicy policy,
                                    Function<SleeEvent, Principal> principalOf,
                                    DenialHandler onDenied) {
        this.delegate = delegate;
        this.policy = policy;
        this.principalOf = principalOf;
        this.onDenied = onDenied;
        this.authorizer = EventAuthorizer.of(delegate.getClass());
    }

    public static AuthorizingEventHandler wrap(SleeEventHandler delegate, AuthzPolicy policy,
                                               Function<SleeEvent, Principal> principalOf,
                                               DenialHandler onDenied) {
        return new AuthorizingEventHandler(delegate, policy, principalOf, onDenied);
    }

    public SleeEventHandler delegate() {
        return delegate;
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) throws Exception {
        try {
            authorizer.authorize(policy, principalOf.apply(event));
        } catch (AuthzException denied) {
            onDenied.onDenied(event, aci, denied);
            return;
        }
        delegate.onEvent(event, aci);
    }
}
