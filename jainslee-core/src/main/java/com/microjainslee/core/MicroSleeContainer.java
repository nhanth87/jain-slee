/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.CreateException;
import com.microjainslee.api.InitialEventSelector;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.ServiceID;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.TimerPort;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded JAIN-SLEE micro-container foundation with no JBoss Modules, VFS, MSC, or JMX dependency.
 */
public final class MicroSleeContainer {

    private static final Logger LOG = LogManager.getLogger(MicroSleeContainer.class);

    public enum State {
        CREATED, STARTED, STOPPED
    }

    private final MicroSleeConfiguration configuration;
    private final InMemoryActivityContextNamingFacility activityContextNamingFacility;
    private final EventRouter eventRouter;
    private final TimerPortImpl timerPort;
    private VirtualThreadSbbEntityPool sbbEntityPool;
    private final ServiceRegistry serviceRegistry = new ServiceRegistry();
    private final InMemoryCmpFieldStore cmpFieldStore = new InMemoryCmpFieldStore();
    private final InMemoryProfileFacility profileFacility = new InMemoryProfileFacility();
    private final SbbLifecycleManager sbbLifecycleManager = new SbbLifecycleManager();
    private final ConcurrentHashMap<String, SimpleSbbLocalObject> sbbs =
            new ConcurrentHashMap<String, SimpleSbbLocalObject>();
    private final ConcurrentHashMap<String, RaBootstrapContextImpl> resourceAdaptors =
            new ConcurrentHashMap<String, RaBootstrapContextImpl>();
    private volatile State state = State.CREATED;
    private volatile ClassLoader deploymentClassLoader;
    private volatile InitialEventSelectorCustomizer initialEventSelectorCustomizer;

    public MicroSleeContainer() {
        this(MicroSleeConfiguration.defaults());
    }

    public MicroSleeContainer(MicroSleeConfiguration configuration) {
        this.configuration = configuration;
        this.activityContextNamingFacility = new InMemoryActivityContextNamingFacility();
        this.eventRouter = new EventRouter(configuration.getEventRouterBufferSize(),
                configuration.isPreferVirtualThreads(),
                configuration.isSbbPerVirtualThread());
        this.timerPort = TimerPortImpl.create(eventRouter);
        this.sbbEntityPool = newSbbEntityPool();
        this.eventRouter.bindSbbEntityPool(this.sbbEntityPool);
        this.eventRouter.bindTransactionSupport(timerPort.getBridge(),
                new DefaultErrorHandlingPolicy(timerPort.getBridge()));
        this.deploymentClassLoader = Thread.currentThread().getContextClassLoader();
    }

    /**
     * Build a fresh {@link VirtualThreadSbbEntityPool} from the current
     * configuration. Used both for the initial construction and to rebuild the
     * pool after {@link #stop()} shut the previous one down.
     */
    private VirtualThreadSbbEntityPool newSbbEntityPool() {
        return new VirtualThreadSbbEntityPool(
                configuration.getSbbPoolMin(),
                configuration.getSbbPoolMax(),
                configuration.isSbbPerVirtualThread());
    }

    public synchronized void start() {
        if (state == State.STARTED) {
            return;
        }
        // The SBB entity pool's underlying executor is shut down by stop();
        // rebuild it so a stop/start round-trip yields a usable pool.
        if (sbbEntityPool == null || sbbEntityPool.isShutdown()) {
            sbbEntityPool = newSbbEntityPool();
            eventRouter.bindSbbEntityPool(sbbEntityPool);
            LOG.info("Re-created SBB entity pool after previous stop()");
        }
        CmpFieldStoreLocator.set(cmpFieldStore);
        sbbEntityPool.prewarm(sbbEntityPool.getMin());
        state = State.STARTED;
        autoDeployFromClasspathIndex();
    }

    public synchronized void stop() {
        if (state == State.STOPPED) {
            return;
        }
        for (ServiceID serviceID : serviceRegistry.snapshot().keySet()) {
            if (serviceRegistry.isActive(serviceID)) {
                serviceRegistry.stop(serviceID);
            }
        }
        for (RaBootstrapContextImpl context : resourceAdaptors.values()) {
            ResourceAdaptor ra = context.getResourceAdaptor();
            if (ra != null) {
                ra.raStopping();
                ra.raInactive();
                ra.raUnconfigure();
            }
        }
        resourceAdaptors.clear();
        sbbEntityPool.shutdown();
        timerPort.getBridge().shutdown();
        eventRouter.shutdown();
        activityContextNamingFacility.clear();
        sbbs.clear();
        CmpFieldStoreLocator.set(null);
        profileFacility.shutdown();
        state = State.STOPPED;
    }

    public SimpleSbbLocalObject registerSbb(String id, Sbb sbb) {
        return registerSbb(id, sbb, new ServiceID(id, "com.microjainslee", "1.0"));
    }

    public SimpleSbbLocalObject registerSbb(String id, Sbb sbb, ServiceID serviceID) {
        if (state != State.STARTED) {
            throw new IllegalStateException("Container must be started before registering SBBs");
        }
        if (sbbs.containsKey(id)) {
            return sbbs.get(id);
        }
        final VirtualThreadSbbEntityPool.SbbEntity entity = sbbEntityPool.acquire(id, () -> sbb);
        final SbbID sbbID = new SbbID(id);
        final SimpleSbbLocalObject localObject = new SimpleSbbLocalObject(
                sbbID,
                entity.getSbb(),
                sbbEntityPool,
                new SimpleSbbLocalObject.RemovalListener() {
                    @Override
                    public void onRemoved(SimpleSbbLocalObject removedObject) {
                        detachFromAllActivityContexts(removedObject);
                        sbbs.remove(id);
                        sbbEntityPool.release(entity);
                        // Persist any in-flight CMP mutations the SBB did
                        // through CmpAccessorInvoker before sbbRemove ran,
                        // then drop the persistent representation. CmpBackedSbb
                        // subclasses can override cmpPersist() to flush custom
                        // state into the store before we drop the entity.
                        Sbb sbbInstance = removedObject.getSbb();
                        if (sbbInstance instanceof CmpBackedSbb) {
                            try {
                                ((CmpBackedSbb) sbbInstance).cmpPersist();
                            } catch (RuntimeException ignored) {
                                // best effort
                            }
                        }
                        cmpFieldStore.remove(id);
                    }
                },
                0);
        // SYNCHRONOUSLY bind entity id + CMP store on the SBB instance
        // BEFORE submitting to the entity thread. This ensures callers
        // can use CMP accessors immediately after registerSbb() returns,
        // without racing the async activation.
        if (sbb instanceof CmpBackedSbb) {
            CmpBackedSbb backed = (CmpBackedSbb) sbb;
            backed.setSbbEntityId(id);
            backed.setCmpFieldStore(cmpFieldStore);
        }
        entity.submit(new Runnable() {
            @Override
            public void run() {
                SimpleSbbContext ctx = new SimpleSbbContext(serviceID, localObject, sbbID, timerPort,
                        activityContextNamingFacility, profileFacility);
                Sbb sbbInstance = entity.getSbb();
                try {
                    // Phase A state machine: setSbbContext -> sbbCreate -> sbbPostCreate -> sbbActivate.
                    // Falls back to the legacy direct invocation when sbbCreate throws
                    // a non-CreateException failure (e.g. a RuntimeException from user code).
                    sbbLifecycleManager.create(sbbInstance, ctx, null);
                    sbbLifecycleManager.postCreate(sbbInstance);
                    // Pre-populate CMP store on entity state so the SBB can read it
                    // through the (reflection-based) CmpAccessorInvoker during sbbLoad.
                    Map<String, Object> cmpState = cmpFieldStore.load(id);
                    localObject.getEntityState().getCmpFields().putAll(cmpState);
                    sbbLifecycleManager.activate(sbbInstance, cmpState);
                    // SbbLifecycleManager.activate() already transitions to READY; mirror
                    // the state in the per-entity state object as well.
                    localObject.getEntityState().transitionTo(SbbLifecycleManager.State.READY);
                } catch (CreateException ce) {
                    LOG.warn("SBB {} failed sbbCreate/sbbPostCreate: {}", id, ce.getMessage());
                    sbbLifecycleManager.removeEntity(sbbInstance);
                    sbbs.remove(id);
                    sbbEntityPool.release(entity);
                    cmpFieldStore.remove(id);
                    throw new RuntimeException(ce);
                } catch (RuntimeException re) {
                    LOG.error("SBB {} activation failed: {}", id, re.getMessage(), re);
                    sbbs.remove(id);
                    cmpFieldStore.remove(id);
                    throw re;
                }
            }
        });
        sbbs.put(id, localObject);
        return localObject;
    }

    private void autoDeployFromClasspathIndex() {
        try {
            SbbIndexLoader.SbbIndex index = SbbIndexLoader.load(deploymentClassLoader);
            if (index.isEmpty()) {
                LOG.debug("No {} entries found on classpath", SbbIndexLoader.INDEX_RESOURCE);
                return;
            }
            LOG.info("Auto-deploying from {}: {} sbb(s), {} eventType(s), {} du(s)",
                    SbbIndexLoader.INDEX_RESOURCE,
                    index.getSbbs().size(),
                    index.getEventTypes().size(),
                    index.getDeployableUnits().size());

            for (SbbIndexLoader.SbbIndexEntry entry : index.getSbbs()) {
                deploySbbEntry(entry);
            }
            for (SbbIndexLoader.DeployableUnitIndexEntry du : index.getDeployableUnits()) {
                deployDeployableUnit(du, index);
            }
        } catch (IOException ioe) {
            throw new IllegalStateException("Failed to load " + SbbIndexLoader.INDEX_RESOURCE, ioe);
        }
    }

    private void deploySbbEntry(SbbIndexLoader.SbbIndexEntry entry) {
        if (sbbs.containsKey(entry.getName())) {
            LOG.debug("SBB {} already registered — skipping", entry.getName());
            return;
        }
        Sbb sbb = instantiateComponent(entry.getClassName(), Sbb.class);
        ServiceID serviceID = new ServiceID(entry.getName(), entry.getVendor(), entry.getVersion());
        registerSbb(entry.getName(), sbb, serviceID);
        LOG.info("Auto-registered SBB {} ({})", entry.getName(), entry.getClassName());
    }

    private void deployDeployableUnit(SbbIndexLoader.DeployableUnitIndexEntry du,
                                      SbbIndexLoader.SbbIndex index) {
        ServiceID serviceID = new ServiceID(du.getName(), du.getVendor(), du.getVersion());
        serviceRegistry.activate(serviceID);
        LOG.info("Activated service {} from deployable unit {}", serviceID, du.getClassName());

        for (String sbbClassName : du.getSbbs()) {
            SbbIndexLoader.SbbIndexEntry entry = findSbbEntry(index, sbbClassName);
            if (entry != null) {
                deploySbbEntry(entry);
            } else {
                Sbb sbb = instantiateComponent(sbbClassName, Sbb.class);
                String id = simpleClassName(sbbClassName);
                registerSbb(id, sbb, serviceID);
                LOG.info("Auto-registered DU SBB {} ({})", id, sbbClassName);
            }
        }

        for (String raClassName : du.getRas()) {
            bootstrapResourceAdaptor(raClassName, du.getName());
        }
    }

    private static SbbIndexLoader.SbbIndexEntry findSbbEntry(SbbIndexLoader.SbbIndex index, String className) {
        for (SbbIndexLoader.SbbIndexEntry entry : index.getSbbs()) {
            if (entry.getClassName().equals(className)) {
                return entry;
            }
        }
        return null;
    }

    private static String simpleClassName(String className) {
        int dot = className.lastIndexOf('.');
        return dot >= 0 ? className.substring(dot + 1) : className;
    }

    public RaBootstrapContextImpl bootstrapResourceAdaptor(String raClassName, String entityName) {
        if (resourceAdaptors.containsKey(entityName)) {
            return resourceAdaptors.get(entityName);
        }
        ResourceAdaptor ra = instantiateComponent(raClassName, ResourceAdaptor.class);
        RaBootstrapContextImpl context = new RaBootstrapContextImpl(this, entityName);
        context.setResourceAdaptor(ra);
        ra.setResourceAdaptorContext(context);
        ra.raConfigure();
        ra.raActive();
        resourceAdaptors.put(entityName, context);
        LOG.info("Bootstrapped resource adaptor {} as entity {}", raClassName, entityName);
        return context;
    }

    private <T> T instantiateComponent(String className, Class<T> expectedType) {
        try {
            Class<?> clazz = Class.forName(className, true, deploymentClassLoader);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            if (!expectedType.isInstance(instance)) {
                throw new IllegalStateException(className + " is not a " + expectedType.getSimpleName());
            }
            return expectedType.cast(instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate " + className, e);
        }
    }

    public InMemoryActivityContext createActivityContext(String name) {
        InMemoryActivityContext aci = new InMemoryActivityContext(name);
        activityContextNamingFacility.bind(name, aci);
        return aci;
    }

    public void attach(String activityContextName, SbbLocalObject sbbLocalObject) {
        ActivityContextInterface aci = activityContextNamingFacility.lookup(activityContextName);
        if (aci == null) {
            throw new IllegalArgumentException("Unknown activity context: " + activityContextName);
        }
        if (!(aci instanceof InMemoryActivityContext)) {
            throw new IllegalArgumentException("Unsupported activity context type: " + aci.getClass());
        }
        InMemoryActivityContext activityContext = (InMemoryActivityContext) aci;
        SbbTransactionContext transaction =
                ActivityContextTransactionRegistry.currentFor(activityContext);
        if (transaction != null) {
            transaction.recordAttach(sbbLocalObject);
            transaction.recordTimerBind(sbbLocalObject);
            return;
        }
        activityContext.attachImmediate(sbbLocalObject);
        timerPort.getBridge().bindActivityContext(sbbLocalObject, aci);
    }

    private void detachFromAllActivityContexts(SbbLocalObject sbbLocalObject) {
        for (ActivityContextInterface aci : activityContextNamingFacility.getBoundContexts()) {
            if (aci instanceof InMemoryActivityContext) {
                ((InMemoryActivityContext) aci).detachImmediate(sbbLocalObject);
            }
        }
        timerPort.getBridge().unbindActivityContext(sbbLocalObject);
    }

    public void setInitialEventSelectorCustomizer(InitialEventSelectorCustomizer customizer) {
        this.initialEventSelectorCustomizer = customizer;
    }

    public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
        if (aci instanceof InMemoryActivityContext) {
            InMemoryActivityContext activityContext = (InMemoryActivityContext) aci;
            if (activityContext.getAttachedSbbs().isEmpty()) {
                attachRootSbbViaInitialEventSelector(event, activityContext);
            }
        }
        eventRouter.routeEvent(event, aci);
    }

    private void attachRootSbbViaInitialEventSelector(SleeEvent event,
            InMemoryActivityContext activityContext) {
        InitialEventSelector selector = new DefaultInitialEventSelector(event, activityContext);
        InitialEventSelectorCustomizer customizer = initialEventSelectorCustomizer;
        if (customizer != null) {
            customizer.customize(selector);
        }
        if (!selector.isInitialEvent()) {
            return;
        }
        String rootSbbId = selector.getRootSbbId();
        SimpleSbbLocalObject root = rootSbbId != null ? sbbs.get(rootSbbId) : null;
        if (root == null && !sbbs.isEmpty()) {
            root = sbbs.values().iterator().next();
        }
        if (root != null) {
            activityContext.attachImmediate(root);
            timerPort.getBridge().bindActivityContext(root, activityContext);
        }
    }

    public ClassLoader createDeploymentClassLoader(File deploymentDirectory) throws MalformedURLException {
        if (deploymentDirectory == null) {
            throw new IllegalArgumentException("deploymentDirectory is required");
        }
        URL url = deploymentDirectory.toURI().toURL();
        deploymentClassLoader = new URLClassLoader(new URL[] { url },
                Thread.currentThread().getContextClassLoader());
        return deploymentClassLoader;
    }

    public State getState() {
        return state;
    }

    public ClassLoader getDeploymentClassLoader() {
        return deploymentClassLoader;
    }

    public EventRouter getEventRouter() {
        return eventRouter;
    }

    public TimerPort getTimerPort() {
        return timerPort;
    }

    public InMemoryActivityContextNamingFacility getActivityContextNamingFacility() {
        return activityContextNamingFacility;
    }

    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    public MicroSleeConfiguration getConfiguration() {
        return configuration;
    }

    public VirtualThreadSbbEntityPool getSbbEntityPool() {
        return sbbEntityPool;
    }

    /**
     * Phase A — access the CMP field store. Returns the in-memory backend
     * by default; embedders may wire in a Redis/JPA-backed implementation.
     */
    public CmpFieldStore getCmpFieldStore() {
        return cmpFieldStore;
    }

    /**
     * Phase 2 — access the profile facility. Returns the in-memory backend
     * by default; embedders may wire in a JPA / Redis-backed
     * implementation through {@link #installProfileFacility(ProfileFacility)}
     * before {@link #start()} runs.
     */
    public ProfileFacility getProfileFacility() {
        return profileFacility;
    }

    /**
     * Phase A — access the SBB lifecycle state machine used during
     * {@link #registerSbb(String, Sbb)}.
     */
    public SbbLifecycleManager getSbbLifecycleManager() {
        return sbbLifecycleManager;
    }

    /**
     * Phase B — factory that creates a child SBB entity under the given
     * parent id. Delegates to {@link #registerSbb(String, Sbb)} using a
     * freshly-constructed child SBB of the same class as the parent (this
     * is the simplest reasonable behaviour for an R&D container; a
     * production deployment would tie this to a service-bound
     * {@code <sbb-ref>} from the deployment descriptor).
     *
     * <p>Returns the local object of the new child so the parent SBB can
     * immediately narrow it to its typed interface.
     */
    public SbbLocalObject createChildSbb(String parentSbbId, String childSbbId) {
        return createChildSbb(parentSbbId, childSbbId, null);
    }

    /**
     * Phase B — create a child SBB entity under the given parent, using
     * {@code childFactory} to materialise the child instance. When
     * {@code childFactory} is {@code null}, the container falls back to
     * reflection (looking up a no-arg constructor on the parent's SBB
     * class) — which works for top-level classes but fails for anonymous
     * or non-static inner SBBs. Embedders that use anonymous classes
     * should pass an explicit factory.
     */
    public SbbLocalObject createChildSbb(String parentSbbId, String childSbbId,
                                         java.util.function.Function<String, Sbb> childFactory) {
        if (parentSbbId == null || childSbbId == null) {
            throw new IllegalArgumentException("parentSbbId and childSbbId are required");
        }
        SimpleSbbLocalObject parent = sbbs.get(parentSbbId);
        if (parent == null) {
            throw new IllegalStateException("Unknown parent SBB entity: " + parentSbbId);
        }
        Sbb childInstance;
        if (childFactory != null) {
            childInstance = childFactory.apply(childSbbId);
            if (childInstance == null) {
                throw new IllegalStateException(
                        "Child factory returned null for child " + childSbbId);
            }
        } else {
            // Re-use the parent's Sbb class for the child via reflection.
            // Production stacks derive this from the deployment descriptor;
            // micro-jainslee keeps the convention simple. We use the
            // declared no-arg constructor (rather than Class.newInstance
            // which is deprecated since Java 9 and also requires public
            // access) so private and package-private constructors are
            // accepted too.
            try {
                java.lang.reflect.Constructor<? extends Sbb> ctor =
                        parent.getSbb().getClass().getDeclaredConstructor();
                ctor.setAccessible(true);
                childInstance = ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Cannot instantiate child SBB of class "
                        + parent.getSbb().getClass().getName() + ": " + e.getMessage(), e);
            }
        }
        return registerSbb(childSbbId, childInstance,
                new ServiceID(childSbbId, "com.microjainslee", "1.0"));
    }

    /**
     * Phase B — convenience: get the {@link ChildRelationFactory} that
     * child relations should use to spawn new children. The factory
     * simply delegates to {@link #createChildSbb(String, String)} with
     * a UUID-derived child id.
     */
    public ChildRelationFactory getChildRelationFactory() {
        return new ChildRelationFactory() {
            @Override
            public SbbLocalObject createChild(String parentSbbId) {
                String childId = parentSbbId + ".child." + java.util.UUID.randomUUID();
                return createChildSbb(parentSbbId, childId, null);
            }
        };
    }

    /**
     * Phase B — alternate factory that uses the given supplier to
     * materialise each child SBB instance. Use this when your SBB class
     * cannot be instantiated via reflection (anonymous / inner class).
     */
    public ChildRelationFactory getChildRelationFactory(
            final java.util.function.Function<String, Sbb> childSupplier) {
        return new ChildRelationFactory() {
            @Override
            public SbbLocalObject createChild(String parentSbbId) {
                String childId = parentSbbId + ".child." + java.util.UUID.randomUUID();
                return createChildSbb(parentSbbId, childId, childSupplier);
            }
        };
    }
}
