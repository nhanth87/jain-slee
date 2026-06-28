/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §11 — Resource Adaptor interface.
 * All Resource Adaptors must implement this interface.
 *
 * <p>micro-jainslee simplifies the spec interface to 7 methods (spec defines
 * 20+ incl. callbacks). The {@link #unsetResourceAdaptorContext()} method
 * corresponds to the spec §11.3 lifecycle method invoked as the last call
 * on a Resource Adaptor object before it becomes a candidate for garbage
 * collection. The default implementation is a no-op so existing direct
 * implementers do not need to change.</p>
 */
public interface ResourceAdaptor {
    void setResourceAdaptorContext(ResourceAdaptorContext context);

    /**
     * Spec §11.3 — last lifecycle method invoked before the RA object is
     * garbage-collected. The RA must drop any reference to the context it
     * received in {@link #setResourceAdaptorContext(ResourceAdaptorContext)}.
     * <p>
     * Default is a no-op for backward compatibility with direct implementers
     * (e.g. {@code HttpIngressResourceAdaptor}, {@code GrpcMenuResourceAdaptor})
     * that do not need explicit cleanup beyond what {@link #raUnconfigure()}
     * already does. The {@code AbstractResourceAdaptor} base class overrides
     * this to call {@code onContextUnset()} and null the context field.
     */
    default void unsetResourceAdaptorContext() {
    }

    void raConfigure();
    void raActive();
    void raStopping();
    void raInactive();
    void raUnconfigure();
}