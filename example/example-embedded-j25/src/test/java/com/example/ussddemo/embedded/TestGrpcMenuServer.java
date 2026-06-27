/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.embedded;

import com.example.ussddemo.grpc.proto.MenuRequest;
import com.example.ussddemo.grpc.proto.MenuResponse;
import com.example.ussddemo.grpc.proto.UssdMenuServiceGrpc;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

/** Minimal in-test gRPC USSD menu server (mirrors grpc-simulator behaviour). */
public final class TestGrpcMenuServer {

    private static final Logger LOG = LogManager.getLogger(TestGrpcMenuServer.class);

    private static final String BALANCE_MENU =
            "Welcome to micro-jainslee demo\n1. Balance\n2. Buy bundle\n0. Exit";

    private final Server server;
    private final int port;

    private TestGrpcMenuServer(int port) {
        this.port = port;
        this.server = NettyServerBuilder
                .forAddress(new InetSocketAddress("127.0.0.1", port))
                .addService(new UssdMenuServiceGrpc.UssdMenuServiceImplBase() {
                    @Override
                    public void resolveMenu(MenuRequest request,
                                            StreamObserver<MenuResponse> observer) {
                        LOG.debug("[test-grpc] ResolveMenu msisdn={}", request.getMsisdn());
                        MenuResponse.Builder b = MenuResponse.newBuilder()
                                .setSessionId(request.getSessionId());
                        if ("*123#".equals(request.getUssdString())) {
                            observer.onNext(b.setStatus("OK").setMenuText(BALANCE_MENU).build());
                        } else {
                            observer.onNext(b.setStatus("ERR")
                                    .setError("Unknown short code " + request.getUssdString())
                                    .build());
                        }
                        observer.onCompleted();
                    }
                })
                .build();
    }

    public static TestGrpcMenuServer startOnFreePort() throws IOException {
        TestGrpcMenuServer instance = new TestGrpcMenuServer(0);
        instance.server.start();
        return instance;
    }

    public int port() {
        int bound = server.getPort();
        return bound > 0 ? bound : port;
    }

    public void close() {
        if (!server.isShutdown()) {
            server.shutdownNow();
            try {
                server.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
