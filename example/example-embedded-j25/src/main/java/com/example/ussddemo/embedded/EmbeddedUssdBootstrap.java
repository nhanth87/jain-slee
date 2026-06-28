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
import com.microjainslee.core.SbbLifecycleManager;
import com.microjainslee.core.SimpleSbbLocalObject;
import com.microjainslee.core.ies.InitialEventSelectorDispatcher;
import com.microjainslee.core.ra.ResourceAdaptorContextBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Wires resource adaptors, pooled SBB types, and seeded subscriber profiles.
 *
 * <p>Updated for Perfect Core S1-S5 (2026-06-28):</p>
 * <ul>
 *   <li><b>S5</b> — RA lifecycle now goes through
 *       {@link ResourceAdaptorContextBuilder#build(MicroSleeContainer,
 *       ResourceAdaptor, String)} instead of the legacy
 *       {@code RaBootstrapContextImpl} shim.</li>
 *   <li><b>S3</b> — bind an {@link InitialEventSelectorDispatcher} on
 *       the container so the {@code @InitialEventSelect} methods on
 *       the SBBs take effect.</li>
 * </ul>
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
        // Perfect Core S2 — register the concrete subclass that the
        // Javassist generator (or our hand-written $Concrete stub)
        // emits for the abstract Ss7UssdIngressSbb. The pool will
        // instantiate $Concrete via its no-arg constructor.
        container.registerSbbType(Ss7UssdIngressSbb.class,
                Ss7UssdIngressSbb.$Concrete::new);
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
        // Perfect Core S5 — drive the full RA lifecycle through the
        // kernel-side factory. The legacy RaBootstrapContextImpl shim is
        // replaced by ResourceAdaptorContextBuilder.build which wires
        // the RA to the live container's EventRouter, ACNF, TimerBridge,
        // AlarmFacility, TraceFacility, NullActivityFactory, and
        // EventLookupFacility in one call.
        ResourceAdaptorContextBuilder.build(container, ra, entityName);
    }

    /**
     * Perfect Core S3 — bind the Initial Event Selector dispatcher so
     * the event router honours {@code @InitialEventSelect} methods on
     * the SBBs (e.g. {@link Ss7UssdIngressSbb#selectInitialEvent}).
     * Without this binding, every incoming
     * {@code Ss7UssdBeginEvent} would create a brand-new SBB entity,
     * breaking the USSD stateful protocol.
     */
    public void bindInitialEventSelector() {
        try {
            com.microjainslee.core.VirtualThreadSbbEntityPool pool = container.getSbbEntityPool();
            final java.util.concurrent.atomic.AtomicLong counter =
                    new java.util.concurrent.atomic.AtomicLong();
            InitialEventSelectorDispatcher.SbbEntityPool adapter =
                    new InitialEventSelectorDispatcher.SbbEntityPool() {
                        @Override
                        public String allocateNew(Class<?> sbbClass) {
                            String entityId = sbbClass.getSimpleName()
                                    + "#" + counter.incrementAndGet();
                            final Class<? extends com.microjainslee.api.Sbb> typedSbb =
                                    sbbClass.asSubclass(com.microjainslee.api.Sbb.class);
                            pool.acquire(entityId, () -> {
                                try {
                                    return typedSbb.getDeclaredConstructor().newInstance();
                                } catch (Exception e) {
                                    throw new IllegalStateException(
                                            "IES allocate factory failed for "
                                                    + sbbClass.getName(), e);
                                }
                            });
                            return entityId;
                        }

                        @Override
                        public boolean contains(String entityId) {
                            return pool.findEntity(entityId) != null;
                        }

                        @Override
                        public void onEntityRemoved(String entityId,
                                                     java.util.function.Consumer<String> callback) {
                            callback.accept(entityId);
                        }
                    };
            InitialEventSelectorDispatcher dispatcher =
                    new InitialEventSelectorDispatcher(adapter);
            container.setInitialEventSelectorDispatcher(dispatcher);
            LOG.info("Initial Event Selector dispatcher bound (S3)");
        } catch (RuntimeException e) {
            // log4j2 uses .warn(String, Throwable) instead of JBoss
            // Logger's .warnf(Throwable, String, ...) helper.
            LOG.warn("IES dispatcher bind failed — falling back to legacy allocate-per-event", e);
        }
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
