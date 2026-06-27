/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.ra;

import com.example.ussddemo.grpc.proto.MenuRequest;
import com.example.ussddemo.grpc.proto.MenuResponse;
import com.example.ussddemo.grpc.proto.UssdMenuServiceGrpc;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.TimeUnit;

/** Blocking gRPC client for the external USSD menu Application Server. */
public final class GrpcMenuClient {

    private static final Logger LOG = LogManager.getLogger(GrpcMenuClient.class);

    private final ManagedChannel channel;
    private final UssdMenuServiceGrpc.UssdMenuServiceBlockingStub stub;

    public GrpcMenuClient(String host, int port) {
        this(host, port, 5_000L);
    }

    public GrpcMenuClient(String host, int port, long deadlineMs) {
        this.channel = NettyChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = UssdMenuServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS);
    }

    public MenuResponse resolveMenu(String msisdn, String ussdString, String sessionId) {
        MenuRequest req = MenuRequest.newBuilder()
                .setMsisdn(msisdn)
                .setUssdString(ussdString)
                .setSessionId(sessionId == null ? "" : sessionId)
                .build();
        try {
            return stub.resolveMenu(req);
        } catch (StatusRuntimeException e) {
            Status s = e.getStatus();
            LOG.warn("[grpc] resolveMenu failed: {} {}", s.getCode(), s.getDescription());
            return MenuResponse.newBuilder()
                    .setSessionId(req.getSessionId())
                    .setStatus("ERR")
                    .setError(s.getCode() + ": " + s.getDescription())
                    .build();
        }
    }

    public void close() {
        channel.shutdownNow();
        try {
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.warn("gRPC channel did not terminate within 2s");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
