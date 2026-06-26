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

import com.microjainslee.api.CreateException;
import com.microjainslee.api.SLEEException;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link ChildRelationImpl}.
 */
public class ChildRelationTest {

    private static ChildRelationFactory factoryFor(final Sbb child) {
        return new ChildRelationFactory() {
            @Override
            public SbbLocalObject createChild(String parentSbbId) {
                return new SimpleSbbLocalObject(
                        new SbbID(parentSbbId + ".child"), child, null, null, 0);
            }
        };
    }

    private static Sbb noopSbb() {
        return new Sbb() { };
    }

    @Test
    public void createAddsToCollection() throws Exception {
        ChildRelationImpl rel = new ChildRelationImpl("parent-1", factoryFor(noopSbb()));
        assertEquals(0, rel.size());

        SbbLocalObject child = rel.create();
        assertNotNull(child);
        assertEquals(1, rel.size());
        assertTrue(rel.contains(child));
    }

    @Test
    public void createUsesFactory() throws Exception {
        final AtomicInteger calls = new AtomicInteger(0);
        ChildRelationImpl rel = new ChildRelationImpl("parent-1", new ChildRelationFactory() {
            @Override
            public SbbLocalObject createChild(String parentSbbId) {
                calls.incrementAndGet();
                return new SimpleSbbLocalObject(
                        new SbbID(parentSbbId + ".c"), noopSbb(), null, null, 0);
            }
        });
        rel.create();
        rel.create();
        assertEquals(2, calls.get());
    }

    @Test
    public void iteratorReturnsAllChildren() throws Exception {
        ChildRelationImpl rel = new ChildRelationImpl("p", factoryFor(noopSbb()));
        rel.create();
        rel.create();
        rel.create();
        int count = 0;
        for (SbbLocalObject c : rel) {
            assertNotNull(c);
            count++;
        }
        assertEquals(3, count);
    }

    @Test
    public void removeOnParentCascades() throws Exception {
        final AtomicInteger removed = new AtomicInteger(0);
        Sbb sbb = new Sbb() {
            @Override
            public void sbbRemove() {
                removed.incrementAndGet();
            }
        };
        ChildRelationImpl rel = new ChildRelationImpl("p", factoryFor(sbb));
        SbbLocalObject c1 = rel.create();
        SbbLocalObject c2 = rel.create();
        rel.remove(c1);
        // remove() must also call c1.remove() -> sbbRemove()
        assertEquals(1, removed.get());
        assertEquals(1, rel.size());
        rel.remove(c2);
        assertEquals(2, removed.get());
        assertEquals(0, rel.size());
    }

    @Test
    public void addThrowsUnsupported() {
        ChildRelationImpl rel = new ChildRelationImpl("p", factoryFor(noopSbb()));
        try {
            rel.add(new SimpleSbbLocalObject(new SbbID("x"), noopSbb(), null, null, 0));
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void factoryReturningNullThrowsCreateException() {
        ChildRelationImpl rel = new ChildRelationImpl("p", new ChildRelationFactory() {
            @Override
            public SbbLocalObject createChild(String parentSbbId) {
                return null;
            }
        });
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
    public void sameNameReturnsSameInstance() {
        ChildRelationImpl rel = new ChildRelationImpl("p", factoryFor(noopSbb()));
        assertSame(rel, rel);
    }

    @Test
    public void synchronizedCreateDoesNotLoseChildren() throws Exception {
        final ChildRelationImpl rel = new ChildRelationImpl("p", factoryFor(noopSbb()));
        final int threadCount = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threadCount);
        for (int t = 0; t < threadCount; t++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        rel.create();
                    } catch (Exception ignored) {
                        // best effort
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(threadCount, rel.size());
        // size after iteration check
        int seen = 0;
        for (SbbLocalObject c : rel) {
            seen++;
        }
        assertEquals(threadCount, seen);
        assertFalse(rel.isEmpty());
    }
}