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

import java.util.Optional;

/**
 * SPI turning transport credentials into a {@link Principal}: a JWT bearer
 * token, an OAuth session cookie, a SIP identity, a Diameter origin-host …
 * Implementations must verify the credential (signature, expiry) — an
 * unverifiable credential resolves to {@link Optional#empty()}.
 *
 * @param <C> credential type
 */
@FunctionalInterface
public interface PrincipalResolver<C> {

    Optional<Principal> resolve(C credential);
}
