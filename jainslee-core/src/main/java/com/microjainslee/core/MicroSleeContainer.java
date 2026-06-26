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
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SbbID;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.TimerPort;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedded JAIN-SLEE micro-container foundation with no JBoss Modules, VFS, MSC, or JMX dependency.
 */
public final class MicroSleeContainer {

    public enum State {
        CREATED, STARTED, STOPPED
    }

    private final MicroSleeConfiguration configuration;
    private final InMemoryActivityContextNamingFacility activityContextNamingFacility;
    private final EventRouter eventRouter;
    private final TimerPortImpl timerPort;
    private final VirtualThreadSbbEntityPool sbbEntityPool;
    private final ConcurrentHashMap<String, SimpleSbbLocalObject> sbbs =
            new ConcurrentHashMap<String, SimpleSbbLocalObject>();
    private volatile State state = State.CREATED;
    private volatile ClassLoader deploymentClassLoader;

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
        // Tell the EventRouter to dispatch onto the SBB's owning virtual thread.
        this.eventRouter.bindSbbEntityPool(this.sbbEntityPool);
        this.deploymentClassLoader = Thread.currentThread().getContextClassLoader();
    }

    public synchronized void start() {
        if (state == State.STARTED) {
            return;
        }
        // Pre-warm the SBB entity pool so virtual threads are parked and ready
        // before the first event arrives.
        sbbEntityPool.prewarm(sbbEntityPool.getMin());
        state = State.STARTED;
    }

    public synchronized void stop() {
        if (state == State.STOPPED) {
            return;
        }
        sbbEntityPool.shutdown();
        timerPort.getBridge().shutdown();
        eventRouter.shutdown();
        activityContextNamingFacility.clear();
        sbbs.clear();
        state = State.STOPPED;
    }

    public SimpleSbbLocalObject registerSbb(String id, Sbb sbb) {
        if (state != State.STARTED) {
            throw new IllegalStateException("Container must be started before registering SBBs");
        }
        // Acquire (or create) the per-SBB virtual-thread entity, which also
        // wraps the canonical SbbLocalObject for this SBB ID. All future events
        // for this ID are delivered onto the entity's owning virtual thread
        // (see EventRouter.dispatch), giving the spec-mandated single-threaded
        // event ordering.
        final VirtualThreadSbbEntityPool.SbbEntity entity = sbbEntityPool.acquire(id, () -> sbb);
        final SbbID sbbID = new SbbID(id);
        final SimpleSbbLocalObject localObject = new SimpleSbbLocalObject(sbbID, entity.getSbb());
        // Bind the per-SBB context ON the virtual thread so sbbCreate() / sbbActivate()
        // observe the same single-threaded ordering as runtime events.
        entity.submit(() -> {
            SimpleSbbContext ctx = new SimpleSbbContext(localObject, timerPort,
                    activityContextNamingFacility);
            Sbb sbbInstance = entity.getSbb();
            sbbInstance.setSbbContext(ctx);
            sbbInstance.sbbCreate();
            sbbInstance.sbbActivate();
        });
        sbbs.put(id, localObject);
        return localObject;
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
        aci.attach(sbbLocalObject);
        timerPort.getBridge().bindActivityContext(sbbLocalObject, aci);
    }

    public void routeEvent(SleeEvent event, ActivityContextInterface aci) {
        eventRouter.routeEvent(event, aci);
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

    public MicroSleeConfiguration getConfiguration() {
        return configuration;
    }

    /** Returns the per-SBB virtual-thread entity pool. */
    public VirtualThreadSbbEntityPool getSbbEntityPool() {
        return sbbEntityPool;
    }
}
