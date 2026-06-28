/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.tck;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ProfileTable;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.SbbLocalObject;
import com.microjainslee.api.ServiceID;

import java.util.Set;

/**
 * P1.3 — Skeleton adapter that exposes a micro-jainslee kernel through the
 * subset of the JAIN SLEE 1.1 (JSR-240) {@code SleeContainer} SPI used by
 * the TCK test suites.
 *
 * <p><strong>This is a skeleton — every method delegates to a TODO body.</strong>
 * The P1.5 / P1.6 sprints will fill in the bodies by adapting each SPI
 * shape onto the corresponding {@code MicroSleeContainer} facility.
 * What exists today is:
 * <ul>
 *   <li>The {@code MicrojainsleeContainerAdapter} SPI surface — 15 method
 *       signatures mirroring the JSR-240 contract.</li>
 *   <li>The constructor takes a {@code MicroSleeContainer} (or any
 *       implementation of the kernel interface) so tests can wire a
 *       live container into the harness.</li>
 *   <li>Each method is documented with its JSR-240 contract plus a
 *       {@code TODO} marker pointing at the micro-jainslee facility that
 *       will satisfy it.</li>
 * </ul>
 *
 * <p>The skeleton intentionally throws
 * {@link UnsupportedOperationException} from every method body so any
 * accidental TCK test that runs against an unimplemented method fails
 * fast and visibly (rather than silently passing).
 */
public class MicrojainsleeContainerAdapter {

    /**
     * The underlying micro-jainslee kernel instance the adapter wraps.
     * Held by reference; never null (enforced by the constructor).
     */
    private final Object microSleeContainer;

    /**
     * Create a new adapter wrapping the supplied micro-jainslee kernel.
     *
     * @param microSleeContainer a live {@code MicroSleeContainer} instance.
     *                           Typed as {@link Object} because the kernel
     *                           class lives in {@code jainslee-core} which
     *                           is a {@code provided}-scope dependency
     *                           (so we don't introduce a compile-time
     *                           edge into the adapter).
     */
    public MicrojainsleeContainerAdapter(Object microSleeContainer) {
        if (microSleeContainer == null) {
            throw new IllegalArgumentException(
                    "microSleeContainer is required (got null)");
        }
        this.microSleeContainer = microSleeContainer;
    }

    /**
     * Access the wrapped micro-jainslee kernel. Exposed for the
     * {@link TckRunner} and for advanced TCK tests that need to reach
     * past the SPI surface (e.g. for fixture setup).
     *
     * @return the kernel instance the adapter wraps (never null).
     */
    public Object getMicroSleeContainer() {
        return microSleeContainer;
    }

    // --------------------------------------------------------------
    //  JSR-240 SleeContainer SPI surface — 15 methods.
    //  Each method below is a skeleton. P1.5/P1.6 will fill the bodies.
    // --------------------------------------------------------------

    /**
     * JSR-240 §13.2.1 — look up a Profile Table by its string name.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getProfileFacility().getProfileTable(String)}.
     */
    public ProfileTable getProfileTable(String profileTableName) {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getProfileTable(String) — delegate to ProfileFacility");
    }

    /**
     * JSR-240 §13.2.2 — look up a Profile Table by its {@code ProfileTableID}.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getProfileFacility().getProfileTable(ProfileTableID)}.
     */
    public ProfileTable getProfileTableById(Object profileTableId) {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getProfileTableById — delegate to ProfileFacility");
    }

    /**
     * JSR-240 §13.2.3 — enumerate all Profile Tables known to the container.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getProfileFacility().getProfileTables()}.
     */
    public Set<String> getProfileTables() {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getProfileTables() — enumerate ProfileFacility tables");
    }

    /**
     * JSR-240 §13.3.1 — look up SBB root SBB entities by their SBB ID.
     *
     * <p>TODO (P1.6): delegate to the kernel's SBB entity index. There is
     * no direct equivalent in micro-jainslee yet — the SBB entity pool is
     * the closest concept.
     */
    public Set<? extends SbbLocalObject> getSbbEntities(Object sbbId) {
        throw new UnsupportedOperationException(
                "TODO(P1.6): getSbbEntities(SbbID) — delegate to SbbEntityPool");
    }

    /**
     * JSR-240 §13.3.2 — look up all SBB root entities bound to a service.
     *
     * <p>TODO (P1.6): iterate the kernel's service registry.
     */
    public Set<? extends SbbLocalObject> getSbbEntitiesForService(ServiceID serviceId) {
        throw new UnsupportedOperationException(
                "TODO(P1.6): getSbbEntitiesForService(ServiceID)");
    }

    /**
     * JSR-240 §13.4.1 — look up an installed Service by its {@code ServiceID}.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getServiceRegistry().getService(ServiceID)}.
     */
    public Object getService(ServiceID serviceId) {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getService(ServiceID) — delegate to ServiceRegistry");
    }

    /**
     * JSR-240 §13.4.2 — enumerate all installed Services.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getServiceRegistry().getServices()}.
     */
    public Set<ServiceID> getServices() {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getServices() — enumerate ServiceRegistry");
    }

    /**
     * JSR-240 §13.5.1 — look up Resource Adaptor entities by RA id.
     *
     * <p>TODO (P1.5): iterate the kernel's RA registry.
     */
    public Set<? extends ResourceAdaptor> getResourceAdaptorEntities(Object raId) {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getResourceAdaptorEntities(ResourceAdaptorID)");
    }

    /**
     * JSR-240 §13.5.2 — look up a Resource Adaptor entity by entity name.
     *
     * <p>TODO (P1.5): delegate to the kernel's RA registry index.
     */
    public ResourceAdaptor getResourceAdaptorEntity(String entityName) {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getResourceAdaptorEntity(String)");
    }

    /**
     * JSR-240 §13.6 — resolve an Activity Context Interface from its
     * opaque handle (the JAIN SLEE 1.1 ACI lookup is handle-based, not
     * name-based).
     *
     * <p>TODO (P1.6): delegate to
     * {@code microSleeContainer.getActivityContextFactory().getActivityContext(handle)}.
     */
    public ActivityContextInterface getActivityContext(Object activityContextHandle) {
        throw new UnsupportedOperationException(
                "TODO(P1.6): getActivityContext(ActivityContextHandle)");
    }

    /**
     * JSR-240 §13.7 — return the container's Trace Manager.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getTraceFacility()}.
     */
    public Object getTraceManager() {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getTraceManager() — delegate to TraceFacility");
    }

    /**
     * JSR-240 §13.8 — return the container's Alarm Facility.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getAlarmFacility()}.
     */
    public Object getAlarmFacility() {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getAlarmFacility() — delegate to AlarmFacility");
    }

    /**
     * JSR-240 §13.9 — return the container's Timer Facility.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getTimerFacility()}.
     */
    public Object getTimerFacility() {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getTimerFacility() — delegate to TimerFacility");
    }

    /**
     * JSR-240 §13.10 — return the container's Profile Facility.
     *
     * <p>TODO (P1.5): delegate to
     * {@code microSleeContainer.getProfileFacility()}.
     */
    public Object getProfileFacility() {
        throw new UnsupportedOperationException(
                "TODO(P1.5): getProfileFacility() — already exists in microSleeContainer");
    }

    /**
     * JSR-240 §13.11 — return the container's JNDI-style namespace root
     * (where Services, Profile Tables, etc. are bound).
     *
     * <p>TODO (P1.6): delegate to
     * {@code microSleeContainer.getNamingPort()}.
     */
    public Object getNamespace() {
        throw new UnsupportedOperationException(
                "TODO(P1.6): getNamespace() — delegate to NamingPort");
    }

    // Total: 15 SPI method skeletons. The P1.5/P1.6 sprints will replace
    // each TODO body with a real implementation that adapts the JSR-240
    // contract onto the corresponding micro-jainslee facility.
}
