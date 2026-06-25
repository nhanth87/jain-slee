/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 */

package org.mobicents.slee.runtime.facilities;

import java.util.concurrent.Executor;

import org.mobicents.slee.container.eventrouter.EventRouterExecutor;

/**
 * Optional Phase-2 adapter sketch for wiring {@link TimerFacilityImpl} to a
 * jSS7 {@code TimerScheduler} backend without calling SBB code on the timer
 * thread.
 *
 * <p>Production path remains {@link TimerFacilityImpl} +
 * {@code FaultTolerantScheduler}. Enable this bridge only behind a feature
 * flag after TCK validation.</p>
 *
 * <p>Micro-jainslee reference implementation:
 * {@code com.microjainslee.core.SleeTimerSchedulerBridge}.</p>
 *
 * <pre>
 * // Sketch — not wired by default:
 * TimerFacilityBackendBridge backendBridge =
 *     new TimerFacilityBackendBridge(eventRouterExecutor);
 * timerBackend.schedule(record, delay, r ->
 *     backendBridge.dispatchTimerFire(() -> ac.fireEvent(TIMER_EVENT, ...)));
 * </pre>
 *
 * <p>Preserve JTA semantics by deferring {@code schedule} until transaction
 * commit, mirroring {@code SetTimerAfterTxCommitRunnable} in
 * {@link TimerFacilityImpl}.</p>
 */
public final class TimerFacilityBackendBridge {

    private final Executor timerFireExecutor;

    public TimerFacilityBackendBridge(EventRouterExecutor timerFireExecutor) {
        this.timerFireExecutor = timerFireExecutor;
    }

    public TimerFacilityBackendBridge(Executor timerFireExecutor) {
        this.timerFireExecutor = timerFireExecutor;
    }

    /**
     * Posts a timer fire to the Event Router executor. Never invoke SBB or
     * {@code ActivityContext.fireEvent} on the jSS7 hashed-wheel thread.
     */
    public void dispatchTimerFire(Runnable fireTask) {
        timerFireExecutor.execute(fireTask);
    }
}
