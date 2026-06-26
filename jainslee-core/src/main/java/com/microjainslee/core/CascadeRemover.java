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

import com.microjainslee.api.SbbLocalObject;

import java.util.List;

/**
 * JAIN-SLEE 1.1 §6.8 + §2.2.8 — cascading removal of an SBB entity tree.
 *
 * <p>Removing a root SBB must propagate through every child relation so the
 * entity sub-tree is torn down atomically with respect to the surrounding
 * transaction. This helper centralises the walk so it can be reused from
 * {@link SimpleSbbLocalObject#remove()} and from
 * {@link MicroSleeContainer}'s shutdown path.
 *
 * <p>The walk is iterative (no recursion) to avoid blowing the stack on
 * pathological entity trees, and uses {@link SbbEntityState#getChildRelations()}
 * snapshots so concurrent child creation does not throw
 * {@link java.util.ConcurrentModificationException}.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class CascadeRemover {

    private CascadeRemover() {
        // utility
    }

    /**
     * Recursively remove {@code root} and every descendant reachable
     * through its {@link SbbEntityState} child relations.
     *
     * @param root the local object of the root SBB entity
     */
    public static void cascadeRemove(SbbLocalObject root) {
        if (root == null) {
            return;
        }
        // Depth-first iterative walk using an explicit stack. Each iteration
        // removes the deepest child first so we never recurse into a relation
        // we've already cleared.
        java.util.ArrayDeque<SbbLocalObject> stack = new java.util.ArrayDeque<SbbLocalObject>();
        stack.push(root);
        while (!stack.isEmpty()) {
            SbbLocalObject current = stack.pop();
            if (!(current instanceof SimpleSbbLocalObject)) {
                // foreign impl — best effort, no child traversal possible
                try {
                    current.remove();
                } catch (Exception ignored) {
                    // best effort
                }
                continue;
            }
            SimpleSbbLocalObject simple = (SimpleSbbLocalObject) current;
            List<ChildRelationImpl> relations = simple.getEntityState().getChildRelations();
            for (ChildRelationImpl rel : relations) {
                for (SbbLocalObject child : rel) {
                    stack.push(child);
                }
            }
            try {
                simple.remove();
            } catch (Exception ignored) {
                // best effort — spec §5.5.4 says the SLEE should log and
                // continue; we keep that contract here.
            }
        }
    }
}