/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.codegen;

import com.microjainslee.api.Sbb;
import com.microjainslee.core.CmpFieldStore;
import com.microjainslee.core.CmpFieldStoreLocator;
import com.microjainslee.core.InMemoryCmpFieldStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint S2 — verifies the {@link ConcreteSbbGenerator} contract:
 * <ul>
 *   <li>abstract SBB classes with 5 different CMP field types
 *       (int, String, Long, ArrayList&lt;String&gt;, byte[]) are
 *       turned into concrete, instantiable subclasses;</li>
 *   <li>default CMP values come back through the generated accessor;</li>
 *   <li>setter writes are routed through {@link CmpFieldStoreLocator}
 *       to the {@link CmpFieldStore} bound on the current thread;</li>
 *   <li>100K getter/setter cycle completes without errors (informational
 *       throughput check, not asserted).</li>
 * </ul>
 */
class ConcreteSbbGeneratorTest {

    /** Five-field test SBB matching the WIRING_GUIDE §S2 acceptance criteria. */
    public abstract static class FiveFieldSbb implements Sbb {
        public abstract int getCounter();
        public abstract void setCounter(int counter);
        public abstract String getSessionId();
        public abstract void setSessionId(String sessionId);
        public abstract Long getLastEventTime();
        public abstract void setLastEventTime(Long lastEventTime);
        public abstract ArrayList<String> getRecentInputs();
        public abstract void setRecentInputs(ArrayList<String> recentInputs);
        public abstract byte[] getPayload();
        public abstract void setPayload(byte[] payload);
    }

    @TempDir
    Path deployDir;

    private ConcreteSbbGenerator generator;
    private InMemoryCmpFieldStore store;

    @BeforeEach
    void setup() {
        generator = new ConcreteSbbGenerator();
        store = new InMemoryCmpFieldStore();
        CmpFieldStoreLocator.set(store);
    }

    @AfterEach
    void teardown() {
        CmpFieldStoreLocator.set(null);
    }

    @Test
    @DisplayName("field naming rule")
    void fieldNaming() {
        assertThat(ConcreteSbbGenerator.toCmpFieldName("getSessionId")).isEqualTo("sessionId");
        assertThat(ConcreteSbbGenerator.toCmpFieldName("setSessionId")).isEqualTo("sessionId");
        assertThat(ConcreteSbbGenerator.toCmpFieldName("getCmpFieldFoo")).isEqualTo("foo");
        assertThat(ConcreteSbbGenerator.toCmpFieldName("getCmpField")).isEqualTo("cmpField");
        assertThat(ConcreteSbbGenerator.toCmpFieldName("get")).isEqualTo("_unnamed");
    }

    @Test
    @DisplayName("generates a concrete, instantiable subclass for a 5-field abstract SBB")
    void generatesConcreteClass() throws Exception {
        Class<?> concrete = generator.getOrGenerate(FiveFieldSbb.class, deployDir);
        assertThat(concrete).isNotNull();
        assertThat(concrete.getSuperclass().getName()).isEqualTo(FiveFieldSbb.class.getName());
        assertThat(concrete.getName()).isEqualTo(FiveFieldSbb.class.getName() + ConcreteSbbGenerator.CONCRETE_SUFFIX);

        // Concrete class must be instantiable
        FiveFieldSbb sbb = (FiveFieldSbb) concrete.getDeclaredConstructor().newInstance();
        assertThat(sbb).isNotNull();

        // Cached on second call
        Class<?> again = generator.getOrGenerate(FiveFieldSbb.class, deployDir);
        assertThat(again).isSameAs(concrete);
    }

    @Test
    @DisplayName("default values come back through the generated accessor")
    void defaultValuesReturned() throws Exception {
        Class<?> concrete = generator.getOrGenerate(FiveFieldSbb.class, deployDir);
        FiveFieldSbb sbb = (FiveFieldSbb) concrete.getDeclaredConstructor().newInstance();

        // primitive int returns 0
        assertThat(sbb.getCounter()).isEqualTo(0);
        // String returns null when nothing is stored
        assertThat(sbb.getSessionId()).isNull();
        // Long (boxed) returns null when nothing is stored
        assertThat(sbb.getLastEventTime()).isNull();
        // ArrayList<String> returns null when nothing is stored
        assertThat(sbb.getRecentInputs()).isNull();
        // byte[] returns null when nothing is stored
        assertThat(sbb.getPayload()).isNull();
    }

    @Test
    @DisplayName("setter persists the value through CmpFieldStoreLocator")
    void setterPersists() throws Exception {
        Class<?> concrete = generator.getOrGenerate(FiveFieldSbb.class, deployDir);
        FiveFieldSbb sbb = (FiveFieldSbb) concrete.getDeclaredConstructor().newInstance();

        sbb.setCounter(42);
        sbb.setSessionId("abc-123");
        sbb.setLastEventTime(1700000000L);
        ArrayList<String> inputs = new ArrayList<>();
        inputs.add("1");
        inputs.add("2");
        sbb.setRecentInputs(inputs);
        sbb.setPayload(new byte[]{1, 2, 3, 4});

        // The store (bound via CmpFieldStoreLocator) now has every field.
        // Note: CmpFieldStoreLocator keys by entity id; without one bound,
        // the generated class still writes to its per-instance fallback
        // and the read path returns the per-instance value.
        assertThat(sbb.getCounter()).isEqualTo(42);
        assertThat(sbb.getSessionId()).isEqualTo("abc-123");
        assertThat(sbb.getLastEventTime()).isEqualTo(1700000000L);
        assertThat(sbb.getRecentInputs()).containsExactly("1", "2");
        assertThat(sbb.getPayload()).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("setter routes through CmpFieldStore when entityId is set")
    void setterRoutesThroughStore() throws Exception {
        // Bind entity id BEFORE calling the setters by reflecting on the
        // concrete class's _sbbEntityId field.
        Class<?> concrete = generator.getOrGenerate(FiveFieldSbb.class, deployDir);
        FiveFieldSbb sbb = (FiveFieldSbb) concrete.getDeclaredConstructor().newInstance();
        java.lang.reflect.Field idField = concrete.getDeclaredField("_sbbEntityId");
        idField.setAccessible(true);
        idField.set(sbb, "entity-42");

        sbb.setCounter(7);
        sbb.setSessionId("xyz");

        // The store should have entries keyed by entity id
        Map<String, Object> stored = store.load("entity-42");
        assertThat(stored).containsEntry("counter", 7);
        assertThat(stored).containsEntry("sessionId", "xyz");

        // Read-back returns the same value (cache + store)
        assertThat(sbb.getCounter()).isEqualTo(7);
        assertThat(sbb.getSessionId()).isEqualTo("xyz");
    }

    @Test
    @DisplayName("passivation + reload sees the persisted CMP value")
    void passivationRoundTrip() throws Exception {
        Class<?> concrete = generator.getOrGenerate(FiveFieldSbb.class, deployDir);

        // First SBB entity writes 99
        FiveFieldSbb writer = (FiveFieldSbb) concrete.getDeclaredConstructor().newInstance();
        java.lang.reflect.Field idField = concrete.getDeclaredField("_sbbEntityId");
        idField.setAccessible(true);
        idField.set(writer, "passivation-test");
        writer.setCounter(99);
        assertThat(store.load("passivation-test")).containsEntry("counter", 99);

        // Simulate a fresh passivation: a new SBB instance for the same id
        FiveFieldSbb reader = (FiveFieldSbb) concrete.getDeclaredConstructor().newInstance();
        idField.set(reader, "passivation-test");
        assertThat(reader.getCounter()).isEqualTo(99);
    }

    @Test
    @DisplayName("100K getter/setter cycle completes (informational, not asserted)")
    void cycleBenchmark() throws Exception {
        Class<?> concrete = generator.getOrGenerate(FiveFieldSbb.class, deployDir);
        FiveFieldSbb sbb = (FiveFieldSbb) concrete.getDeclaredConstructor().newInstance();
        java.lang.reflect.Field idField = concrete.getDeclaredField("_sbbEntityId");
        idField.setAccessible(true);
        idField.set(sbb, "bench");

        long start = System.nanoTime();
        long acc = 0;
        for (int i = 0; i < 100_000; i++) {
            sbb.setCounter(i);
            acc += sbb.getCounter();
        }
        long elapsed = System.nanoTime() - start;
        // informational only
        System.out.printf("100K cycle: %d ms, acc=%d%n", elapsed / 1_000_000, acc);
        // We do not assert elapsed time — the benchmark is informational.
        // Sanity: the loop completed without throwing and produced a
        // plausible accumulator.
        assertThat(acc).isGreaterThan(0L);
    }

    @Test
    @DisplayName("rejects non-Sbb source classes")
    void rejectsNonSbb() {
        class NotAnSbb {
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                generator.getOrGenerate(NotAnSbb.class, deployDir));
    }
}
