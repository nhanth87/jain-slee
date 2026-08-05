/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.http2.resilience;

import com.microjainslee.ra.sbi.http2.command.SbiOutboundCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RA-owned saga skeleton keyed by {@code 3gpp-Sbi-Correlation-Info} / sagaId.
 * Steps register compensate commands; on fail the coordinator runs them reverse-order.
 */
public final class SbiSagaCoordinator {

    public enum State { RUNNING, COMPLETED, COMPENSATING, FAILED }

    public static final class Saga {
        public final String id;
        public final String correlationInfo;
        public final long startedAtMs = System.currentTimeMillis();
        public volatile State state = State.RUNNING;
        public final List<SbiOutboundCommand> compensates = new ArrayList<>();
        public final List<String> completedSteps = new ArrayList<>();

        Saga(String id, String correlationInfo) {
            this.id = id;
            this.correlationInfo = correlationInfo;
        }
    }

    private final ConcurrentHashMap<String, Saga> sagas = new ConcurrentHashMap<>();

    public Saga begin(String correlationInfo) {
        String id = UUID.randomUUID().toString();
        Saga s = new Saga(id, correlationInfo);
        sagas.put(id, s);
        return s;
    }

    public Optional<Saga> get(String sagaId) {
        return Optional.ofNullable(sagas.get(sagaId));
    }

    public void registerCompensate(String sagaId, SbiOutboundCommand compensate) {
        Saga s = sagas.get(sagaId);
        if (s == null || compensate == null) {
            return;
        }
        synchronized (s) {
            s.compensates.add(compensate);
        }
    }

    public void markStepDone(String sagaId, String stepId) {
        Saga s = sagas.get(sagaId);
        if (s == null) {
            return;
        }
        synchronized (s) {
            s.completedSteps.add(stepId == null ? "?" : stepId);
        }
    }

    public void complete(String sagaId) {
        Saga s = sagas.get(sagaId);
        if (s != null) {
            s.state = State.COMPLETED;
        }
    }

    /** Returns compensate commands in reverse registration order. */
    public List<SbiOutboundCommand> failAndCompensate(String sagaId) {
        Saga s = sagas.get(sagaId);
        if (s == null) {
            return List.of();
        }
        synchronized (s) {
            s.state = State.COMPENSATING;
            List<SbiOutboundCommand> rev = new ArrayList<>(s.compensates);
            java.util.Collections.reverse(rev);
            s.state = State.FAILED;
            return List.copyOf(rev);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new ConcurrentHashMap<>();
        sagas.forEach((id, s) -> out.put(id, Map.of(
                "state", s.state.name(),
                "correlationInfo", s.correlationInfo == null ? "" : s.correlationInfo,
                "steps", s.completedSteps.size(),
                "compensates", s.compensates.size(),
                "startedAtMs", s.startedAtMs)));
        return out;
    }
}
