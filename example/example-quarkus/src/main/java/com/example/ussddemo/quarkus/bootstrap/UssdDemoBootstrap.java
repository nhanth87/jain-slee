/*
 * micro-jainslee 1.1.0 -- example application (example-quarkus)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.quarkus.bootstrap;

import com.example.ussddemo.quarkus.grpc.GrpcMenuClient;
import com.example.ussddemo.quarkus.grpc.GrpcMenuUpstream;
import com.example.ussddemo.quarkus.profile.UssdSubscriberProfile;
import com.example.ussddemo.quarkus.ra.GrpcMenuResourceAdaptor;
import com.example.ussddemo.quarkus.ra.HttpIngressResourceAdaptor;
import com.example.ussddemo.quarkus.sbbs.GrpcClientSbb;
import com.example.ussddemo.quarkus.sbbs.HttpServerSbb;
import com.example.ussddemo.quarkus.sbbs.Ss7UssdIngressSbb;
import com.example.ussddemo.quarkus.service.UssdCallbackDispatcher;
import com.example.ussddemo.quarkus.service.UssdSbbWiring;
import com.example.ussddemo.quarkus.service.UssdSessionStore;
import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.UnrecognizedProfileTableNameException;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;
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
 * Quarkus CDI bootstrap — starts MicroSleeContainer, wires both RAs,
 * registers pooled SBB types, seeds subscriber profiles.
 */
@ApplicationScoped
@Unremovable
public final class UssdDemoBootstrap {

    private static final Logger LOG = Logger.getLogger(UssdDemoBootstrap.class);

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

    @ConfigProperty(name = "ussd.http.port", defaultValue = "8080")
    int httpPort;

    @ConfigProperty(name = "grpc.host", defaultValue = "127.0.0.1")
    String grpcHost;

    @ConfigProperty(name = "grpc.port", defaultValue = "9090")
    int grpcPort;

    @ConfigProperty(name = "ussd.session.timeout-ms", defaultValue = "30000")
    long sessionTimeoutMs;

    @Inject
    UssdSessionStore sessionStore;

    @Inject
    UssdCallbackDispatcher callbackDispatcher;

    @Inject
    UssdSbbWiring wiring;

    private volatile MicroSleeContainer container;
    private volatile GrpcMenuUpstream grpcClient;
    private volatile HttpIngressResourceAdaptor httpRa;
    private volatile GrpcMenuResourceAdaptor grpcRa;

    @Produces
    @Singleton
    @Unremovable
    public MicroSleeContainer microSleeContainer() {
        MicroSleeContainer c = container;
        if (c == null) {
            synchronized (UssdDemoBootstrap.class) {
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
                    LOG.infof("MicroSleeContainer constructed (bufferSize=%d)", bufferSize);
                }
            }
        }
        return c;
    }

    void onStart(@Observes StartupEvent ev) {
        MicroSleeContainer c = microSleeContainer();
        if (c.getState() != MicroSleeContainer.State.STARTED) {
            c.start();
        }
        wiring.install(c, sessionStore, callbackDispatcher, sessionTimeoutMs);
        registerSbbTypes(c);
        try {
            seedProfiles(c);
            bootstrapResourceAdaptors(c, new GrpcMenuClient(grpcHost, grpcPort));
        } catch (Exception e) {
            throw new IllegalStateException("USSD demo bootstrap failed", e);
        }
        LOG.info("USSD demo bootstrap complete");
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (httpRa != null) {
            httpRa.raInactive();
            httpRa.raUnconfigure();
        }
        if (grpcRa != null) {
            grpcRa.raInactive();
            grpcRa.raUnconfigure();
        }
        if (grpcClient != null) {
            grpcClient.close();
        }
        MicroSleeContainer c = container;
        if (c != null) {
            c.stop();
        }
    }

    private void registerSbbTypes(MicroSleeContainer c) {
        c.registerSbbType(HttpServerSbb.class, () -> new HttpServerSbb(wiring));
        c.registerSbbType(Ss7UssdIngressSbb.class, () -> new Ss7UssdIngressSbb(wiring));
        c.registerSbbType(GrpcClientSbb.class, () -> new GrpcClientSbb(wiring));
        LOG.info("Registered pooled SBB types: HttpServer, Ss7UssdIngress, GrpcClient");
    }

    private void seedProfiles(MicroSleeContainer c) throws Exception {
        ProfileFacility facility = c.getProfileFacility();
        facility.createProfileTable(UssdSubscriberProfile.TABLE_NAME);
        seedSubscriber(facility, wiring, "251911000001", 1);
        seedSubscriber(facility, wiring, "251911000002", 2);
        LOG.info("Seeded ussd-subscriber profiles");
    }

    private static void seedSubscriber(ProfileFacility facility, UssdSbbWiring wiring,
                                       String msisdn, int tier)
            throws UnrecognizedProfileTableNameException {
        ProfileLocalObject plo = facility.createProfile(
                UssdSubscriberProfile.TABLE_NAME, msisdn, UssdSubscriberProfile.class);
        Profile profile = plo.getProfile();
        if (profile instanceof UssdSubscriberProfile) {
            UssdSubscriberProfile sub = (UssdSubscriberProfile) profile;
            sub.setMsisdn(msisdn);
            sub.setMenuTier(tier);
            wiring.seedMenuTier(msisdn, tier);
        }
    }

    private void bootstrapResourceAdaptors(MicroSleeContainer c, GrpcMenuUpstream upstream)
            throws Exception {
        grpcClient = upstream;
        grpcRa = new GrpcMenuResourceAdaptor();
        grpcRa.setGrpcMenuClient(upstream);
        RaBootstrapContextImpl grpcCtx = new RaBootstrapContextImpl(c, "UssdMenuRA");
        grpcCtx.setResourceAdaptor(grpcRa);
        grpcRa.setResourceAdaptorContext(grpcCtx);
        grpcRa.raConfigure();
        grpcRa.raActive();
        wiring.setGrpcRa(grpcRa);

        httpRa = new HttpIngressResourceAdaptor();
        httpRa.setPort(httpPort);
        httpRa.setWiring(wiring);
        httpRa.setSessionStore(sessionStore);
        RaBootstrapContextImpl httpCtx = new RaBootstrapContextImpl(c, "HttpIngressRA");
        httpCtx.setResourceAdaptor(httpRa);
        httpRa.setResourceAdaptorContext(httpCtx);
        httpRa.raConfigure();
        httpRa.raActive();
        wiring.setHttpRa(httpRa);
        LOG.infof("HTTP RA listening on http://127.0.0.1:%d", httpRa.port());
    }

    /** Test hook — manual bootstrap without Quarkus CDI lifecycle. */
    public void startForTesting(MicroSleeContainer c, int httpPort, GrpcMenuUpstream grpcUpstream)
            throws Exception {
        this.httpPort = httpPort;
        this.container = c;
        if (c.getState() != MicroSleeContainer.State.STARTED) {
            c.start();
        }
        wiring.install(c, sessionStore, callbackDispatcher, sessionTimeoutMs);
        registerSbbTypes(c);
        seedProfiles(c);
        bootstrapResourceAdaptors(c, grpcUpstream);
    }

    public void startForTesting(MicroSleeContainer c, int httpPort, String grpcHost, int grpcPort)
            throws Exception {
        startForTesting(c, httpPort, new GrpcMenuClient(grpcHost, grpcPort));
    }

    public HttpIngressResourceAdaptor httpRa() {
        return httpRa;
    }
}
