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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spec §6.7 compliant cascade SBB removal — instance-based, registration-driven.
 *
 * <p>JAIN SLEE 1.1 §6.7: "When a root SBB entity is removed, all child SBB
 * entities must also be removed. {@code sbbRemove()} is called on child SBB
 * entities before parent."
 *
 * <p>Order: depth-first post-order (leaf-first):
 * <pre>
 *   parent
 *   ├── child-A
 *   │   └── grandchild-A1 → sbbRemove() FIRST
 *   │   └── grandchild-A2 → sbbRemove() SECOND
 *   │   child-A → sbbRemove() THIRD
 *   └── child-B → sbbRemove() FOURTH
 *   parent → sbbRemove() LAST (or never if pool handles it)
 * </pre>
 *
 * <p>This class is the instance-based variant used by
 * {@link ChildRelationImpl} when parent SBBs opt in to the explicit
 * {@code registerChild}/{@code cascadeRemove} workflow. The legacy
 * {@link com.microjainslee.core.CascadeRemover} (static utility in the
 * {@code com.microjainslee.core} package) is retained for callers that
 * prefer the {@code SimpleSbbLocalObject} state-driven path.
 *
 * <p>Module: jainslee-core — wire
 * {@link com.microjainslee.core.VirtualThreadSbbEntityPool} to delegate
 * {@code entities.remove()} calls to {@link #cascadeRemove(String)} via
 * the supplied {@link EntityLookup}.
 *
 * @author Tran Nhan (nhanth87)
 */
public class CascadeRemover {

    private static final Logger LOG = LogManager.getLogger(CascadeRemover.class);

    /**
     * Lookup the live SBB instance for {@code entityId} and release the
     * entity back to the pool once removal completes.
     */
    public interface EntityLookup {
        /** Return the live SBB instance, or {@code null} when unknown. */
        Sbb getSbb(String entityId);

        /** Release the entity slot — called once per cascade-removed entity. */
        void releaseEntity(String entityId);
    }

    // parentId → Set<childId>
    private final ConcurrentHashMap<String, Set<String>> parentToChildren =
            new ConcurrentHashMap<>();

    // childId → parentId (reverse index for O(1) parent lookup)
    private final ConcurrentHashMap<String, String> childToParent =
            new ConcurrentHashMap<>();

    private final EntityLookup entityLookup;

    public CascadeRemover(EntityLookup entityLookup) {
        if (entityLookup == null) {
            throw new IllegalArgumentException("EntityLookup is required");
        }
        this.entityLookup = entityLookup;
    }

    /** Register a parent-child relationship (called from {@link ChildRelationImpl#create()}). */
    public void registerChild(String parentId, String childId) {
        if (parentId == null || childId == null) {
            throw new IllegalArgumentException("parentId and childId are required");
        }
        parentToChildren.computeIfAbsent(parentId, k -> ConcurrentHashMap.newKeySet())
                        .add(childId);
        childToParent.put(childId, parentId);
        LOG.trace(String.format("Child registered: %s → %s", parentId, childId));
    }

    /** Deregister a child (called from {@link ChildRelationImpl#remove(Object)}). */
    public void deregisterChild(String parentId, String childId) {
        Set<String> children = parentToChildren.get(parentId);
        if (children != null) {
            children.remove(childId);
            if (children.isEmpty()) {
                parentToChildren.remove(parentId, children);
            }
        }
        childToParent.remove(childId);
    }

    /**
     * Cascade remove with cycle protection.
     *
     * @param rootEntityId entity to remove (may be root or intermediate)
     * @param visited      visited set used to short-circuit cycles
     */
    public void cascadeRemove(String rootEntityId, Set<String> visited) {
        if (rootEntityId == null) {
            return;
        }
        if (visited == null) {
            visited = new HashSet<>();
        }
        if (!visited.add(rootEntityId)) {
            LOG.warn(String.format("Cycle detected in SBB entity tree at %s — skipping", rootEntityId));
            return;
        }

        // 1. Depth-first post-order: walk children first.
        Set<String> children = parentToChildren.remove(rootEntityId);
        if (children != null) {
            for (String childId : new HashSet<>(children)) {
                cascadeRemove(childId, visited);
            }
        }

        // 2. Call sbbRemove() on this entity's SBB instance.
        Sbb sbb = entityLookup.getSbb(rootEntityId);
        if (sbb != null) {
            try {
                sbb.sbbRemove();
                LOG.trace("sbbRemove() called on " + rootEntityId);
            } catch (Throwable t) {
                // Spec §6.7: exceptions in sbbRemove() are logged, removal continues.
                LOG.error("sbbRemove() threw exception for entity " + rootEntityId
                        + " — continuing removal", t);
            }
        }

        // 3. Clean up reverse index.
        String parentId = childToParent.remove(rootEntityId);
        if (parentId != null) {
            Set<String> siblingSet = parentToChildren.get(parentId);
            if (siblingSet != null) {
                siblingSet.remove(rootEntityId);
            }
        }

        // 4. Release entity back to the pool.
        entityLookup.releaseEntity(rootEntityId);
    }

    /** Convenience overload — cascade remove with a fresh visited set. */
    public void cascadeRemove(String rootEntityId) {
        cascadeRemove(rootEntityId, new HashSet<>());
    }

    /** Immutable view of registered children for {@code parentId}. */
    public Set<String> getChildren(String parentId) {
        Set<String> children = parentToChildren.get(parentId);
        return children != null ? Set.copyOf(children) : Set.of();
    }

    /** @return {@code true} when {@code entityId} has a registered parent. */
    public boolean isChild(String entityId) {
        return entityId != null && childToParent.containsKey(entityId);
    }

    /** @return the registered parent id for {@code childId}, or {@code null}. */
    public String getParent(String childId) {
        return childId == null ? null : childToParent.get(childId);
    }

    /** Total number of tracked parent + child entries — for diagnostics. */
    public int totalTrackedEntities() {
        return parentToChildren.size() + childToParent.size();
    }

    /** Direct access to the {@link EntityLookup} — used by ChildRelationImpl
     * when a single child needs to be removed without cascading the parent. */
    public EntityLookup entityLookup() {
        return entityLookup;
    }

    /** Drop every index — does NOT call {@code sbbRemove()} on the entities. */
    public void clear() {
        parentToChildren.clear();
        childToParent.clear();
    }
}
