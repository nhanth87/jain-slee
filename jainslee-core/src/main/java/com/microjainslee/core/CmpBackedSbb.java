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

import java.lang.reflect.Method;

/**
 * Optional SBB base class that wires the abstract {@code getXxx}/{@code setXxx}
 * CMP accessors declared by the subclass to the {@link CmpFieldStore} so the
 * container can persist them across passivation / re-activation.
 *
 * <p>JAIN-SLEE 1.1 §6.5 says the SLEE supplies the implementation of CMP
 * accessors at deploy time; in micro-jainslee the spec-aligned approach is to
 * either:
 * <ul>
 *   <li>extend {@code CmpBackedSbb} (this class) — abstract accessors are
 *       auto-wired to the {@link CmpFieldStore} the first time the entity is
 *       activated, and persisted back to it on {@code sbbStore}.</li>
 *   <li>or write a concrete class and persist manually via
 *       {@code ctx.getCmpFieldStore().store(entityId, state)} in
 *       {@link Sbb#sbbStore()}.</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * @com.microjainslee.api.annotations.SbbAnnotation(name = "Counter")
 * public class CounterSbb extends CmpBackedSbb {
 *     public abstract int getCounter();
 *     public abstract void setCounter(int value);
 * }
 * }</pre>
 *
 * <p>The subclass declares the abstract accessors; this base class provides
 * the concrete implementation that delegates to the {@link CmpFieldStore}
 * (looked up via the entity id captured in {@link #setSbbEntityId(String)}).
 *
 * @author Tran Nhan (nhanth87)
 */
public abstract class CmpBackedSbb implements Sbb {

    private String sbbEntityId;
    /**
     * Per-instance override for the {@link CmpFieldStore}. Populated by
     * {@link MicroSleeContainer} during {@code registerSbb} so the CMP
     * bridge does not have to depend on the thread-local
     * {@link CmpFieldStoreLocator} (which is only populated on the thread
     * that called {@link MicroSleeContainer#start()}). Application threads
     * spawned from tests or event handlers therefore still find the right
     * store.
     */
    private CmpFieldStore boundStore;

    /**
     * Bind the SBB to its entity id; called by
     * {@link MicroSleeContainer} right before {@code sbbCreate}.
     */
    public final void setSbbEntityId(String id) {
        this.sbbEntityId = id;
    }

    /**
     * Bind the {@link CmpFieldStore} this SBB should read/write through.
     * Called by {@link MicroSleeContainer} immediately after
     * {@link #setSbbEntityId(String)} so the CMP bridge has a direct,
     * thread-agnostic reference. Falls back to {@link CmpFieldStoreLocator}
     * if no instance-level store was ever bound.
     */
    public final void setCmpFieldStore(CmpFieldStore store) {
        this.boundStore = store;
    }

    /** Return the currently bound entity id (may be {@code null} before registration). */
    public final String getSbbEntityId() {
        return sbbEntityId;
    }

    /** Return the instance-bound {@link CmpFieldStore}, or {@code null} if none was set. */
    public final CmpFieldStore getCmpFieldStore() {
        return boundStore;
    }

    /**
     * Resolve the {@link CmpFieldStore} the container manages. Prefers
     * the instance-level binding (set by {@link #setCmpFieldStore(CmpFieldStore)})
     * so application threads that did not call
     * {@link MicroSleeContainer#start()} still find the store; falls back
     * to the thread-local {@link CmpFieldStoreLocator} for embedders that
     * never wired the per-instance binding explicitly.
     */
    protected final CmpFieldStore cmpStore() {
        if (sbbEntityId == null) {
            throw new IllegalStateException(
                    "SBB entity id has not been bound; was registerSbb() called?");
        }
        if (boundStore != null) {
            return boundStore;
        }
        CmpFieldStore store = CmpFieldStoreLocator.get();
        if (store == null) {
            throw new IllegalStateException(
                    "No CmpFieldStore registered in this JVM; "
                            + "is MicroSleeContainer running?");
        }
        return store;
    }

    /**
     * Read a CMP field value via the SBB's abstract getter method.
     * <p>
     * Subclasses typically call this from inside their own non-abstract
     * helpers; the {@code getXxx} declaration remains abstract so the
     * signature matches the JAIN-SLEE 1.1 §6.5 rule "accessors must be
     * abstract". The base class does NOT implement those abstract methods
     * automatically (Java does not allow that without bytecode rewriting);
     * instead it provides {@link #cmpRead(Method)} / {@link #cmpWrite(Method, Object)}
     * that the SBB's own concrete helper methods can invoke.
     *
     * @param abstractGetter an abstract {@code getXxx} method declared on the subclass
     * @return the value stored for the CMP field (default if none yet)
     */
    protected final Object cmpRead(Method abstractGetter) {
        return CmpAccessorInvoker.getValue(this, cmpStore(), sbbEntityId, abstractGetter);
    }

    /** Write a CMP field value via the SBB's abstract setter method. */
    protected final void cmpWrite(Method abstractSetter, Object value) {
        CmpAccessorInvoker.setValue(this, cmpStore(), sbbEntityId, abstractSetter, value);
    }

    /**
     * Hook invoked by the container immediately before {@link #sbbStore()} to
     * give subclasses a chance to flush in-memory CMP writes back to the
     * persistent store. The default implementation is a no-op because the
     * {@link CmpAccessorInvoker}-based getters/setters already write through
     * to the store on every invocation — but subclasses that maintain
     * non-reflective state can override this.
     */
    public void cmpPersist() {
        // no-op by default
    }
}