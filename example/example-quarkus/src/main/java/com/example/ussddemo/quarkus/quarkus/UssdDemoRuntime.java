/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.quarkus;

import com.example.ussddemo.quarkus.events.UssdBeginEvent;
import com.example.ussddemo.quarkus.grpc.MockGrpcMenuClient;
import com.example.ussddemo.quarkus.sbbs.GrpcBackendSbb;
import com.example.ussddemo.quarkus.sbbs.Ss7UssdIngressSbb;
import com.example.ussddemo.quarkus.service.UssdCallbackDispatcher;
import com.example.ussddemo.quarkus.service.UssdSessionStore;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.InMemoryActivityContext;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;
import io.quarkus.arc.Unremovable;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Quarkus CDI bridge.
 *
 * <p>The Quarkus adapter-quarkus extension provides a
 * {@code SyntheticBeanBuildItem} for {@link MicroSleeContainer} but in
 * the @QuarkusTest static-init path that synthetic bean is not
 * always wired (the recorder's static fields may not yet be visible
 * at validation time). To make the example rock-solid, this class
 * also registers an explicit CDI producer for {@code MicroSleeContainer}
 * with {@code @Unremovable} + lifecycle event observers, falling back
 * to the synthetic bean if present.
 *
 * <p>The SBBs are constructed manually -- not by the APT scanner -- so
 * we still register them with {@code container.registerSbb} per session.
 */
@ApplicationScoped
@Unremovable
public final class UssdDemoRuntime {

    private static final Logger LOG = Logger.getLogger(UssdDemoRuntime.class);

    public static final String SS7_SBB_ID = "Ss7UssdIngress";
    public static final String GRPC_SBB_ID = "GrpcBackend";

    /**
     * Per-session SBB IDs -- suffixed with the session id so concurrent
     * sessions don't collide on the (internally-cached) auto-deployed
     * SBB entries from the sbb-index.properties.
     */
    private String ss7Id(String sessionId) {
        return SS7_SBB_ID + "/" + sessionId;
    }

    private String grpcId(String sessionId) {
        return GRPC_SBB_ID + "/" + sessionId;
    }

    @ConfigProperty(name = "microjainslee.buffer-size", defaultValue = "2048")
    int bufferSize;

    @ConfigProperty(name = "microjainslee.prefer-virtual-threads", defaultValue = "true")
    boolean preferVirtualThreads;

    @ConfigProperty(name = "microjainslee.sbb-pool-min", defaultValue = "16")
    int sbbPoolMin;

    @ConfigProperty(name = "microjainslee.sbb-pool-max", defaultValue = "4096")
    int sbbPoolMax;

    @ConfigProperty(name = "microjainslee.sbb-per-virtual-thread", defaultValue = "true")
    boolean sbbPerVirtualThread;

    @Inject
    UssdSessionStore sessionStore;

    @Inject
    UssdCallbackDispatcher callbackDispatcher;

    /**
     * Optional gRPC client passed to per-session {@link GrpcBackendSbb}
     * instances via {@link GrpcBackendSbb#setGrpcClientForTesting}. In a
     * Quarkus runtime this would be wired by {@code @Inject} directly on
     * the SBB; the wiring test uses a setter hook because the no-arg
     * SBB ctor (used by auto-deploy) leaves the field null.
     */
    @Inject
    MockGrpcMenuClient grpcClient;

    private volatile MicroSleeContainer container;

    /**
     * CDI producer for {@link MicroSleeContainer}. The Quarkus
     * adapter-quarkus extension's {@code @Produces} fallback
     * sometimes loses the static-init ordering race, so we
     * provide our own.
     */
    @Produces
    @Singleton
    @Unremovable
    public MicroSleeContainer microSleeContainer() {
        MicroSleeContainer c = container;
        if (c == null) {
            synchronized (UssdDemoRuntime.class) {
                c = container;
                if (c == null) {
                    MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                            .eventRouterBufferSize(bufferSize)
                            .preferVirtualThreads(preferVirtualThreads)
                            .sbbPoolMin(sbbPoolMin)
                            .sbbPoolMax(sbbPoolMax)
                            .sbbPerVirtualThread(sbbPerVirtualThread)
                            .build();
                    c = new MicroSleeContainer(cfg);
                    container = c;
                    LOG.infof("Embedded MicroSleeContainer constructed (bufferSize=%d)", bufferSize);
                }
            }
        }
        return c;
    }

    void onStart(@Observes StartupEvent ev) {
        microSleeContainer().start();
        LOG.info("MicroSleeContainer started");
    }

    void onStop(@Observes ShutdownEvent ev) {
        MicroSleeContainer c = container;
        if (c != null) {
            c.stop();
        }
    }

    public MicroSleeContainer container() { return microSleeContainer(); }
    public UssdSessionStore sessionStore() { return sessionStore; }

    public void beginSession(String sessionId, String msisdn, String ussdString, String callbackUrl) {
        sessionStore.open(sessionId);
        sessionStore.attachCallback(sessionId, callbackUrl);

        MicroSleeContainer c = microSleeContainer();
        InMemoryActivityContext aci = c.createActivityContext(sessionId);
        // Construct SBBs with `this` as the ctor arg so the SBBs hold
        // a reference back to the runtime (routeEvent/completeSession).
        // The no-arg ctors are only used by the Quarkus ARC normal-scope
        // proxy, but those don't actually receive onEvent either --
        // we always use the per-session SBBs constructed here.
        SimpleSbbLocalObject ss7 = c.registerSbb(ss7Id(sessionId),
                new Ss7UssdIngressSbb(this));
        GrpcBackendSbb grpcSbb = new GrpcBackendSbb(this);
        // In a real Quarkus runtime, @Inject would populate grpcClient
        // automatically. The non-CDI wiring test sets it via reflection
        // through a setter hook on the SBB.
        if (grpcClient() != null) {
            grpcSbb.setGrpcClientForTesting(grpcClient());
        }
        SimpleSbbLocalObject grpc = c.registerSbb(grpcId(sessionId), grpcSbb);
        ss7.setPriority(8);
        grpc.setPriority(5);
        c.attach(sessionId, ss7);
        c.attach(sessionId, grpc);

        LOG.infof("Firing UssdBeginEvent session=%s msisdn=%s callback=%s",
                sessionId, msisdn,
                callbackUrl == null ? "<polling>" : callbackUrl);
        c.routeEvent(new UssdBeginEvent(sessionId, msisdn, ussdString), aci);
    }

    /** Test hook: in real Quarkus, @Inject populates the gRPC client. */
    private MockGrpcMenuClient grpcClient() {
        try {
            java.lang.reflect.Field f = UssdDemoRuntime.class
                    .getDeclaredField("grpcClient");
            f.setAccessible(true);
            return (MockGrpcMenuClient) f.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
        container.routeEvent(event, aci);
    }

    public void completeSession(String sessionId, String responseText) {
        sessionStore.complete(sessionId, responseText);
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record != null && record.getCallbackUrl() != null) {
            callbackDispatcher.dispatch(record.getCallbackUrl(), sessionId, "COMPLETED",
                    responseText, null);
        }
    }

    public void failSession(String sessionId, String message) {
        sessionStore.fail(sessionId, message);
        UssdSessionStore.SessionRecord record = sessionStore.get(sessionId);
        if (record != null && record.getCallbackUrl() != null) {
            callbackDispatcher.dispatch(record.getCallbackUrl(), sessionId, "FAILED",
                    null, message);
        }
    }
}
