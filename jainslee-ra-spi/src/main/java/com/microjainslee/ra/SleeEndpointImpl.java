/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra;

import com.microjainslee.api.ActivityAlreadyExistsException;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ActivityContextNamingFacility;
import com.microjainslee.api.ActivityEndedEvent;
import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.EventType;
import com.microjainslee.api.FiredUnrecognizedEventException;
import com.microjainslee.api.FireableEventType;
import com.microjainslee.api.SleeEndpoint;
import com.microjainslee.api.UnrecognizedActivityException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Perfect Core S5 — full spec-compliant {@link SleeEndpoint}.
 * <p>
 * SleeEndpoint is the bridge between an RA and the SLEE container:
 * <ul>
 *   <li>{@link #activityStarted(ActivityHandle)} creates an ACI for
 *       the handle and registers the bidirectional mapping</li>
 *   <li>{@link #fireEvent} validates the call against the state
 *       machine and the declared event-type set, then routes the
 *       event through the kernel's {@link EventRouterPort}</li>
 *   <li>{@link #activityEnded(ActivityHandle)} fires
 *       {@link ActivityEndedEvent}, detaches SBBs, and unbinds the
 *       handle from the naming facility</li>
 * </ul>
 *
 * <h2>Validations per JAIN SLEE 1.1 §13.4</h2>
 * <ul>
 *   <li>Handle must be active (activityStarted was called)</li>
 *   <li>EventType must be in the RA's declared set</li>
 *   <li>State machine must be ACTIVE</li>
 *   <li>Address may be {@code null} for non-routed events</li>
 * </ul>
 *
 * <h2>Why this lives in jainslee-ra-spi</h2>
 * The original Mobicents/SleeEndpoint depends on the container's
 * internal EventRouter. We keep the kernel-side wiring in
 * {@code jainslee-core} and reach it through {@link EventRouterPort}
 * (a thin sealed interface) so {@code jainslee-ra-spi} stays
 * compile-time independent from the kernel module.
 */
public class SleeEndpointImpl implements SleeEndpoint {

    private static final Logger LOG = LogManager.getLogger(SleeEndpointImpl.class);

    private final EventRouterPort eventRouter;
    private final AcquireActivityContext aciFactory;
    private final ActivityContextNamingFacility acnf;
    private final RaEntityStateMachine stateMachine;

    /** Active handles: handle.getId() -> ActivityContextInterface */
    private final ConcurrentHashMap<String, ActivityContextInterface> activeHandles =
            new ConcurrentHashMap<>();

    /** Declared event types (RA sets these during raActive()). */
    private final Set<String> declaredEventTypeNames = ConcurrentHashMap.newKeySet();

    public SleeEndpointImpl(EventRouterPort eventRouter,
                            AcquireActivityContext aciFactory,
                            ActivityContextNamingFacility acnf,
                            RaEntityStateMachine stateMachine) {
        if (eventRouter == null) throw new IllegalArgumentException("eventRouter is required");
        if (aciFactory == null) throw new IllegalArgumentException("aciFactory is required");
        if (stateMachine == null) throw new IllegalArgumentException("stateMachine is required");
        this.eventRouter = eventRouter;
        this.aciFactory = aciFactory;
        this.acnf = acnf;
        this.stateMachine = stateMachine;
    }

    /**
     * RA declares which event types it will fire. Should be called
     * once during {@code raActive()}.
     */
    public void declareEventType(String eventTypeName) {
        if (eventTypeName == null || eventTypeName.isEmpty()) {
            throw new IllegalArgumentException("eventTypeName is required");
        }
        declaredEventTypeNames.add(eventTypeName);
        LOG.debug("Declared event type: {}", eventTypeName);
    }

    public void declareEventType(FireableEventType fet) {
        if (fet == null) throw new IllegalArgumentException("fet is required");
        EventType et = fet.getEventType();
        if (et != null) {
            declaredEventTypeNames.add(et.getName());
        }
    }

    public Set<String> declaredEventTypeNames() {
        return Set.copyOf(declaredEventTypeNames);
    }

    // ──────────────────────────────────────────────────────────
    // Activity lifecycle
    // ──────────────────────────────────────────────────────────

    /**
     * RA calls this when a new activity starts (e.g. incoming USSD
     * dialog, SIP INVITE). Creates an ACI for the handle and
     * registers the bidirectional mapping.
     */
    @Override
    public void activityStarted(ActivityHandle handle)
            throws ActivityAlreadyExistsException {
        if (handle == null) throw new IllegalArgumentException("handle is required");
        stateMachine.assertCanFireEvent();

        String handleId = handle.getId();
        if (activeHandles.containsKey(handleId)) {
            throw new ActivityAlreadyExistsException(
                    "ActivityHandle already active: " + handleId);
        }

        // Acquire an ACI from the kernel (or fall back to in-memory impl)
        ActivityContextInterface aci = aciFactory.acquire(handleId);
        activeHandles.put(handleId, aci);
        if (acnf != null) {
            acnf.bind(aci.getActivityContextName(), aci);
        }

        LOG.debug("Activity started: handle={} aci={}", handleId, aci.getActivityContextName());
    }

    /**
     * RA calls this when an activity ends.
     * Fires {@link ActivityEndedEvent}, detaches SBBs, cleans up
     * mapping.
     */
    @Override
    public void activityEnded(ActivityHandle handle) {
        if (handle == null) return;
        String handleId = handle.getId();
        ActivityContextInterface aci = activeHandles.remove(handleId);

        if (aci == null) {
            LOG.warn("activityEnded() called for unknown handle {} -- ignoring", handleId);
            return;
        }

        // Fire ActivityEndEvent so attached SBBs can do cleanup
        try {
            eventRouter.routeEvent(new ActivityEndedEvent(handle), aci);
        } catch (Exception e) {
            LOG.error("Error routing ActivityEndEvent for handle {}: {}",
                    handleId, e.getMessage(), e);
        }

        // Detach all SBBs from the ACI
        if (aci instanceof DetachableAci detachable) {
            detachable.detachAll();
        }

        // Remove from ACNF
        if (acnf != null) {
            acnf.unbind(aci.getActivityContextName());
        }

        LOG.debug("Activity ended: handle={}", handleId);
    }

    // ──────────────────────────────────────────────────────────
    // Event firing
    // ──────────────────────────────────────────────────────────

    @Override
    public void fireEvent(ActivityHandle handle,
                          Object event,
                          Address address,
                          FireableEventType eventType)
            throws UnrecognizedActivityException,
                   FiredUnrecognizedEventException,
                   IllegalStateException {
        if (handle == null) throw new IllegalArgumentException("handle is required");

        // 1. State check
        stateMachine.assertCanFireEvent();

        // 2. Handle must be active
        String handleId = handle.getId();
        ActivityContextInterface aci = activeHandles.get(handleId);
        if (aci == null) {
            throw new UnrecognizedActivityException(
                    "ActivityHandle not active: " + handleId +
                    " -- did you call activityStarted() first?");
        }

        // 3. Event type must be declared (when caller provided a fet)
        if (eventType != null) {
            EventType et = eventType.getEventType();
            if (et != null) {
                String eventTypeName = et.getName();
                if (!declaredEventTypeNames.isEmpty()
                        && !declaredEventTypeNames.contains(eventTypeName)) {
                    throw new FiredUnrecognizedEventException(
                            "Event type not declared by this RA: " + eventTypeName);
                }
            }
        }

        // 4. Null event check
        if (event == null) {
            throw new IllegalArgumentException("Event object cannot be null");
        }

        // 5. Route through EventRouter (respects LMAX Disruptor queue)
        LOG.debug("RA firing: handle={} event={}", handleId,
                event.getClass().getSimpleName());
        eventRouter.routeEvent(event, aci);
    }

    // ──────────────────────────────────────────────────────────
    // Stop complete (called by RA when drained)
    // ──────────────────────────────────────────────────────────

    @Override
    public void stopComplete() {
        stateMachine.stopComplete();
    }

    // ──────────────────────────────────────────────────────────
    // Internal helpers / metrics
    // ──────────────────────────────────────────────────────────

    /** Count active handles — for health/metrics endpoint. */
    public int activeHandleCount() {
        return activeHandles.size();
    }

    public boolean isHandleActive(String handleId) {
        return activeHandles.containsKey(handleId);
    }

    public ActivityContextInterface lookupAci(String handleId) {
        return activeHandles.get(handleId);
    }

    /** Marker interface for ACI implementations that support bulk detach. */
    public interface DetachableAci {
        void detachAll();
    }
}
