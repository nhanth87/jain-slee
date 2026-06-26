/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.InitialEventSelector;

/**
 * Optional hook to customize {@link InitialEventSelector} before root SBB attachment.
 */
@FunctionalInterface
public interface InitialEventSelectorCustomizer {
    void customize(InitialEventSelector selector);
}
