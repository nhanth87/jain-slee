/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.tck;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Sanity tests for the {@link MicrojainsleeContainerAdapter} skeleton.
 *
 * <p>The harness is a skeleton in P1.3 — what we are validating here is
 * that the SPI surface compiles, the constructor enforces its null
 * guard, and the accessor returns the same instance that was passed in.
 * The 15 SPI methods themselves throw
 * {@link UnsupportedOperationException}; P1.5/P1.6 will replace the
 * bodies with real implementations.
 */
class MicrojainsleeContainerAdapterTest {

    /**
     * A stand-in for the kernel — typed as Object on purpose because
     * jainslee-core is a provided-scope dependency of this harness and
     * we want to keep the test classpath free of a compile-time edge.
     */
    private static final Object FAKE_KERNEL = new Object();

    @Test
    void instantiatesWithNonNullKernel() {
        MicrojainsleeContainerAdapter adapter =
                new MicrojainsleeContainerAdapter(FAKE_KERNEL);
        assertNotNull(adapter,
                "adapter must be non-null after successful construction");
    }

    @Test
    void rejectsNullKernel() {
        assertThrows(IllegalArgumentException.class,
                () -> new MicrojainsleeContainerAdapter(null),
                "constructor must reject a null kernel argument");
    }

    @Test
    void exposesTheWrappedKernelByReference() {
        MicrojainsleeContainerAdapter adapter =
                new MicrojainsleeContainerAdapter(FAKE_KERNEL);
        Object exposed = adapter.getMicroSleeContainer();
        assertSame(FAKE_KERNEL, exposed,
                "getMicroSleeContainer() must return the exact instance passed in");
    }

    @Test
    void spiMethodsAllThrowUnsupportedForNow() {
        // Pin the skeleton contract: every SPI method throws
        // UnsupportedOperationException. If P1.5/P1.6 fills one in,
        // update this test alongside that change.
        MicrojainsleeContainerAdapter adapter =
                new MicrojainsleeContainerAdapter(FAKE_KERNEL);

        // We sample one SPI method (the simplest — getServices()) to
        // confirm the skeleton throws. We deliberately do not exhaustively
        // call all 15 here because each would need a real fixture argument.
        assertThrows(UnsupportedOperationException.class,
                () -> adapter.getServices(),
                "getServices() is a TODO in the skeleton — must throw UOE");
        assertEquals(FAKE_KERNEL, adapter.getMicroSleeContainer(),
                "accessor must still return the wrapped kernel after the throw");
    }
}
