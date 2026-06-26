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
import com.microjainslee.api.InitialEventSelector;
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
    private final VirtualThreadSbbEntityPool sbbEntityPool;
    private final ServiceRegistry serviceRegistry = new ServiceRegistry();
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
        this.sbbEntityPool = new VirtualThreadSbbEntityPool(
                configuration.getSbbPoolMin(),
                configuration.getSbbPoolMax(),
                configuration.isSbbPerVirtualThread());
        this.eventRouter.bindSbbEntityPool(this.sbbEntityPool);
        this.eventRouter.bindTransactionSupport(timerPort.getBridge(),
                new DefaultErrorHandlingPolicy(timerPort.getBridge()));
        this.deploymentClassLoader = Thread.currentThread().getContextClassLoader();
    }

    public synchronized void start() {
        if (state == State.STARTED) {
            return;
        }
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
                    }
                },
                0);
        entity.submit(new Runnable() {
            @Override
            public void run() {
                SimpleSbbContext ctx = new SimpleSbbContext(serviceID, localObject, timerPort,
                        activityContextNamingFacility);
                Sbb sbbInstance = entity.getSbb();
                sbbInstance.setSbbContext(ctx);
                sbbInstance.sbbCreate();
                sbbInstance.sbbActivate();
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
}
