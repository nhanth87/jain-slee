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

import com.microjainslee.api.Sbb;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Advanced CMP edge cases.
 */
public class CmpAdvancedEdgeTest {

    private MicroSleeContainer container;

    @Before
    public void start() {
        container = new MicroSleeContainer();
        container.start();
    }

    @After
    public void stop() {
        if (container != null) container.stop();
    }

    /** SBB with two CMP fields: counter (int) and label (String). */
    public abstract static class TwoFieldSbb extends CmpBackedSbb {
        public int readCounter() {
            try {
                Method getter = TwoFieldSbb.class.getMethod("getCounter");
                return ((Integer) cmpRead(getter)).intValue();
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        public void writeCounter(int v) {
            try {
                Method setter = TwoFieldSbb.class.getMethod("setCounter", int.class);
                cmpWrite(setter, Integer.valueOf(v));
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        public String readLabel() {
            try {
                Method getter = TwoFieldSbb.class.getMethod("getLabel");
                return (String) cmpRead(getter);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        public void writeLabel(String v) {
            try {
                Method setter = TwoFieldSbb.class.getMethod("setLabel", String.class);
                cmpWrite(setter, v);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        public abstract int getCounter();
        public abstract void setCounter(int v);
        public abstract String getLabel();
        public abstract void setLabel(String v);
    }

    /**
     * Spin-wait until the container's async activation task has bound the
     * SBB entity id, or give up after the deadline. Replaces ad-hoc
     * {@code Thread.sleep(...)} calls so concurrent-writer tests don't race
     * the parked virtual thread that runs {@code sbbCreate} /
     * {@code setSbbEntityId}.
     */
    private void waitForActivation(TwoFieldSbb sbb, long maxMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (sbb.getSbbEntityId() == null) {
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException(
                        "SBB activation did not complete within " + maxMillis + "ms");
            }
            Thread.sleep(5);
        }
    }

    private TwoFieldSbb newConcreteTwoField() {
        return new TwoFieldSbb() {
            private int counter;
            private String label;
            @Override public int getCounter() { return counter; }
            @Override public void setCounter(int v) { this.counter = v; }
            @Override public String getLabel() { return label; }
            @Override public void setLabel(String v) { this.label = v; }
        };
    }

    @Test
    public void twoFieldsCoexistInStore() throws Exception {
        TwoFieldSbb sbb = newConcreteTwoField();
        container.registerSbb("two-fields", (Sbb) sbb);
        Thread.sleep(100);
        sbb.writeCounter(7);
        sbb.writeLabel("hello");
        assertEquals(7, sbb.readCounter());
        assertEquals("hello", sbb.readLabel());
        InMemoryCmpFieldStore store = (InMemoryCmpFieldStore) container.getCmpFieldStore();
        Map<String, Object> persisted = store.load("two-fields");
        assertEquals(Integer.valueOf(7), persisted.get("counter"));
        assertEquals("hello", persisted.get("label"));
    }

    @Test
    public void orphanSbbCannotAccessCmpStore() {
        TwoFieldSbb orphan = newConcreteTwoField();
        try {
            orphan.readCounter();
            org.junit.Assert.fail("orphan SBB must not access CMP store");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("entity id"));
        }
    }

    @Test
    public void concurrentWritersToSameFieldLastWriterWins() throws Exception {
        TwoFieldSbb sbb = newConcreteTwoField();
        container.registerSbb("concurrent-target", (Sbb) sbb);
        waitForActivation(sbb, 5000);

        int threadCount = 16;
        int writesPerThread = 200;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        for (int t = 0; t < threadCount; t++) {
            final int tid = t;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < writesPerThread; i++) {
                            sbb.writeCounter(tid * 10000 + i);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals("no errors expected", 0, errors.get());
        InMemoryCmpFieldStore store = (InMemoryCmpFieldStore) container.getCmpFieldStore();
        Map<String, Object> persisted = store.load("concurrent-target");
        assertNotNull(persisted.get("counter"));
    }

    @Test
    public void serializableCompositeFieldRoundTrips() throws Exception {
        TwoFieldSbb sbb = newConcreteTwoField();
        container.registerSbb("composite", (Sbb) sbb);
        Thread.sleep(100);

        InMemoryCmpFieldStore store = (InMemoryCmpFieldStore) container.getCmpFieldStore();
        List<String> list = new ArrayList<String>();
        list.add("alpha");
        list.add("beta");
        Map<String, Object> state = new HashMap<String, Object>();
        state.put("counter", Integer.valueOf(1));
        state.put("label", "owner");
        state.put("tags", list);
        store.store("composite", state);

        Map<String, Object> loaded = store.load("composite");
        Object tags = loaded.get("tags");
        assertNotNull(tags);
        assertTrue("tags should be a List", tags instanceof List);
        assertFalse("loaded list must be a copy", tags == list);
        assertEquals(list, tags);
    }

    @Test
    public void overwritingExistingFieldPreservesOtherFields() throws Exception {
        TwoFieldSbb sbb = newConcreteTwoField();
        container.registerSbb("overwrite", (Sbb) sbb);
        Thread.sleep(100);
        sbb.writeCounter(10);
        sbb.writeLabel("first");
        sbb.writeCounter(20);
        assertEquals(20, sbb.readCounter());
        assertEquals("first", sbb.readLabel());
    }

    @Test
    public void storingNullValueRemovesField() throws Exception {
        TwoFieldSbb sbb = newConcreteTwoField();
        container.registerSbb("null-set", (Sbb) sbb);
        Thread.sleep(100);
        sbb.writeLabel("first");
        assertEquals("first", sbb.readLabel());
        sbb.writeLabel(null);
        assertEquals(null, sbb.readLabel());
        // The label field is now absent from the store
        InMemoryCmpFieldStore store = (InMemoryCmpFieldStore) container.getCmpFieldStore();
        assertFalse(store.load("null-set").containsKey("label"));
    }

    @Test
    public void manyFieldsRoundTrip() throws Exception {
        // Create an SBB with 10 fields and verify all persist.
        abstract class TenFieldSbb extends CmpBackedSbb {
            public Integer readField(int i) {
                try {
                    Method g = TenFieldSbb.class.getMethod("getField" + i);
                    return (Integer) cmpRead(g);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            public void writeField(int i, int v) {
                try {
                    Method s = TenFieldSbb.class.getMethod("setField" + i, int.class);
                    cmpWrite(s, Integer.valueOf(v));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            public abstract int getField0();
            public abstract void setField0(int v);
            public abstract int getField1();
            public abstract void setField1(int v);
            public abstract int getField2();
            public abstract void setField2(int v);
            public abstract int getField3();
            public abstract void setField3(int v);
            public abstract int getField4();
            public abstract void setField4(int v);
            public abstract int getField5();
            public abstract void setField5(int v);
            public abstract int getField6();
            public abstract void setField6(int v);
            public abstract int getField7();
            public abstract void setField7(int v);
            public abstract int getField8();
            public abstract void setField8(int v);
            public abstract int getField9();
            public abstract void setField9(int v);
        }
        TenFieldSbb sbb = new TenFieldSbb() {
            private int[] f = new int[10];
            private int g(int i) { return f[i]; }
            private void s(int i, int v) { f[i] = v; }
            @Override public int getField0() { return g(0); }
            @Override public void setField0(int v) { s(0, v); }
            @Override public int getField1() { return g(1); }
            @Override public void setField1(int v) { s(1, v); }
            @Override public int getField2() { return g(2); }
            @Override public void setField2(int v) { s(2, v); }
            @Override public int getField3() { return g(3); }
            @Override public void setField3(int v) { s(3, v); }
            @Override public int getField4() { return g(4); }
            @Override public void setField4(int v) { s(4, v); }
            @Override public int getField5() { return g(5); }
            @Override public void setField5(int v) { s(5, v); }
            @Override public int getField6() { return g(6); }
            @Override public void setField6(int v) { s(6, v); }
            @Override public int getField7() { return g(7); }
            @Override public void setField7(int v) { s(7, v); }
            @Override public int getField8() { return g(8); }
            @Override public void setField8(int v) { s(8, v); }
            @Override public int getField9() { return g(9); }
            @Override public void setField9(int v) { s(9, v); }
        };
        container.registerSbb("ten-fields", (Sbb) sbb);
        Thread.sleep(100);
        for (int i = 0; i < 10; i++) {
            sbb.writeField(i, i * 100);
        }
        for (int i = 0; i < 10; i++) {
            assertEquals(Integer.valueOf(i * 100), sbb.readField(i));
        }
    }
}
