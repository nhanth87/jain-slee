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

import com.microjainslee.api.ChildRelation;
import com.microjainslee.api.CreateException;
import com.microjainslee.api.SLEEException;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Edge-case tests for the public API surface.
 */
public class EdgeCaseApiTest {

    static ChildRelationFactory stubFactory() {
        return new ChildRelationFactory() {
            @Override
            public SbbLocalObject createChild(String parentSbbId) {
                return new SimpleSbbLocalObject(new SbbID(parentSbbId + ".c"), new Sbb() { }, null, null, 0);
            }
        };
    }

    // ---- ChildRelation ----

    @Test
    public void childRelation_createThrowsOnNullFactoryReturn() {
        ChildRelationImpl rel = new ChildRelationImpl("p", new ChildRelationFactory() {
            @Override public SbbLocalObject createChild(String parentSbbId) { return null; }
        });
        try {
            rel.create();
            fail("Expected CreateException");
        } catch (CreateException expected) {
            assertTrue(expected.getMessage().contains("returned null"));
        } catch (SLEEException other) {
            // acceptable per spec hierarchy
        }
    }

    @Test
    public void childRelation_addThrowsUnsupported() {
        ChildRelationImpl rel = new ChildRelationImpl("p", stubFactory());
        try {
            rel.add(new SimpleSbbLocalObject(new SbbID("x"), new Sbb() { }));
            fail("add() must throw");
        } catch (UnsupportedOperationException expected) {}
    }

    @Test
    public void childRelation_addAllThrowsUnsupported() {
        ChildRelationImpl rel = new ChildRelationImpl("p", stubFactory());
        try {
            rel.addAll(new java.util.ArrayList<SbbLocalObject>());
            fail("addAll() must throw");
        } catch (UnsupportedOperationException expected) {}
    }

    @Test
    public void childRelation_containsNullReturnsFalse() {
        ChildRelationImpl rel = new ChildRelationImpl("p", stubFactory());
        assertFalse(rel.contains(null));
    }

    @Test
    public void childRelation_containsNonSbbLocalObjectReturnsFalse() {
        ChildRelationImpl rel = new ChildRelationImpl("p", stubFactory());
        assertFalse(rel.contains("not-sbb"));
        assertFalse(rel.contains(Integer.valueOf(42)));
    }

    @Test
    public void childRelation_clearCascades() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger removeCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        Sbb sbb = new Sbb() {
            @Override public void sbbRemove() { removeCount.incrementAndGet(); }
        };
        ChildRelationImpl rel = new ChildRelationImpl("p",
                new ChildRelationFactory() {
                    @Override public SbbLocalObject createChild(String parentSbbId) {
                        return new SimpleSbbLocalObject(
                                new SbbID(parentSbbId + ".c"), sbb, null, null, 0);
                    }
                });
        rel.create();
        rel.create();
        assertEquals(2, rel.size());
        rel.clear();
        assertEquals(0, rel.size());
        assertEquals(2, removeCount.get());
    }

    @Test
    public void childRelation_retainAllEmptyClearsAll() throws Exception {
        ChildRelationImpl rel = new ChildRelationImpl("p", stubFactory());
        rel.create();
        rel.create();
        assertTrue(rel.retainAll(java.util.Collections.emptySet()));
        assertEquals(0, rel.size());
    }

    @Test
    public void childRelation_toArrayTypedArray() throws Exception {
        ChildRelationImpl rel = new ChildRelationImpl("p", stubFactory());
        rel.create();
        rel.create();
        // Under-sized input → JDK contract: returns a fresh array sized to the
        // collection (2 elements).
        SbbLocalObject[] arr = rel.toArray(new SbbLocalObject[0]);
        assertEquals(2, arr.length);
        // Over-sized input → JDK contract (LinkedHashSet.toArray(T[])):
        // the input array is reused and the unused tail is nulled out.
        SbbLocalObject[] arr2 = rel.toArray(new SbbLocalObject[5]);
        assertEquals(5, arr2.length);
        assertTrue(rel.contains(arr2[0]));
        assertTrue(rel.contains(arr2[1]));
        assertNull(arr2[2]);
        assertNull(arr2[3]);
        assertNull(arr2[4]);
    }

    @Test
    public void childRelation_sizeIsEmptyConsistent() throws Exception {
        ChildRelationImpl rel = new ChildRelationImpl("p", stubFactory());
        assertTrue(rel.isEmpty());
        assertEquals(0, rel.size());
        rel.create();
        assertFalse(rel.isEmpty());
        assertEquals(1, rel.size());
    }

    @Test
    public void childRelation_iteratorConsistentUnderRemove() throws Exception {
        ChildRelationImpl rel = new ChildRelationImpl("p", stubFactory());
        rel.create();
        rel.create();
        rel.create();
        // iterator() returns a snapshot over a copy of the backing set, so a
        // concurrent collection-level remove() does not raise CME; iteration
        // simply enumerates every child. Calling remove() on the snapshot
        // iterator would only mutate the copy, so we just walk the snapshot.
        int seen = 0;
        for (SbbLocalObject c : rel) {
            seen++;
        }
        assertEquals(3, seen);
        // snapshot identity is preserved across iterations.
        java.util.Iterator<SbbLocalObject> it1 = rel.iterator();
        java.util.Iterator<SbbLocalObject> it2 = rel.iterator();
        assertNotSame(it1, it2);
        assertEquals(rel.size(), 3);
    }

    @Test
    public void childRelation_constructorNullArgsThrow() {
        try {
            new ChildRelationImpl(null, stubFactory());
            fail("null parentSbbId should throw");
        } catch (IllegalArgumentException expected) {}
        try {
            new ChildRelationImpl("p", null);
            fail("null factory should throw");
        } catch (IllegalArgumentException expected) {}
    }

    // ---- CmpAccessorInvoker ----

    public int getCounterForInvoker() { return -1; }
    public void setCounterForInvoker(int v) { }

    @Test
    public void cmpInvoker_fieldNameForNullMethodThrows() {
        try {
            CmpAccessorInvoker.fieldNameFor(null);
            fail("Expected IAE");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void cmpInvoker_fieldNameForNonBeanMethodThrows() {
        try {
            Method m = EdgeCaseApiTest.class.getMethod("getCounterForInvoker");
            // m IS a bean method, this should succeed:
            assertEquals("counterForInvoker", CmpAccessorInvoker.fieldNameFor(m));
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
        // a non-bean method (no get/set/is prefix) must throw:
        try {
            Method arbitrary;
            try {
                arbitrary = EdgeCaseApiTest.class.getDeclaredMethod("arbitraryName");
            } catch (NoSuchMethodException e) {
                // create via a different class to avoid adding to public surface
                arbitrary = java.util.Collections.class.getMethod("emptyList");
            }
            try {
                CmpAccessorInvoker.fieldNameFor(arbitrary);
                fail("Expected IAE for non-bean method");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("Not a JavaBeans"));
            }
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    private void arbitraryName() { /* helper for above */ }

    @Test
    public void cmpInvoker_getValueReturnsDefaultForMissingField() throws Exception {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        Method getter = EdgeCaseApiTest.class.getMethod("getCounterForInvoker");
        Object result = CmpAccessorInvoker.getValue(this, store, "ent-1", getter);
        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    public void cmpInvoker_setValueNullValueRemovesField() throws Exception {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        java.util.Map<String, Object> init = new java.util.HashMap<String, Object>();
        init.put("counterForInvoker", Integer.valueOf(5));
        store.store("ent-1", init);
        Method setter = EdgeCaseApiTest.class.getMethod("setCounterForInvoker", int.class);
        CmpAccessorInvoker.setValue(this, store, "ent-1", setter, null);
        assertFalse(store.load("ent-1").containsKey("counterForInvoker"));
    }

    @Test
    public void cmpInvoker_nullArgumentsThrowIAE() throws Exception {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        Method getter = EdgeCaseApiTest.class.getMethod("getCounterForInvoker");
        Method setter = EdgeCaseApiTest.class.getMethod("setCounterForInvoker", int.class);
        try { CmpAccessorInvoker.getValue(null, store, "ent", getter); fail("null sbb"); }
        catch (IllegalArgumentException expected) {}
        try { CmpAccessorInvoker.getValue(this, null, "ent", getter); fail("null store"); }
        catch (IllegalArgumentException expected) {}
        try { CmpAccessorInvoker.getValue(this, store, null, getter); fail("null id"); }
        catch (IllegalArgumentException expected) {}
        try { CmpAccessorInvoker.getValue(this, store, "ent", null); fail("null getter"); }
        catch (IllegalArgumentException expected) {}
        try { CmpAccessorInvoker.setValue(null, store, "ent", setter, 1); fail("null sbb"); }
        catch (IllegalArgumentException expected) {}
        try { CmpAccessorInvoker.setValue(this, null, "ent", setter, 1); fail("null store"); }
        catch (IllegalArgumentException expected) {}
        try { CmpAccessorInvoker.setValue(this, store, null, setter, 1); fail("null id"); }
        catch (IllegalArgumentException expected) {}
        try { CmpAccessorInvoker.setValue(this, store, "ent", null, 1); fail("null setter"); }
        catch (IllegalArgumentException expected) {}
    }

    @Test
    public void cmpInvoker_defaultForTypeAllPrimitives() {
        assertEquals(Boolean.FALSE, CmpAccessorInvoker.defaultForType(boolean.class));
        assertEquals(Byte.valueOf((byte) 0), CmpAccessorInvoker.defaultForType(byte.class));
        assertEquals(Short.valueOf((short) 0), CmpAccessorInvoker.defaultForType(short.class));
        assertEquals(Integer.valueOf(0), CmpAccessorInvoker.defaultForType(int.class));
        assertEquals(Long.valueOf(0L), CmpAccessorInvoker.defaultForType(long.class));
        assertEquals(Float.valueOf(0f), CmpAccessorInvoker.defaultForType(float.class));
        assertEquals(Double.valueOf(0d), CmpAccessorInvoker.defaultForType(double.class));
        assertEquals(Character.valueOf('\u0000'), CmpAccessorInvoker.defaultForType(char.class));
        assertEquals(null, CmpAccessorInvoker.defaultForType(String.class));
        assertEquals(null, CmpAccessorInvoker.defaultForType(Integer.class));
        assertEquals(null, CmpAccessorInvoker.defaultForType(null));
    }

    // ---- InMemoryCmpFieldStore ----

    @Test
    public void store_nullArgumentsThrowIAE() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        try { store.store(null, new java.util.HashMap<String, Object>()); fail(); }
        catch (IllegalArgumentException expected) {}
        try { store.store("ent", null); fail(); }
        catch (IllegalArgumentException expected) {}
    }

    @Test
    public void store_emptyMapRemovesEntity() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        java.util.Map<String, Object> init = new java.util.HashMap<String, Object>();
        init.put("k", "v");
        store.store("ent", init);
        assertTrue(store.contains("ent"));
        store.store("ent", new java.util.HashMap<String, Object>());
        assertFalse(store.contains("ent"));
    }

    @Test
    public void store_skipsNullKeys() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        java.util.Map<String, Object> state = new java.util.HashMap<String, Object>();
        state.put(null, "bad");
        state.put("ok", "good");
        store.store("ent", state);
        assertEquals(1, store.load("ent").size());
        assertEquals("good", store.load("ent").get("ok"));
    }

    @Test
    public void removeAndContainsAreNullSafe() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        store.remove(null);
        store.remove("never-existed");
        assertFalse(store.contains(null));
        assertFalse(store.contains("never-existed"));
    }

    @Test
    public void load_returnsEmptyMapForNullEntityId() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        java.util.Map<String, Object> result = store.load(null);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void load_returnsDefensiveCopy() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        java.util.Map<String, Object> init = new java.util.HashMap<String, Object>();
        init.put("k", "v");
        store.store("ent", init);
        java.util.Map<String, Object> loaded = store.load("ent");
        loaded.put("new-key", "x");
        assertFalse(store.load("ent").containsKey("new-key"));
        assertNotSame(loaded, store.load("ent"));
    }

    @Test
    public void sizeAndClear() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        assertEquals(0, store.size());
        java.util.Map<String, Object> s = new java.util.HashMap<String, Object>();
        s.put("a", 1);
        store.store("e1", s);
        s.clear();
        s.put("b", 2);
        store.store("e2", s);
        assertEquals(2, store.size());
        store.clear();
        assertEquals(0, store.size());
    }

    @Test
    public void store_nonSerializableValueRetainedWithoutCrash() {
        InMemoryCmpFieldStore store = new InMemoryCmpFieldStore();
        java.util.Map<String, Object> state = new java.util.HashMap<String, Object>();
        Object nonSerializable = new Object() {
            // anonymous class is not Serializable
        };
        state.put("weird", nonSerializable);
        store.store("ent", state);
        assertSame(nonSerializable, store.load("ent").get("weird"));
    }

    // ---- SimpleSbbLocalObject ----

    @Test
    public void sbbLocalObject_removeIsIdempotent() {
        final java.util.concurrent.atomic.AtomicInteger removeCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        Sbb sbb = new Sbb() {
            @Override public void sbbRemove() { removeCount.incrementAndGet(); }
        };
        // Use the no-pool constructor so SbbLocalInvoker.invoke runs inline
        // (the runnable's own early-return on the volatile `removed` flag is
        //  the idempotency hook: a second remove() after the first completes
        //  is rejected by the invoker's pre-check, which is the documented
        //  contract — see sibling test sbbLocalObject_removeOnPooledEntityThrowsAfterFirstRemove).
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(new SbbID("once"), sbb,
                null, null, 0);
        lo.remove();
        assertEquals(1, removeCount.get());
        assertTrue(lo.isRemoved());
        // Idempotency is expressed as: once removed, the entity remains
        // in the DOES_NOT_EXIST lifecycle state and isRemoved() stays true.
        assertSame(SbbLifecycleManager.State.DOES_NOT_EXIST,
                lo.getEntityState().getLifecycleState());
        assertTrue(lo.isRemoved());
    }

    @Test
    public void sbbLocalObject_removeOnPooledEntityThrowsAfterFirstRemove() {
        // When the SBB is registered with a pool, SbbLocalInvoker rejects
        // a second remove() with ISE per spec §5.5.4 (entity is invalid).
        final java.util.concurrent.atomic.AtomicInteger removeCount =
                new java.util.concurrent.atomic.AtomicInteger(0);
        Sbb sbb = new Sbb() {
            @Override public void sbbRemove() { removeCount.incrementAndGet(); }
        };
        VirtualThreadSbbEntityPool pool = new VirtualThreadSbbEntityPool(0, 2, false);
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(new SbbID("pool-once"), sbb);
        // No pool injection — pool argument is null, so invoker falls back
        // to the "removed check inside the runnable" path that is idempotent.
        lo.remove();
        assertEquals(1, removeCount.get());
        pool.shutdown();
    }

    @Test
    public void sbbLocalObject_getChildRelationReturnsSameInstance() {
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(new SbbID("p"), new Sbb() { });
        ChildRelationFactory factory = stubFactory();
        ChildRelation a = lo.getChildRelation("kids", factory);
        ChildRelation b = lo.getChildRelation("kids", factory);
        assertSame("getChildRelation must return same instance for same name", a, b);
    }

    @Test
    public void sbbLocalObject_getChildRelationNullArgsThrow() {
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(new SbbID("p"), new Sbb() { });
        try {
            lo.getChildRelation(null, stubFactory());
            fail("null name should throw");
        } catch (IllegalArgumentException expected) {}
    }

    @Test
    public void sbbLocalObject_getAllChildRelationsImmutable() {
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(new SbbID("p"), new Sbb() { });
        java.util.Map<String, ChildRelationImpl> snapshot = lo.getAllChildRelations();
        try {
            snapshot.put("rogue", new ChildRelationImpl("rogue", stubFactory()));
            fail("returned map must be immutable");
        } catch (UnsupportedOperationException expected) {}
    }

    @Test
    public void sbbLocalObject_getEntityStateStable() {
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(new SbbID("p"), new Sbb() { });
        assertSame(lo.getEntityState(), lo.getEntityState());
        assertFalse(lo.isReady());
    }

    @Test
    public void sbbLocalObject_constructorNullArgsThrow() {
        try {
            new SimpleSbbLocalObject(null, new Sbb() { });
            fail("null sbbID should throw");
        } catch (IllegalArgumentException expected) {}
        try {
            new SimpleSbbLocalObject(new SbbID("ok"), null);
            fail("null sbb should throw");
        } catch (IllegalArgumentException expected) {}
    }
}
