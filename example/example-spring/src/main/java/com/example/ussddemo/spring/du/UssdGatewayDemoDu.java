/*
 * micro-jainslee 1.1.0 — example application (ussd-quarkus-demo)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.du;

import com.example.ussddemo.spring.sbbs.GrpcBackendSbb;
import com.example.ussddemo.spring.sbbs.Ss7UssdIngressSbb;
import com.microjainslee.api.annotations.DeployableUnit;

/**
 * Marker deployable unit referenced by the APT-generated {@code sbb-index.properties}.
 */
@DeployableUnit(
        name = "UssdGatewayDemo",
        vendor = "com.example.ussddemo",
        version = "1.0",
        sbbs = {Ss7UssdIngressSbb.class, GrpcBackendSbb.class},
        description = "USSD gateway demo: SS7 ingress SBB + gRPC backend SBB")
public final class UssdGatewayDemoDu {
    private UssdGatewayDemoDu() {
    }
}
