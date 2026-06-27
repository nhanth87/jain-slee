/*
 * micro-jainslee 1.1.0 — example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.du;

import com.example.ussddemo.quarkus.sbbs.GrpcClientSbb;
import com.example.ussddemo.quarkus.sbbs.HttpServerSbb;
import com.example.ussddemo.quarkus.sbbs.Ss7UssdIngressSbb;
import com.microjainslee.api.annotations.DeployableUnit;

@DeployableUnit(
        name = "UssdGatewayDemo",
        vendor = "com.example.ussddemo.quarkus",
        version = "1.0",
        sbbs = {HttpServerSbb.class, Ss7UssdIngressSbb.class, GrpcClientSbb.class},
        description = "USSD gateway demo: HTTP ingress + SS7 MAP leg + gRPC client child SBB")
public final class UssdGatewayDemoDu {
    private UssdGatewayDemoDu() {
    }
}
