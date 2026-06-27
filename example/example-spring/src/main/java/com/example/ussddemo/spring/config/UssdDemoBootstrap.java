/*
 * micro-jainslee 1.1.0 -- example application (example-spring)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.ussddemo.spring.config;

import com.example.ussddemo.spring.events.HttpUssdBeginEvent;
import com.example.ussddemo.spring.grpc.GrpcMenuClient;
import com.example.ussddemo.spring.grpc.GrpcMenuResolver;
import com.example.ussddemo.spring.profile.UssdSubscriberProfile;
import com.example.ussddemo.spring.ra.GrpcMenuResourceAdaptor;
import com.example.ussddemo.spring.ra.HttpIngressResourceAdaptor;
import com.example.ussddemo.spring.sbbs.GrpcClientSbb;
import com.example.ussddemo.spring.sbbs.HttpServerSbb;
import com.example.ussddemo.spring.sbbs.Ss7UssdIngressSbb;
import com.example.ussddemo.spring.service.InMemoryGrpcMenuClient;
import com.example.ussddemo.spring.service.UssdCallbackDispatcher;
import com.example.ussddemo.spring.service.UssdSessionStore;
import com.example.ussddemo.spring.service.UssdWiring;
import com.microjainslee.api.InitialEventSelector;
import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.core.InMemoryProfileFacility;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;

import org.jboss.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;

/**
 * Wires the USSD demo: SBB type pools, HTTP + gRPC RAs, profile seed,
 * and initial-event routing for {@link HttpUssdBeginEvent}.
 */
@Configuration
public class UssdDemoBootstrap {

    private static final Logger LOG = Logger.getLogger(UssdDemoBootstrap.class);

    @Autowired
    private MicroSleeContainer container;

    @Autowired
    private UssdWiring wiring;

    @Autowired
    private UssdSessionStore sessionStore;

    @Autowired
    private UssdCallbackDispatcher callbackDispatcher;

    @Value("${ussd.demo.http.port:8081}")
    private int httpPort;

    @Value("${ussd.demo.grpc.host:127.0.0.1}")
    private String grpcHost;

    @Value("${ussd.demo.grpc.port:9090}")
    private int grpcPort;

    @Value("${ussd.demo.grpc.use-in-memory:false}")
    private boolean useInMemoryGrpc;

    @Value("${ussd.demo.grpc.latency-ms:10}")
    private long inMemoryGrpcLatencyMs;

    private GrpcMenuClient remoteGrpcClient;

    @Bean
    public SmartLifecycle ussdDemoLifecycle() {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public boolean isAutoStartup() {
                return true;
            }

            @Override
            public int getPhase() {
                return Integer.MIN_VALUE + 200;
            }

            @Override
            public void start() {
                wireSlee();
                running = true;
                LOG.info("USSD demo bootstrap complete (HTTP RA port="
                        + wiring.httpRa().boundPort() + ")");
            }

            @Override
            public void stop() {
                if (remoteGrpcClient != null) {
                    remoteGrpcClient.close();
                    remoteGrpcClient = null;
                }
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    private void wireSlee() {
        wiring.setContainer(container);

        container.setInitialEventSelectorCustomizer(this::customizeInitialEvent);

        seedProfiles();

        HttpIngressResourceAdaptor httpRa = new HttpIngressResourceAdaptor();
        httpRa.setSessionStore(sessionStore);
        httpRa.setPort(httpPort);
        activateRa(httpRa, "http-ingress");
        wiring.setHttpRa(httpRa);

        GrpcMenuResourceAdaptor grpcRa = new GrpcMenuResourceAdaptor();
        grpcRa.setContainer(container);
        grpcRa.setGrpcMenuResolver(grpcResolver());
        activateRa(grpcRa, "grpc-menu");
        wiring.setGrpcRa(grpcRa);
    }

    private void activateRa(com.microjainslee.api.ResourceAdaptor ra, String entityName) {
        RaBootstrapContextImpl ctx = new RaBootstrapContextImpl(container, entityName);
        ctx.setResourceAdaptor(ra);
        ra.setResourceAdaptorContext(ctx);
        ra.raConfigure();
        ra.raActive();
    }

    private void customizeInitialEvent(InitialEventSelector selector) {
        if (selector.getEvent() instanceof HttpUssdBeginEvent) {
            String sessionId = ((HttpUssdBeginEvent) selector.getEvent()).getSessionId();
            String httpId = sessionId + "/HttpServer";
            HttpServerSbb httpSbb = new HttpServerSbb(wiring, sessionStore, callbackDispatcher);
            container.registerSbb(httpId, httpSbb);
            selector.setRootSbbId(httpId);
        }
    }

    private GrpcMenuResolver grpcResolver() {
        if (useInMemoryGrpc) {
            InMemoryGrpcMenuClient inMemory = new InMemoryGrpcMenuClient(inMemoryGrpcLatencyMs);
            return inMemory::resolveMenu;
        }
        remoteGrpcClient = new GrpcMenuClient(grpcHost, grpcPort);
        return remoteGrpcClient::resolveMenu;
    }

    private void seedProfiles() {
        ProfileFacility facility = container.getProfileFacility();
        if (!(facility instanceof InMemoryProfileFacility)) {
            LOG.warn("Profile facility is not in-memory; skipping seed");
            return;
        }
        InMemoryProfileFacility inMemory = (InMemoryProfileFacility) facility;
        inMemory.createProfileTable("ussdSubscribers");
        seedSubscriber(inMemory, "251911000001", 2);
        seedSubscriber(inMemory, "251911000002", 1);
    }

    private static void seedSubscriber(InMemoryProfileFacility facility,
                                       String msisdn, int tier) {
        try {
            facility.createProfile("ussdSubscribers", msisdn, UssdSubscriberProfile.class);
            ProfileLocalObject plo = facility.getProfile(
                    new com.microjainslee.api.ProfileID("ussdSubscribers", msisdn));
            Profile profile = plo == null ? null : plo.getProfile();
            if (profile instanceof UssdSubscriberProfile) {
                UssdSubscriberProfile sub = (UssdSubscriberProfile) profile;
                sub.setMsisdn(msisdn);
                sub.setMenuTier(tier);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to seed profile for %s", msisdn);
        }
    }
}
