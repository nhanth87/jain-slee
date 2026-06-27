/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.du;

import com.microjainslee.api.annotations.DeployableUnit;

/**
 * Marker deployable unit. SBB types are registered manually at runtime via
 * {@code registerSbbType} in {@link com.example.ussddemo.embedded.EmbeddedUssdBootstrap}.
 */
@DeployableUnit(
        name = "UssdGatewayDemo",
        vendor = "com.example.ussddemo",
        version = "1.0",
        description = "USSD gateway embedded demo (runtime-registered SBB types)")
public final class UssdGatewayDemoDu {
    private UssdGatewayDemoDu() {
    }
}
