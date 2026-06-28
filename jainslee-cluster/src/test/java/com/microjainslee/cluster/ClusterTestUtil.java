/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SbbLocalObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test fixtures for {@link ClusteredActivityContextNamingFacility}.
 *
 * <p>Provides {@link #newSerializableAci(String)}, a minimal
 * {@link ActivityContextInterface} that also implements
 * {@link java.io.Serializable} so it can travel the Infinispan
 * wire through the default Java-serialization marshaller.
 *
 * <p>Why a custom stub and not {@code InMemoryActivityContext}?
 * The concrete class in jainslee-core does not implement
 * {@link java.io.Serializable} — this is a tracked P5 task. See
 * {@link ClusteredActivityContextNamingFacility} for the caveat.
 *
 * <p><b>R&amp;D only — never for production.</b>
 */
public final class ClusterTestUtil {

    /** Sequential id generator so tests can compare bound values
     *  without relying on {@code equals()}. */
    private static final AtomicInteger ACI_SEQ = new AtomicInteger();

    private ClusterTestUtil() {
        // utility class
    }

    /**
     * Build a {@link java.io.Serializable}
     * {@link ActivityContextInterface} for the given name. The
     * returned instance carries a unique sequence number so tests
     * can assert identity without overriding {@code equals()}.
     */
    public static ActivityContextInterface newSerializableAci(String name) {
        return new SerializableAci(name, ACI_SEQ.incrementAndGet());
    }

    /**
     * Test stub: a {@link java.io.Serializable}
     * {@link ActivityContextInterface}. The two
     * {@code attach}/{@code detach} operations are no-ops because
     * the cluster ACNF only stores the handle, not the SBB graph.
     */
    public static final class SerializableAci implements ActivityContextInterface, java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final int sequence;

        SerializableAci(String name, int sequence) {
            this.name = name;
            this.sequence = sequence;
        }

        @Override
        public String getActivityContextName() {
            return name;
        }

        @Override
        public void attach(SbbLocalObject sbbLocalObject) {
            // no-op: ACNF stores the handle only; the SBB graph is
            // the concern of the entity pool, not the facility.
        }

        @Override
        public void detach(SbbLocalObject sbbLocalObject) {
            // no-op, see attach().
        }

        public int getSequence() {
            return sequence;
        }

        @Override
        public String toString() {
            return "SerializableAci[name=" + name + ", seq=" + sequence + "]";
        }
    }
}
