/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.transport;

import org.mobicents.protocols.api.Management;
import org.mobicents.protocols.sctp.spi.SctpBackend;
import org.mobicents.protocols.sctp.spi.SctpProvider;

/**
 * Isolation point for the SCTP transport. Default is F-Stack/DPDK (native-safe).
 * {@code NETTY_KERNEL} remains a JVM-only oracle and is refused inside a native image.
 *
 * <p>DESIGN §10.2 P3 guard: this factory only selects the transport backend — it
 * deliberately exposes NO SCTP protocol timers (RTO.*, Path.Max.Retrans, heartbeat
 * interval). Those stay at the stack/kernel RFC 4960 §15 defaults; do not add timer
 * knobs here. See Nextgen STP RUNBOOK §D for the audit record.</p>
 */
final class SctpManagementFactory {

    private SctpManagementFactory() { }

    static Management create(String name) throws Exception {
        String override = System.getProperty("ra.jss7.sctp.impl");
        if (override != null && !override.isBlank()) {
            if (override.contains("netty") && SctpProvider.isNativeImage()) {
                throw new IllegalStateException("Netty/JDK SCTP is forbidden in GraalVM native images");
            }
            Class<?> clazz = Class.forName(override);
            return (Management) clazz.getConstructor(String.class).newInstance(name);
        }
        SctpBackend backend = SctpBackend.from(System.getProperty("sctp.backend", "FSTACK_DPDK"));
        return SctpProvider.create(name, backend);
    }
}
