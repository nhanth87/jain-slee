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
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * Stress-tests {@link CascadeRemover} on deep SBB entity trees.
 *
 * <p>Per Phase B (missing_feature_microjainslee.md), cascade removal
 * of child SBBs must be iterative (no recursion) so a degenerate tree
 * (e.g. 1000 levels) cannot blow the JVM stack. This test builds
 * wide + deep trees and verifies that a single {@code cascadeRemove}
 * call removes every node.
 */
public class CascadeRemoverDeepTreeTest {

    @Test
    public void wideTreeFanoutCascadeRemovesAll() throws Exception {
        // root with 20 children, each with 5 grandchildren = 1 + 20 + 100 = 121
        final AtomicInteger removeCalls = new AtomicInteger();
        CountingSbb counting = new CountingSbb(removeCalls);
        ChildRelationFactory factory = parentId -> new SimpleSbbLocalObject(
                new SbbID(parentId + ".child"), counting, null, null, 0);

        SimpleSbbLocalObject root = new SimpleSbbLocalObject(
                new SbbID("root"), counting, null, null, 0);
        ChildRelationImpl rootRel = root.getChildRelation("kids", factory);
        for (int i = 0; i < 20; i++) {
            SimpleSbbLocalObject mid = (SimpleSbbLocalObject) rootRel.create();
            ChildRelationImpl midRel = mid.getChildRelation("grandkids", factory);
            for (int j = 0; j < 5; j++) {
                midRel.create();
            }
        }

        int before = countEntities(root);
        assertEquals("tree shape: 121 entities", 121, before);

        CascadeRemover.cascadeRemove(root);
        assertEquals("all 121 entities removed", 121, removeCalls.get());
    }

    @Test
    public void deepChainCascadeRemovesAll() throws Exception {
        final AtomicInteger removeCalls = new AtomicInteger();
        CountingSbb counting = new CountingSbb(removeCalls);
        ChildRelationFactory factory = parentId -> new SimpleSbbLocalObject(
                new SbbID(parentId + ".child"), counting, null, null, 0);

        SimpleSbbLocalObject root = new SimpleSbbLocalObject(
                new SbbID("chain-root"), counting, null, null, 0);
        ChildRelationImpl rel = root.getChildRelation("next", factory);
        SimpleSbbLocalObject prev = root;
        final int depth = 50;
        for (int i = 0; i < depth; i++) {
            SbbLocalObject child = rel.create();
            // Re-bind the relation for the deeper node.
            rel = ((SimpleSbbLocalObject) child).getChildRelation("next", factory);
            prev = (SimpleSbbLocalObject) child;
        }

        // 1 root + depth children
        assertEquals(depth + 1, countEntities(root));

        CascadeRemover.cascadeRemove(root);
        assertEquals(depth + 1, removeCalls.get());
    }

    @Test
    public void nullRootIsNoOp() {
        // Must not throw.
        CascadeRemover.cascadeRemove(null);
    }

    @Test
    public void singleNodeCascadeRemovesItself() {
        AtomicInteger calls = new AtomicInteger();
        SimpleSbbLocalObject lo = new SimpleSbbLocalObject(
                new SbbID("solo"), new CountingSbb(calls), null, null, 0);
        CascadeRemover.cascadeRemove(lo);
        assertEquals(1, calls.get());
    }

    // --- helpers ---

    private static int countEntities(SbbLocalObject root) {
        java.util.ArrayDeque<SbbLocalObject> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        int n = 0;
        while (!stack.isEmpty()) {
            SbbLocalObject cur = stack.pop();
            n++;
            if (cur instanceof SimpleSbbLocalObject) {
                SimpleSbbLocalObject s = (SimpleSbbLocalObject) cur;
                for (ChildRelationImpl rel : s.getAllChildRelations().values()) {
                    for (SbbLocalObject child : rel) {
                        stack.push(child);
                    }
                }
            }
        }
        return n;
    }

    private static final class CountingSbb implements Sbb {
        private final AtomicInteger counter;

        CountingSbb(AtomicInteger counter) {
            this.counter = counter;
        }

        @Override
        public void sbbRemove() {
            counter.incrementAndGet();
        }
    }
}
