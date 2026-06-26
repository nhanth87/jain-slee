/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.quarkus;

import com.microjainslee.core.MicroSleeContainer;
import io.quarkus.runtime.RuntimeValue;

/**
 * Internal static holder used to expose the build-time-constructed {@link MicroSleeContainer}
 * to the CDI producer at runtime.
 *
 * <p>Because Quarkus splits classes into build-time and run-time classpaths, the deployment
 * module cannot directly inject a runtime bean into the producer. Instead, the recorder
 * stores the constructed container in this holder during static-init, and the producer
 * pulls it out via {@link #get()}.</p>
 *
 * <p>This holder is intentionally package-private: it is not part of the public extension API.</p>
 */
final class MicroJainsleeHolder {

    private static volatile RuntimeValue<MicroSleeContainer> container;

    private MicroJainsleeHolder() {
        // utility
    }

    static void set(RuntimeValue<MicroSleeContainer> value) {
        container = value;
    }

    static RuntimeValue<MicroSleeContainer> get() {
        return container;
    }
}