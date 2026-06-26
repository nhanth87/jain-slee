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

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ConcurrencyControl;
import com.microjainslee.api.EventContext;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JBoss-free activity context used by the embedded micro-container.
 */
public final class InMemoryActivityContext implements ActivityContextInterface, EventContext {

    private final String name;
    private final CopyOnWriteArrayList<SbbLocalObject> attachedSbbs =
            new CopyOnWriteArrayList<SbbLocalObject>();
    private final ReentrantLock transactionLock = new ReentrantLock();
    private final ConcurrentHashMap<String, ReentrantLock> eventTypeLocks =
            new ConcurrentHashMap<String, ReentrantLock>();
    private volatile ConcurrencyControl concurrencyControl = ConcurrencyControl.TRANSACTION;
    private volatile boolean suspended;

    public InMemoryActivityContext(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Activity context name must not be empty");
        }
        this.name = name;
    }

    @Override
    public String getActivityContextName() {
        return name;
    }

    @Override
    public void attach(SbbLocalObject sbbLocalObject) {
        SbbTransactionContext transaction =
                ActivityContextTransactionRegistry.currentFor(this);
        if (transaction != null) {
            transaction.recordAttach(sbbLocalObject);
            return;
        }
        attachImmediate(sbbLocalObject);
    }

    @Override
    public void detach(SbbLocalObject sbbLocalObject) {
        SbbTransactionContext transaction =
                ActivityContextTransactionRegistry.currentFor(this);
        if (transaction != null) {
            transaction.recordDetach(sbbLocalObject);
            return;
        }
        detachImmediate(sbbLocalObject);
    }

    @Override
    public void suspend() {
        suspended = true;
    }

    @Override
    public void resume() {
        suspended = false;
    }

    @Override
    public boolean isSuspended() {
        return suspended;
    }

    public ConcurrencyControl getConcurrencyControl() {
        return concurrencyControl;
    }

    public void setConcurrencyControl(ConcurrencyControl concurrencyControl) {
        if (concurrencyControl == null) {
            throw new IllegalArgumentException("concurrencyControl is required");
        }
        this.concurrencyControl = concurrencyControl;
    }

    /**
     * Returns the lock that serializes dispatch for the given event, or {@code null}
     * when {@link ConcurrencyControl#EVENT_TYPE_INDEPENDENT} allows concurrent delivery.
     */
    ReentrantLock lockForEvent(SleeEvent event) {
        if (concurrencyControl == ConcurrencyControl.TRANSACTION) {
            return transactionLock;
        }
        if (concurrencyControl == ConcurrencyControl.EVENT_TYPE) {
            String key = event.getClass().getName();
            ReentrantLock lock = eventTypeLocks.get(key);
            if (lock != null) {
                return lock;
            }
            ReentrantLock created = new ReentrantLock();
            ReentrantLock existing = eventTypeLocks.putIfAbsent(key, created);
            return existing != null ? existing : created;
        }
        return null;
    }

    public void attachImmediate(SbbLocalObject sbbLocalObject) {
        if (sbbLocalObject != null && !attachedSbbs.contains(sbbLocalObject)) {
            attachedSbbs.add(sbbLocalObject);
        }
    }

    public void detachImmediate(SbbLocalObject sbbLocalObject) {
        attachedSbbs.remove(sbbLocalObject);
    }

    public List<SbbLocalObject> getAttachedSbbs() {
        return Collections.unmodifiableList(new ArrayList<SbbLocalObject>(attachedSbbs));
    }
}
