/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.ies;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Verifies that {@link MicroSleeContainer#setInitialEventSelectorDispatcher(Object)}
 * forwards to the underlying {@code EventRouter} and that re-binding clears
 * the previous dispatcher.
 */
public class MicroSleeContainerIesWiringTest {

    static class FakePool implements InitialEventSelectorDispatcher.SbbEntityPool {
        final AtomicInteger seq = new AtomicInteger();
        final Set<String> alive = ConcurrentHashMap.newKeySet();
        @Override public String allocateNew(Class<?> sbbClass) {
            String id = "ent-" + seq.incrementAndGet(); alive.add(id); return id;
        }
        @Override public boolean contains(String entityId) { return alive.contains(entityId); }
        @Override public void onEntityRemoved(String entityId, Consumer<String> cb) { }
    }

    private MicroSleeContainer container;

    @Before
    public void setUp() {
        container = new MicroSleeContainer(
                MicroSleeConfiguration.builder()
                        .eventRouterBufferSize(16)
                        .preferVirtualThreads(false)
                        .sbbPerVirtualThread(false)
                        .build());
        container.start();
    }

    @After
    public void tearDown() {
        if (container != null) container.stop();
    }

    @Test
    public void setDispatcher_appearsInEventRouter() {
        assertNull("no dispatcher bound initially", container.getEventRouter().getInitialEventSelectorDispatcher());
        FakePool pool = new FakePool();
        InitialEventSelectorDispatcher d = new InitialEventSelectorDispatcher(pool);
        container.setInitialEventSelectorDispatcher(d);
        assertEquals(d, container.getEventRouter().getInitialEventSelectorDispatcher());
    }

    @Test
    public void setDispatcher_nullClearsBinding() {
        container.setInitialEventSelectorDispatcher(
                new InitialEventSelectorDispatcher(new FakePool()));
        assertNotNull(container.getEventRouter().getInitialEventSelectorDispatcher());
        container.setInitialEventSelectorDispatcher(null);
        assertNull(container.getEventRouter().getInitialEventSelectorDispatcher());
    }

    @Test(expected = IllegalArgumentException.class)
    public void setDispatcher_wrongClass_throws() {
        container.setInitialEventSelectorDispatcher("not a dispatcher");
    }
}
