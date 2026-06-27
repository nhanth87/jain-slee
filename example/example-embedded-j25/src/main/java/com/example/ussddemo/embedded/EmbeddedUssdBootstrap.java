/*
 * micro-jainslee 1.1.0 -- example application (example-embedded-j25)
 */

package com.example.ussddemo.embedded;

import com.example.ussddemo.ra.GrpcMenuClient;
import com.example.ussddemo.ra.GrpcMenuResourceAdaptor;
import com.example.ussddemo.ra.HttpIngressResourceAdaptor;
import com.example.ussddemo.sbbs.GrpcClientSbb;
import com.example.ussddemo.sbbs.HttpServerSbb;
import com.example.ussddemo.sbbs.Ss7UssdIngressSbb;
import java.util.concurrent.ConcurrentHashMap;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.RaBootstrapContextImpl;
import com.microjainslee.core.SbbLifecycleManager;
import com.microjainslee.core.SimpleSbbLocalObject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Wires resource adaptors, pooled SBB types, and seeded subscriber profiles.
 */
public final class EmbeddedUssdBootstrap {

    private static final Logger LOG = LogManager.getLogger(EmbeddedUssdBootstrap.class);

    public static final String HTTP_RA = "http-ingress";
    public static final String GRPC_RA = "grpc-menu";
    public static final String PROFILE_TABLE = "ussdSubscribers";

    private final MicroSleeContainer container;
    private final UssdSessionStore sessionStore;
    private final ConcurrentHashMap<String, String> tiersByMsisdn =
            new ConcurrentHashMap<String, String>();
    private HttpIngressResourceAdaptor httpRa;
    private GrpcMenuResourceAdaptor grpcRa;
    private GrpcMenuClient grpcClient;

    public EmbeddedUssdBootstrap(MicroSleeContainer container, UssdSessionStore sessionStore) {
        this.container = container;
        this.sessionStore = sessionStore;
    }

    public void install(int httpPort, String grpcHost, int grpcPort) {
        seedProfiles();
        wireResourceAdaptors(httpPort, grpcHost, grpcPort);
        LOG.info("Embedded USSD bootstrap complete (httpPort={}, grpc={}:{})",
                httpPort, grpcHost, grpcPort);
    }

    /** Call before {@link MicroSleeContainer#start()} when possible. */
    public void registerSbbTypesOnly() {
        registerSbbTypes();
    }

    public void seedProfilesOnly() {
        seedProfiles();
    }

    public void wireGrpcRa(String grpcHost, int grpcPort) {
        grpcRa = new GrpcMenuResourceAdaptor();
        grpcClient = new GrpcMenuClient(grpcHost, grpcPort);
        grpcRa.setGrpcMenuClient(grpcClient);
        activateRa(grpcRa, GRPC_RA);
    }

    public void shutdown() {
        if (grpcRa != null) {
            grpcRa.raStopping();
            grpcRa.raInactive();
            grpcRa.raUnconfigure();
        }
        if (httpRa != null) {
            httpRa.raStopping();
            httpRa.raInactive();
            httpRa.raUnconfigure();
        }
        if (grpcClient != null) {
            grpcClient.close();
            grpcClient = null;
        }
    }

    public String tierFor(String msisdn) {
        return tiersByMsisdn.getOrDefault(msisdn, "STANDARD");
    }

    public HttpIngressResourceAdaptor httpRa() {
        return httpRa;
    }

    public GrpcMenuResourceAdaptor grpcRa() {
        return grpcRa;
    }

    public String httpEntityId(String sessionId) {
        return "HttpServer/" + sessionId;
    }

    public String ss7EntityId(String sessionId) {
        return "Ss7UssdIngress/" + sessionId;
    }

    public void prepareHttpSession(String sessionId, String callbackUrl,
                                   ActivityContextInterface aci) {
        sessionStore.open(sessionId);
        sessionStore.attachCallback(sessionId, callbackUrl);
        HttpServerSbb httpSbb = new HttpServerSbb();
        SimpleSbbLocalObject httpLo = container.registerSbb(httpEntityId(sessionId), httpSbb);
        httpLo.setPriority(15);
        httpSbb.bindSelf(httpLo);
        container.attach(sessionId, httpLo);
        try {
            waitForActivation(httpLo);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP SBB activation interrupted", e);
        }
        if (aci instanceof com.microjainslee.core.InMemoryActivityContext) {
            int attached = ((com.microjainslee.core.InMemoryActivityContext) aci)
                    .getAttachedSbbs().size();
            if (attached == 0) {
                throw new IllegalStateException(
                        "HTTP SBB attach failed for session " + sessionId);
            }
            LOG.debug("Prepared HTTP session {} with {} attached SBB(s)",
                    sessionId, attached);
        }
    }

    public void releaseSession(String sessionId) {
        container.releaseEntity(ss7EntityId(sessionId));
        container.releaseEntity(httpEntityId(sessionId));
    }

    private void registerSbbTypes() {
        container.registerSbbType(Ss7UssdIngressSbb.class, Ss7UssdIngressSbb::new);
        container.registerSbbType(GrpcClientSbb.class, GrpcClientSbb::new);
        LOG.info("Registered pooled SBB types: Ss7UssdIngress, GrpcClient");
    }

    private void seedProfiles() {
        ProfileFacility facility = container.getProfileFacility();
        facility.createProfileTable(PROFILE_TABLE);
        seedSubscriber(facility, "251911000001", "GOLD");
        seedSubscriber(facility, "251911000002", "SILVER");
        LOG.info("Seeded {} subscriber profiles", 2);
    }

    private void seedSubscriber(ProfileFacility facility, String msisdn, String tier) {
        try {
            ProfileLocalObject plo = facility.createProfile(
                    PROFILE_TABLE, msisdn, UssdSubscriberProfile.class);
            Profile profile = plo.getProfile();
            UssdSubscriberProfile sub = (UssdSubscriberProfile) profile;
            sub.setMsisdn(msisdn);
            sub.setTier(tier);
            tiersByMsisdn.put(msisdn, tier);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to seed profile for " + msisdn, e);
        }
    }

    private void wireResourceAdaptors(int httpPort, String grpcHost, int grpcPort) {
        httpRa = new HttpIngressResourceAdaptor();
        httpRa.setPort(httpPort);
        httpRa.setSessionStore(sessionStore);
        httpRa.setSessionPreparer(this::prepareHttpSession);
        activateRa(httpRa, HTTP_RA);

        grpcRa = new GrpcMenuResourceAdaptor();
        grpcClient = new GrpcMenuClient(grpcHost, grpcPort);
        grpcRa.setGrpcMenuClient(grpcClient);
        activateRa(grpcRa, GRPC_RA);
    }

    private void activateRa(ResourceAdaptor ra, String entityName) {
        RaBootstrapContextImpl ctx = new RaBootstrapContextImpl(container, entityName);
        ctx.setResourceAdaptor(ra);
        ra.setResourceAdaptorContext(ctx);
        ra.raConfigure();
        ra.raActive();
    }

    private static void waitForActivation(SimpleSbbLocalObject lo) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (lo.getEntityState().getLifecycleState() == SbbLifecycleManager.State.READY) {
                return;
            }
            Thread.sleep(10L);
        }
    }
}
