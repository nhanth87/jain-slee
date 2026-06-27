/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.du;

import com.example.ussddemo.spring.sbbs.GrpcClientSbb;
import com.example.ussddemo.spring.sbbs.HttpServerSbb;
import com.example.ussddemo.spring.sbbs.Ss7UssdIngressSbb;
import com.microjainslee.api.annotations.DeployableUnit;

@DeployableUnit(
        name = "UssdGatewayDemo",
        vendor = "com.example.ussddemo",
        version = "1.0",
        sbbs = {HttpServerSbb.class, Ss7UssdIngressSbb.class, GrpcClientSbb.class},
        description = "USSD gateway demo: HTTP ingress + SS7 leg + gRPC client SBB")
public final class UssdGatewayDemoDu {
    private UssdGatewayDemoDu() {
    }
}
