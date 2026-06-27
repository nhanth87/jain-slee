/*
 * micro-jainslee 1.1.0 -- example application (grpc-simulator)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.grpcsimulator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class GrpcSimulatorMain {

    private static final Logger LOG = LogManager.getLogger(GrpcSimulatorMain.class);

    private GrpcSimulatorMain() {}

    public static void main(String[] args) throws java.io.IOException {
        int port = parsePort(args);
        GrpcSimulatorServer server = GrpcSimulatorServer.builder().port(port).build();
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("grpc-simulator: shutting down");
            server.close();
        }, "grpc-simulator-shutdown"));
        LOG.info("grpc-simulator: gRPC h2c listening on :{}", server.port());
    }

    private static int parsePort(String[] args) {
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("grpc-simulator: bad port arg '" + args[0] + "', falling back");
            }
        }
        return Integer.parseInt(System.getProperty("grpc.simulator.port", "9090"));
    }
}
