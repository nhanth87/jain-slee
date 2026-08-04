/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.codegen;

import com.microjainslee.api.OffHeapBindable;
import com.microjainslee.codegen.example.OffHeapDemoSessionSbb;
import com.microjainslee.core.offheap.OffHeapArena;
import com.microjainslee.core.offheap.OffHeapRuntime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Production-2 soak gate: ≥100k off-heap entity slots with codegen accessors.
 */
class OffHeapSoakTest {

    private static final int N = 100_000;

    @TempDir
    Path deployDir;

    @Test
    @DisplayName("100k allocate/bind/write/read/release stays consistent")
    void soakHundredThousandSlots() throws Exception {
        ConcreteSbbGenerator generator = new ConcreteSbbGenerator();
        Class<?> concrete = generator.getOrGenerate(OffHeapDemoSessionSbb.class, deployDir);

        try (OffHeapArena arena = new OffHeapArena("soak",
                OffHeapRuntime.layoutFor(OffHeapDemoSessionSbb.class), N)) {
            Object[] holders = new Object[N];
            long[] bases = new long[N];

            for (int i = 0; i < N; i++) {
                String id = "e" + i;
                long base = arena.allocate(id);
                Object sbb = concrete.getDeclaredConstructor().newInstance();
                OffHeapBindable b = (OffHeapBindable) sbb;
                b.setEntityId(id);
                b.bindSlot(base);
                call(sbb, "setMsisdn", "849" + (1_000_000 + (i % 1_000_000)));
                call(sbb, "setMenuState", i % 17);
                call(sbb, "setStartedAt", 1_700_000_000_000L + i);
                holders[i] = sbb;
                bases[i] = base;
            }

            // Spot-check first / middle / last — proves state lives in the arena.
            assertField(holders[0], "8491000000", 0, 1_700_000_000_000L);
            assertField(holders[N / 2], "849" + (1_000_000 + ((N / 2) % 1_000_000)),
                    (N / 2) % 17, 1_700_000_000_000L + (N / 2));
            assertField(holders[N - 1], "849" + (1_000_000 + ((N - 1) % 1_000_000)),
                    (N - 1) % 17, 1_700_000_000_000L + (N - 1));

            // Twin instance on same slot mid-way through soak — off-heap not heap fields.
            Object twin = concrete.getDeclaredConstructor().newInstance();
            ((OffHeapBindable) twin).bindSlot(bases[N / 2]);
            assertThat(call(twin, "getMsisdn")).isEqualTo(call(holders[N / 2], "getMsisdn"));
            assertThat(call(twin, "getMenuState")).isEqualTo(call(holders[N / 2], "getMenuState"));

            for (int i = 0; i < N; i++) {
                ((OffHeapBindable) holders[i]).unbindSlot();
                arena.free("e" + i);
            }
            assertThat(arena.occupiedCount()).isZero();
        }
    }

    private static void assertField(Object sbb, String msisdn, int menu, long started)
            throws Exception {
        assertThat(call(sbb, "getMsisdn")).isEqualTo(msisdn);
        assertThat(call(sbb, "getMenuState")).isEqualTo(menu);
        assertThat(call(sbb, "getStartedAt")).isEqualTo(started);
    }

    private static Object call(Object target, String method, Object... args) throws Exception {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(method) && m.getParameterCount() == args.length) {
                return m.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(method);
    }
}
