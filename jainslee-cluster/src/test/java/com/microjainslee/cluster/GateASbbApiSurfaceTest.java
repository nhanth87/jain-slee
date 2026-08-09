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

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbContext;
import com.microjainslee.api.SbbLocalObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate A — application SBB API surface must not expose HA checkpoint.
 */
class GateASbbApiSurfaceTest {

    @Test
    void sbbContextHasNoCheckpointEntry() {
        assertNoCheckpointMethod(SbbContext.class);
    }

    @Test
    void sbbLocalObjectHasNoCheckpointEntry() {
        assertNoCheckpointMethod(SbbLocalObject.class);
    }

    @Test
    void sbbHasNoCheckpointEntry() {
        assertNoCheckpointMethod(Sbb.class);
    }

    @Test
    void raCheckpointBridgeIsTheHaEntry() {
        assertThat(RaCheckpointBridge.class.getMethods())
                .extracting(Method::getName)
                .contains("checkpoint", "bindContainer", "bindFunction");
    }

    private static void assertNoCheckpointMethod(Class<?> type) {
        for (Method m : type.getMethods()) {
            assertThat(m.getName().toLowerCase())
                    .as("%s.%s must not be an HA checkpoint entry", type.getSimpleName(), m.getName())
                    .doesNotContain("checkpoint");
        }
    }
}
