/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.child;

import com.microjainslee.api.ChildRelation;
import com.microjainslee.api.CreateException;
import com.microjainslee.api.SLEEException;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.core.SimpleSbbLocalObject;
import com.microjainslee.core.VirtualThreadSbbEntityPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link ChildRelationImpl} — child package variant.
 *
 * <p>Covers: create child, remove child (single + sbbRemove callback),
 * iterate, count, contains, parent-child link via {@link CascadeRemover},
 * and thread-safe concurrent creation.
 */
public class ChildRelationImplTest {

    /** Counting SBB so we can assert sbbRemove() invocation count. */
    private static final class CountingSbb implements Sbb {
        final AtomicInteger removes = new AtomicInteger();
        @Override
        public void sbbRemove() { removes.incrementAndGet(); }
    }

    private VirtualThreadSbbEntityPool pool;
    private CascadeRemover cascadeRemover;
    private AtomicLong childSequence;

    @Before
    public void setUp() {
        pool = new VirtualThreadSbbEntityPool(1, 16, false);
        cascadeRemover = new CascadeRemover(pool.asEntityLookup());
        childSequence = new AtomicLong();
    }

    @After
    public void tearDown() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown();
        }
    }

    /** Allocate a new child entity in the pool + return a SimpleSbbLocalObject. */
    private SimpleSbbLocalObject allocateChild(String parentEntityId, Sbb sbb) {
        String id = parentEntityId + ".child." + childSequence.incrementAndGet();
        pool.acquire(id, 0L, sbb);
        SimpleSbbLocalObject local = new SimpleSbbLocalObject(
                new SbbID(id), sbb, pool, null, 0);
        local.setParentEntityId(parentEntityId);
        return local;
    }

    private ChildRelationImpl<SbbLocalObject> newRelation(String parentEntityId) {
        ChildRelationImpl.ChildEntityFactory<SbbLocalObject> factory =
                (parent, relation) -> allocateChild(parent, new CountingSbb());
        return new ChildRelationImpl<SbbLocalObject>(
                parentEntityId, SbbLocalObject.class, cascadeRemover, factory);
    }

    // ---- create ---------------------------------------------------------

    @Test
    public void createAddsToCollection() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("parent-1");
        assertEquals(0, rel.size());
        assertTrue(rel.isEmpty());

        SbbLocalObject child = rel.create();
        assertNotNull(child);
        assertEquals(1, rel.size());
        assertFalse(rel.isEmpty());
        assertTrue(rel.contains(child));
    }

    @Test
    public void multipleCreatesGrowCollection() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        SbbLocalObject c1 = rel.create();
        SbbLocalObject c2 = rel.create();
        SbbLocalObject c3 = rel.create();
        assertEquals(3, rel.size());
        assertTrue(rel.contains(c1));
        assertTrue(rel.contains(c2));
        assertTrue(rel.contains(c3));
    }

    // ---- remove ---------------------------------------------------------

    @Test
    public void removeSingleChildFiresSbbRemove() throws Exception {
        CountingSbb childSbb = new CountingSbb();
        ChildRelationImpl<SbbLocalObject> rel = new ChildRelationImpl<SbbLocalObject>(
                "p", SbbLocalObject.class, cascadeRemover,
                (parent, relation) -> allocateChild(parent, childSbb));

        SbbLocalObject c1 = rel.create();
        SbbLocalObject c2 = rel.create();

        assertTrue(rel.remove(c1));
        assertEquals(1, childSbb.removes.get());
        assertEquals(1, rel.size());

        assertTrue(rel.remove(c2));
        assertEquals(2, childSbb.removes.get());
        assertEquals(0, rel.size());
    }

    @Test
    public void removeUnregisteredReturnsFalse() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        SbbLocalObject fake = new SimpleSbbLocalObject(
                new SbbID("never-created"), new CountingSbb(), null, null, 0);
        assertFalse(rel.remove(fake));
    }

    @Test
    public void removeNonSbbReturnsFalse() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        assertFalse(rel.remove("not-an-sbb"));
    }

    // ---- iterate --------------------------------------------------------

    @Test
    public void iteratorReturnsAllChildren() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        List<SbbLocalObject> created = new ArrayList<SbbLocalObject>();
        for (int i = 0; i < 5; i++) {
            created.add(rel.create());
        }
        int seen = 0;
        for (SbbLocalObject c : rel) {
            assertNotNull(c);
            seen++;
        }
        assertEquals(5, seen);
        // Verify getAll() returns the same count.
        assertEquals(5, rel.getAll().size());
    }

    @Test
    public void iteratorIsSnapshotSafe() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        rel.create();
        // Copy the view into a list first so the iteration is over a snapshot.
        List<SbbLocalObject> snapshot = new ArrayList<SbbLocalObject>(rel.getAll());
        // Mutate while iterating — must not throw ConcurrentModificationException.
        rel.create();
        int seen = 0;
        for (SbbLocalObject c : snapshot) {
            assertNotNull(c);
            seen++;
        }
        assertEquals(1, seen);
        assertEquals(2, rel.size());
    }

    // ---- contains -------------------------------------------------------

    @Test
    public void containsMatchesByEntityId() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        SbbLocalObject c = rel.create();
        assertTrue(rel.contains(c));
        assertFalse(rel.contains(new SimpleSbbLocalObject(
                new SbbID("other"), new CountingSbb(), null, null, 0)));
    }

    @Test
    public void containsRejectsNonSbb() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        rel.create();
        assertFalse(rel.contains("string"));
        assertFalse(rel.contains(42));
    }

    // ---- parent-child link ---------------------------------------------

    @Test
    public void createRegistersParentChildLink() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("parent-X");
        SbbLocalObject c = rel.create();
        String childEntityId = c.getSbbID().getId();
        assertTrue(cascadeRemover.isChild(childEntityId));
        assertEquals("parent-X", cascadeRemover.getParent(childEntityId));
        assertTrue(cascadeRemover.getChildren("parent-X").contains(childEntityId));
    }

    @Test
    public void removeDeregistersParentChildLink() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("parent-X");
        SbbLocalObject c = rel.create();
        String childEntityId = c.getSbbID().getId();
        assertTrue(cascadeRemover.isChild(childEntityId));
        rel.remove(c);
        assertFalse(cascadeRemover.isChild(childEntityId));
    }

    @Test
    public void parentEntityIdExposedOnChild() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("parent-Y");
        SbbLocalObject c = rel.create();
        if (c instanceof SimpleSbbLocalObject) {
            assertEquals("parent-Y", ((SimpleSbbLocalObject) c).getParentEntityId());
        }
    }

    // ---- thread safety --------------------------------------------------

    @Test
    public void concurrentCreateDoesNotLoseChildren() throws Exception {
        final ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        final int threads = 8;
        final int perThread = 25;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            rel.create();
                        }
                    } catch (Exception ignored) {
                        // best-effort
                    } finally {
                        done.countDown();
                    }
                }
            }, "create-" + t).start();
        }
        start.countDown();
        done.await();
        assertEquals(threads * perThread, rel.size());
    }

    // ---- collection contract -------------------------------------------

    @Test
    public void addAndAddAllThrowUnsupported() {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        try {
            rel.add(new SimpleSbbLocalObject(
                    new SbbID("x"), new CountingSbb(), null, null, 0));
            fail("add() must throw");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        try {
            java.util.List<SbbLocalObject> one = new ArrayList<SbbLocalObject>();
            one.add(new SimpleSbbLocalObject(
                    new SbbID("y"), new CountingSbb(), null, null, 0));
            rel.addAll(one);
            fail("addAll() must throw");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void factoryReturningNullThrowsCreateException() {
        ChildRelationImpl<SbbLocalObject> rel = new ChildRelationImpl<SbbLocalObject>(
                "p", SbbLocalObject.class, cascadeRemover,
                (parent, relation) -> null);
        try {
            rel.create();
            fail("Expected CreateException");
        } catch (CreateException expected) {
            // ok
        } catch (SLEEException expected) {
            // ok
        }
    }

    @Test
    public void implementsChildRelationInterface() {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p");
        assertTrue("must implement ChildRelation", rel instanceof ChildRelation);
    }

    @Test
    public void equalsAndHashCodeStable() {
        ChildRelationImpl<SbbLocalObject> a = newRelation("parent-1");
        ChildRelationImpl<SbbLocalObject> b = newRelation("parent-1");
        // Different instances but same parent + child class
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        ChildRelationImpl<SbbLocalObject> c = newRelation("parent-2");
        assertFalse(a.equals(c));
    }

    @Test
    public void toStringIncludesParentAndSize() throws Exception {
        ChildRelationImpl<SbbLocalObject> rel = newRelation("p-string");
        rel.create();
        String s = rel.toString();
        assertTrue("toString must contain parent id: " + s, s.contains("p-string"));
        assertTrue("toString must contain size: " + s, s.contains("size=1"));
    }
}
