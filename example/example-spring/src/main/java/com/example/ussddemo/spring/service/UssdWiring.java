/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.service;

import com.example.ussddemo.spring.ra.HttpIngressResourceAdaptor;
import com.example.ussddemo.spring.ra.GrpcMenuResourceAdaptor;
import com.microjainslee.core.MicroSleeContainer;
import org.springframework.stereotype.Component;

/**
 * Shared wiring between Spring bootstrap and SBB factories.
 */
@Component
public final class UssdWiring {

    private volatile MicroSleeContainer container;
    private volatile HttpIngressResourceAdaptor httpRa;
    private volatile GrpcMenuResourceAdaptor grpcRa;

    public MicroSleeContainer container() { return container; }
    public void setContainer(MicroSleeContainer container) { this.container = container; }

    public HttpIngressResourceAdaptor httpRa() { return httpRa; }
    public void setHttpRa(HttpIngressResourceAdaptor httpRa) { this.httpRa = httpRa; }

    public GrpcMenuResourceAdaptor grpcRa() { return grpcRa; }
    public void setGrpcRa(GrpcMenuResourceAdaptor grpcRa) { this.grpcRa = grpcRa; }
}
