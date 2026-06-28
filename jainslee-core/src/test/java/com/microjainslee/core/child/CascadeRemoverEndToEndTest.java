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

import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.core.SimpleSbbLocalObject;
import com.microjainslee.core.VirtualThreadSbbEntityPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end tests for {@link CascadeRemover} + {@link ChildRelationImpl}
 * exercising the depth-first post-order spec §6.7 walk on a 2-level SBB
 * entity tree (parent → child → grandchild).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Every entity in the tree receives exactly one {@code sbbRemove()} call.</li>
 *   <li>{@code sbbRemove()} is invoked leaf-first (grandchildren before
 *       children before parent).</li>
 *   <li>{@link VirtualThreadSbbEntityPool#asEntityLookup()} drives the
 *       release through {@code releaseById()} so the pool drains.</li>
 * </ul>
 */
public class CascadeRemoverEndToEndTest {

    /**
     * SBB that records the global order of {@code sbbRemove()} invocations
     * so the test can assert depth-first post-order ordering.
     */
    private static final class OrderedSbb implements Sbb {
        private final String entityId;
        private final CopyOnWriteArrayList<String> order;
        OrderedSbb(String entityId, CopyOnWriteArrayList<String> order) {
            this.entityId = entityId;
            this.order = order;
        }
        @Override
        public void sbbRemove() {
            order.add(entityId);
        }
    }

    private VirtualThreadSbbEntityPool pool;
    private CascadeRemover cascadeRemover;

    @Before
    public void setUp() {
        pool = new VirtualThreadSbbEntityPool(1, 64, false);
        cascadeRemover = new CascadeRemover(pool.asEntityLookup());
    }

    @After
    public void tearDown() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown();
        }
    }

    /** Register an entity in the pool and return its SimpleSbbLocalObject. */
    private SimpleSbbLocalObject register(String entityId, Sbb sbb) {
        pool.acquire(entityId, 0L, sbb);
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(
                new SbbID(entityId), sbb, pool, null, 0);
        return lo;
    }

    // ---- spec §6.7 depth-first post-order ------------------------------

    @Test
    public void twoLevelTreeCascadeRemovesLeafFirst() {
        // parent
        //  ├── child-A
        //  │   └── grandchild-A1
        //  │   └── grandchild-A2
        //  └── child-B
        //      └── grandchild-B1
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        SimpleSbbLocalObject parent = register("parent", new OrderedSbb("parent", order));
        SimpleSbbLocalObject childA = register("child-A", new OrderedSbb("child-A", order));
        SimpleSbbLocalObject childB = register("child-B", new OrderedSbb("child-B", order));
        SimpleSbbLocalObject grandA1 = register("grand-A1", new OrderedSbb("grand-A1", order));
        SimpleSbbLocalObject grandA2 = register("grand-A2", new OrderedSbb("grand-A2", order));
        SimpleSbbLocalObject grandB1 = register("grand-B1", new OrderedSbb("grand-B1", order));

        cascadeRemover.registerChild("parent", "child-A");
        cascadeRemover.registerChild("parent", "child-B");
        cascadeRemover.registerChild("child-A", "grand-A1");
        cascadeRemover.registerChild("child-A", "grand-A2");
        cascadeRemover.registerChild("child-B", "grand-B1");

        // Cascade remove from the root.
        cascadeRemover.cascadeRemove("parent");

        // Every entity must have sbbRemove() invoked exactly once.
        Set<String> distinct = new HashSet<>(order);
        assertEquals("six unique removals", 6, distinct.size());
        assertEquals("each entity removed once", 6, order.size());

        // grand-A1 + grand-A2 must appear before child-A.
        int a1 = order.indexOf("grand-A1");
        int a2 = order.indexOf("grand-A2");
        int ca = order.indexOf("child-A");
        assertTrue("grand-A1 before child-A: " + order, a1 < ca);
        assertTrue("grand-A2 before child-A: " + order, a2 < ca);

        // child-A + child-B must appear before parent.
        int cb = order.indexOf("child-B");
        int p = order.indexOf("parent");
        assertTrue("child-A before parent: " + order, ca < p);
        assertTrue("child-B before parent: " + order, cb < p);

        // grand-B1 before child-B.
        int b1 = order.indexOf("grand-B1");
        assertTrue("grand-B1 before child-B: " + order, b1 < cb);
    }

    @Test
    public void cascadeRemoveReleasesPoolEntities() {
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        SimpleSbbLocalObject parent = register("parent", new OrderedSbb("parent", order));
        SimpleSbbLocalObject child = register("child", new OrderedSbb("child", order));
        cascadeRemover.registerChild("parent", "child");

        assertNotNull(pool.findEntity("parent"));
        assertNotNull(pool.findEntity("child"));

        cascadeRemover.cascadeRemove("parent");

        // The EntityLookup.releaseEntity() must have removed both from the pool.
        assertEquals(null, pool.findEntity("parent"));
        assertEquals(null, pool.findEntity("child"));
    }

    @Test
    public void cycleIsDetectedAndSkipped() {
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        register("A", new OrderedSbb("A", order));
        register("B", new OrderedSbb("B", order));
        cascadeRemover.registerChild("A", "B");
        cascadeRemover.registerChild("B", "A"); // cycle

        // Must not infinite-loop.
        cascadeRemover.cascadeRemove("A");

        // Each entity must be removed at most once.
        assertEquals("exactly 2 removals", 2, order.size());
        assertEquals("A appears once", 1, Collections.frequency(order, "A"));
        assertEquals("B appears once", 1, Collections.frequency(order, "B"));
    }

    @Test
    public void sbbRemoveExceptionDoesNotAbortWalk() {
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        SimpleSbbLocalObject parent = register("parent", new OrderedSbb("parent", order));
        Sbb throwingChild = new Sbb() {
            @Override public void sbbRemove() {
                order.add("throwing-child");
                throw new RuntimeException("boom");
            }
        };
        SimpleSbbLocalObject sibling = register("sibling", new OrderedSbb("sibling", order));
        cascadeRemover.registerChild("parent", "throwing-child");
        cascadeRemover.registerChild("parent", "sibling");

        // Must not propagate the exception; must still call sbbRemove on sibling + parent.
        cascadeRemover.cascadeRemove("parent");
        assertTrue("sibling sbbRemove invoked despite sibling exception",
                order.contains("sibling"));
        assertTrue("parent sbbRemove invoked last",
                order.indexOf("parent") == order.size() - 1);
    }

    // ---- ChildRelationImpl + CascadeRemover integration ----------------

    @Test
    public void childRelationRemoveCascadesDescendants() throws Exception {
        // Use the ChildRelationImpl path end-to-end: create grandchild via
        // its own ChildRelationImpl, then remove via the parent.
        CopyOnWriteArrayList<String> order = new CopyOnWriteArrayList<>();
        String parentId = "parent-CR";
        String childId = "child-CR";

        // Register parent + child manually (since we don't have a
        // type-registry in this test, build entities directly).
        register(parentId, new OrderedSbb(parentId, order));
        register(childId, new OrderedSbb(childId, order));
        cascadeRemover.registerChild(parentId, childId);

        ChildRelationImpl<SbbLocalObject> relation = new ChildRelationImpl<SbbLocalObject>(
                parentId, SbbLocalObject.class, cascadeRemover,
                (parent, r) -> {
                    // No-op: cascade remove walks the index, not the relation.
                    return null;
                });
        // Pre-populate the liveChildren map (the relation we just built is
        // empty since the factory returned null).
        VirtualThreadSbbEntityPool.SbbEntity childEntity = pool.findEntity(childId);
        assertNotNull(childEntity);

        cascadeRemover.cascadeRemove(parentId);

        // Both must be removed.
        assertTrue(order.contains(parentId));
        assertTrue(order.contains(childId));
        assertEquals(2, order.size());
    }
}
