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

import com.microjainslee.api.SLEEException;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.TransactionRequiredLocalException;
import com.microjainslee.api.TransactionRolledbackLocalException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple immutable SBB local object for embedded deployments.
 * <p>
 * Adds Phase-B child-relation support: each instance owns a
 * {@link SbbEntityState} (CMP fields + child relations + lifecycle pointer)
 * and exposes {@link #getChildRelation(String, ChildRelationFactory)} for
 * the parent SBB to create / enumerate children.
 *
 * @author Tran Nhan (nhanth87)
 */
public final class SimpleSbbLocalObject implements SbbLocalObject {

    /**
     * Callback invoked after {@link #remove()} completes on the owning entity thread.
     */
    public interface RemovalListener {
        void onRemoved(SimpleSbbLocalObject localObject);
    }

    /**
     * Sprint S6 — coarse-grained cause code stamped onto each SBB entity just
     * before {@link #remove()} runs. The container reads it via
     * {@link #getRemovalCause()} and maps it to a
     * {@link com.microjainslee.core.removal.EntityRemovalEvent.RemovalReason}
     * before publishing on the removal bus.
     *
     * <p>Default is {@link #SELF} (the SBB removed itself). Callers that know
     * more — a timer-fire path, a cascade-remover path, the hot-redeploy
     * path — call {@link #setRemovalCause(RemovalCause)} before
     * {@code remove()} so observers get the right reason on the bus.
     */
    public enum RemovalCause {
        SELF,
        CASCADE,
        TIMER,
        HOT_REDEPLOY,
        OPERATOR,
        EXCEPTION_ROLLBACK,
        /** JSLEE 1.1 §8.5 — the root entity's attachment count reached zero. */
        ATTACHMENT_COUNT_ZERO
    }

    private final SbbID sbbID;
    private final Sbb sbb;
    private final VirtualThreadSbbEntityPool entityPool;
    private final RemovalListener removalListener;
    private final SbbEntityState entityState = new SbbEntityState();
    private final Map<String, ChildRelationImpl> childRelations =
            new ConcurrentHashMap<String, ChildRelationImpl>();
    private final boolean pooledEntity;
    private volatile int priority;
    private volatile boolean removed;
    /**
     * Sprint S6 — populated by the container right before {@link #remove()}
     * so the {@code RemovalListener} / {@code EntityRemovalBus} can carry the
     * why-was-it-removed information to downstream subscribers. Never reset.
     */
    private volatile RemovalCause removalCause = RemovalCause.SELF;
    /**
     * JSLEE 1.1 §8.5 — a root entity the SLEE created for an initial event. Its tree is
     * removed when its attachment count reaches zero. Entities registered
     * programmatically ({@code registerSbb}) sit outside the SLEE entity model and are
     * never auto-removed.
     */
    private volatile boolean sleeManagedRoot;
    private volatile java.util.function.Consumer<SimpleSbbLocalObject> attachmentCountZeroListener;
    /** Parent entity of a child SBB (§6.8); {@code null} for a root. */
    private volatile SimpleSbbLocalObject parent;
    /**
     * Parent SBB entity id, set when this local object is created as a child
     * via {@link com.microjainslee.core.child.ChildRelationImpl#create()}.
     * {@code null} for root SBB entities. Used by the cascade remover to
     * detect cycles and to give better diagnostics.
     */
    private volatile String parentEntityId;

    public SimpleSbbLocalObject(SbbID sbbID, Sbb sbb) {
        this(sbbID, sbb, null, null, 0);
    }

    public SimpleSbbLocalObject(SbbID sbbID, Sbb sbb, VirtualThreadSbbEntityPool entityPool,
            RemovalListener removalListener, int priority) {
        this(sbbID, sbb, entityPool, removalListener, priority, false);
    }

    public SimpleSbbLocalObject(SbbID sbbID, Sbb sbb, VirtualThreadSbbEntityPool entityPool,
            RemovalListener removalListener, int priority, boolean pooledEntity) {
        if (sbbID == null || sbb == null) {
            throw new IllegalArgumentException("sbbID and sbb are required");
        }
        this.sbbID = sbbID;
        this.sbb = sbb;
        this.entityPool = entityPool;
        this.removalListener = removalListener;
        this.priority = priority;
        this.pooledEntity = pooledEntity;
    }

    public boolean isPooledEntity() {
        return pooledEntity;
    }

    /**
     * Set the parent SBB entity id when this local object is created as a
     * child via {@link com.microjainslee.core.child.ChildRelationImpl#create()}.
     * Visible for the child package — not part of the {@link SbbLocalObject}
     * spec API.
     */
    public void setParentEntityId(String parentEntityId) {
        this.parentEntityId = parentEntityId;
    }

    /** Mark as a SLEE-created root; {@code onZero} fires when its attachment count hits 0. */
    public void markSleeManagedRoot(java.util.function.Consumer<SimpleSbbLocalObject> onZero) {
        this.attachmentCountZeroListener = onZero;
        this.sleeManagedRoot = true;
    }

    public boolean isSleeManagedRoot() {
        return sleeManagedRoot;
    }

    /** Link a child entity to its parent so attachment counts roll up (§8.5). */
    public void setParent(SimpleSbbLocalObject parent) {
        this.parent = parent;
        this.parentEntityId = parent == null || parent.getSbbID() == null ? null : parent.getSbbID().getId();
    }

    public SimpleSbbLocalObject getParent() {
        return parent;
    }

    /**
     * JSLEE 1.1 §8.5 — an entity's attachment count is the number of activity contexts
     * it is attached to plus the attachment counts of its children, so an
     * attach/detach changes this entity and every ancestor. When the root's count
     * drops to zero the tree is due for removal.
     */
    /** Activity contexts this entity itself is attached to (not its children's). */
    private final java.util.Set<InMemoryActivityContext> attachedContexts =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Called by {@link InMemoryActivityContext} on attach / detach of this entity. */
    void attachedTo(InMemoryActivityContext context, boolean attached) {
        if (attached) {
            attachedContexts.add(context);
        } else {
            attachedContexts.remove(context);
        }
        attachmentChanged(attached);
    }

    /**
     * Snapshot of the contexts this entity is attached to — O(own attachments), so
     * removal / recovery never scans every activity context in the container.
     */
    java.util.List<InMemoryActivityContext> attachedContexts() {
        return java.util.List.copyOf(attachedContexts);
    }

    void attachmentChanged(boolean attached) {
        SimpleSbbLocalObject root = this;
        for (SimpleSbbLocalObject node = this; node != null; node = node.parent) {
            if (attached) {
                node.entityState.attached();
            } else {
                node.entityState.detached();
            }
            root = node;
        }
        if (!attached && root.entityState.getAttachmentCount() == 0 && root.sleeManagedRoot && !root.removed) {
            java.util.function.Consumer<SimpleSbbLocalObject> listener = root.attachmentCountZeroListener;
            if (listener != null) {
                listener.accept(root);
            }
        }
    }

    /** Parent SBB entity id, or {@code null} for root entities. */
    public String getParentEntityId() {
        return parentEntityId;
    }

    @Override
    public Sbb getSbb() {
        return sbb;
    }

    @Override
    public SbbID getSbbID() {
        return sbbID;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * §5.5.2 — concrete {@code setSbbPriority} that honours the spec:
     * <ul>
     *   <li>silently no-op if the entity has already been removed (the
     *       underlying {@code setPriority} is the same micro-jainslee internal
     *       hook as before),</li>
     *   <li>throws {@link TransactionRolledbackLocalException} on invalid
     *       entity so the surrounding transaction can be marked for rollback
     *       by the SBB context.</li>
     * </ul>
     */
    @Override
    public void setSbbPriority(byte newPriority)
            throws TransactionRolledbackLocalException,
                   TransactionRequiredLocalException,
                   SLEEException {
        if (removed) {
            throw new TransactionRolledbackLocalException(
                    "Cannot set priority on removed SBB entity " + sbbID.getId());
        }
        setPriority(newPriority);
    }

    @Override
    public byte getSbbPriority()
            throws TransactionRolledbackLocalException,
                   TransactionRequiredLocalException,
                   SLEEException {
        if (removed) {
            throw new TransactionRolledbackLocalException(
                    "Cannot read priority on removed SBB entity " + sbbID.getId());
        }
        return (byte) getPriority();
    }

    @Override
    public boolean isRemoved() {
        return removed;
    }

    /**
     * Sprint S6 — the cause code stamped onto this entity by whatever code
     * path triggered {@link #remove()}. Default is {@link RemovalCause#SELF}.
     */
    public RemovalCause getRemovalCause() {
        return removalCause;
    }

    /**
     * Sprint S6 — let callers (timer scheduler, cascade remover, hot-redeploy
     * path) record WHY the entity is being removed so downstream observers
     * (removal bus, lifecycle logger) get useful diagnostics.
     */
    public void setRemovalCause(RemovalCause removalCause) {
        if (removalCause != null) {
            this.removalCause = removalCause;
        }
    }

    @Override
    public void remove() {
        invokeLocally(new Runnable() {
            @Override
            public void run() {
                if (removed) {
                    // §5.5.4 — silently no-op is the legacy contract; the spec
                    // expects TransactionRolledbackLocalException but the
                    // container handles that on subsequent invocations, not here.
                    return;
                }
                removed = true;
                entityState.markRemoved();
                entityState.transitionTo(SbbLifecycleManager.State.DOES_NOT_EXIST);
                if (!pooledEntity) {
                    sbb.sbbRemove();
                }
                try {
                    sbb.unsetSbbContext();
                } catch (RuntimeException ignored) {
                    // best-effort, spec §6.3.7 says SBB object state must be
                    // equivalent to a passivated SBB; we do not abort remove on this.
                }
                if (removalListener != null) {
                    removalListener.onRemoved(SimpleSbbLocalObject.this);
                }
            }
        });
    }

    @Override
    public void invokeLocally(Runnable action) {
        SbbLocalInvoker.invoke(entityPool, this, action);
    }

    // ---- Phase B: child relations ---------------------------------------

    /**
     * §6.8 — lazy-create a named {@link ChildRelationImpl} for this SBB entity.
     * The first call with a given {@code name} materialises the relation;
     * subsequent calls return the same instance, so {@code size()}/{@code iterator()}
     * stay stable across event deliveries.
     *
     * @param name    the relation name (e.g. {@code "childFoo"})
     * @param factory the factory that will be invoked by {@link
     *                com.microjainslee.api.ChildRelation#create()} to spawn
     *                a new child SBB entity
     * @return the relation, never {@code null}
     */
    public ChildRelationImpl getChildRelation(String name, ChildRelationFactory factory) {
        if (name == null) {
            throw new IllegalArgumentException("relation name is required");
        }
        ChildRelationImpl existing = childRelations.get(name);
        if (existing != null) {
            return existing;
        }
        ChildRelationImpl fresh = new ChildRelationImpl(sbbID.getId(), factory);
        ChildRelationImpl prior = childRelations.putIfAbsent(name, fresh);
        ChildRelationImpl winner = prior != null ? prior : fresh;
        if (prior == null) {
            entityState.registerChildRelation(winner);
        }
        return winner;
    }

    /** Read-only snapshot of every child relation registered on this entity. */
    public Map<String, ChildRelationImpl> getAllChildRelations() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, ChildRelationImpl>(childRelations));
    }

    /** Per-entity CMP + lifecycle state container. */
    public SbbEntityState getEntityState() {
        return entityState;
    }

    /** {@code true} once the entity has transitioned to {@link SbbLifecycleManager.State#READY}. */
    public boolean isReady() {
        return entityState.getLifecycleState() == SbbLifecycleManager.State.READY;
    }

    /**
     * Block until the entity reaches {@link SbbLifecycleManager.State#READY}
     * or the timeout elapses. Entity activation runs asynchronously on the
     * entity's virtual thread, so callers that need the SBB fully activated
     * (e.g. before invoking business methods directly, outside the event
     * path) should use this instead of hand-rolled sleep loops. Events
     * routed through the container never need this — the entity's task
     * queue already serializes activation before delivery.
     *
     * @return {@code true} when READY was reached within the timeout
     */
    public boolean awaitReady(long timeout, java.util.concurrent.TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isReady()) {
            if (isRemoved() || System.nanoTime() >= deadline) {
                return isReady();
            }
            Thread.sleep(1L);
        }
        return true;
    }
}
