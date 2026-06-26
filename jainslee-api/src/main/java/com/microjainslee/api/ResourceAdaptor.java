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
 */
public interface ResourceAdaptor {
    void setResourceAdaptorContext(ResourceAdaptorContext context);
    void raConfigure();
    void raActive();
    void raStopping();
    void raInactive();
    void raUnconfigure();
}