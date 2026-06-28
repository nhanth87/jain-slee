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
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.core.SimpleSbbLocalObject;
import com.microjainslee.core.VirtualThreadSbbEntityPool;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Unit tests for {@link ChildRelationFactory} — caching, reflection scan,
 * default entity factory behaviour, and integration with multiple parent
 * entities.
 */
public class ChildRelationFactoryTest {

    /** Marker child SBB local interface used as the relation's child type. */
    public interface ChildA extends SbbLocalObject { }
    public interface ChildB extends SbbLocalObject { }

    /** Abstract parent SBB exposing two typed child-relation accessors. */
    public abstract static class ParentSbb implements Sbb {
        public abstract ChildRelation getChildARelation();
        public abstract ChildRelation getChildBRelation();
    }

    private VirtualThreadSbbEntityPool pool;
    private CascadeRemover cascadeRemover;
    private ChildRelationFactory factory;

    @Before
    public void setUp() {
        pool = new VirtualThreadSbbEntityPool(1, 16, false);
        cascadeRemover = new CascadeRemover(pool.asEntityLookup());
        factory = new ChildRelationFactory(pool, null, cascadeRemover);
    }

    @After
    public void tearDown() {
        if (pool != null && !pool.isShutdown()) {
            pool.shutdown();
        }
    }

    // ---- factory caching ------------------------------------------------

    @Test
    public void createChildRelationIsCached() {
        ChildRelationImpl<?> a = factory.createChildRelation("parent-1", ChildA.class);
        ChildRelationImpl<?> b = factory.createChildRelation("parent-1", ChildA.class);
        assertSame("same (parent, childClass) → same relation", a, b);
        assertEquals(1, factory.cachedRelationCount());
    }

    @Test
    public void differentParentIdsGetDifferentRelations() {
        ChildRelationImpl<?> a = factory.createChildRelation("parent-1", ChildA.class);
        ChildRelationImpl<?> b = factory.createChildRelation("parent-2", ChildA.class);
        assertNotSame(a, b);
        assertEquals(2, factory.cachedRelationCount());
    }

    @Test
    public void differentChildClassesGetDifferentRelations() {
        ChildRelationImpl<?> a = factory.createChildRelation("parent-1", ChildA.class);
        ChildRelationImpl<?> b = factory.createChildRelation("parent-1", ChildB.class);
        assertNotSame(a, b);
        assertEquals(2, factory.cachedRelationCount());
    }

    @Test
    public void findChildRelationReturnsCached() {
        ChildRelationImpl<?> a = factory.createChildRelation("parent-1", ChildA.class);
        assertSame(a, factory.findChildRelation("parent-1", ChildA.class));
        assertEquals(null, factory.findChildRelation("missing", ChildA.class));
        assertEquals(null, factory.findChildRelation("parent-1", ChildB.class));
    }

    @Test
    public void clearCacheResetsState() {
        factory.createChildRelation("parent-1", ChildA.class);
        factory.createChildRelation("parent-2", ChildB.class);
        assertEquals(2, factory.cachedRelationCount());
        factory.clearCache();
        assertEquals(0, factory.cachedRelationCount());
    }

    // ---- null / invalid args -------------------------------------------

    @Test
    public void nullArgsThrow() {
        try {
            factory.createChildRelation(null, ChildA.class);
            fail("expected IAE on null parentEntityId");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            factory.createChildRelation("p", null);
            fail("expected IAE on null childClass");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void factoryRequiresNonNullPoolAndCascade() {
        try {
            new ChildRelationFactory(null, null, cascadeRemover);
            fail("expected IAE on null pool");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new ChildRelationFactory(pool, null, null);
            fail("expected IAE on null cascadeRemover");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ---- reflection scan ------------------------------------------------

    @Test
    public void findChildRelationMethodsReturnsAbstractAccessors() {
        java.util.List<Method> methods = ChildRelationFactory.findChildRelationMethods(ParentSbb.class);
        assertEquals("two abstract ChildRelation accessors", 2, methods.size());
        for (Method m : methods) {
            assertEquals(ChildRelation.class, m.getReturnType());
        }
    }

    @Test
    public void findChildRelationMethodsReturnsEmptyForNullOrNonSbb() {
        assertEquals(0, ChildRelationFactory.findChildRelationMethods(null).size());
        assertEquals(0, ChildRelationFactory.findChildRelationMethods(String.class).size());
    }

    @Test
    public void findChildRelationMethodsIsCached() {
        java.util.List<Method> first = ChildRelationFactory.findChildRelationMethods(ParentSbb.class);
        java.util.List<Method> second = ChildRelationFactory.findChildRelationMethods(ParentSbb.class);
        // Same content (different defensive copies).
        assertEquals(first.size(), second.size());
    }

    // ---- pool wiring ----------------------------------------------------

    @Test
    public void factoryExposesPoolAndCascade() {
        assertSame(pool, factory.getEntityPool());
        assertSame(cascadeRemover, factory.getCascadeRemover());
    }

    // ---- VirtualThreadSbbEntityPool.createChildRelation wiring ---------

    @Test
    public void poolCreateChildRelationDelegatesToFactory() {
        ChildRelationImpl<ChildA> rel = pool.createChildRelation(
                "parent-X", ChildA.class, factory);
        assertNotNull(rel);
        assertSame("must be cached", rel,
                pool.createChildRelation("parent-X", ChildA.class, factory));
    }

    @Test
    public void poolCreateChildRelationWithNullFactoryReturnsNull() {
        assertEquals(null, pool.createChildRelation("parent-X", ChildA.class, null));
    }

    // ---- end-to-end with CascadeRemover --------------------------------

    @Test
    public void createChildRelationCreatePopulatesCascadeIndex() throws Exception {
        ChildRelationImpl<ChildA> rel = factory.createChildRelation("parent-E", ChildA.class);
        SbbLocalObject c = rel.create();
        String cid = c.getSbbID().getId();
        assertTrue(cascadeRemover.isChild(cid));
        assertEquals("parent-E", cascadeRemover.getParent(cid));
    }
}
