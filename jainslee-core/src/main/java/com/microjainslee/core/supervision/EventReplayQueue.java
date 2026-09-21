package com.microjainslee.core.supervision;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.SleeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production P3 (M3) — bounded, per-entity replay buffer for events that
 * arrive while a supervised restart is in flight.
 *
 * <p>Without it, an event routed to an entity between the failure booking
 * and the fresh-instance recreation is either queued behind a wedged slot
 * thread (lost when the restart abandons the slot) or delivered to an
 * instance that is about to be discarded. The router parks such events
 * here (single-SBB activity contexts only) and the supervisor drains the
 * buffer right after a successful restart, re-routing every parked event
 * through the normal dispatch path.
 *
 * <p>Thread-safety: {@code park} is called from the router's dispatch
 * thread, {@code drain} / {@code abandon} from the supervisor thread —
 * each per-entity deque is guarded by its own monitor, so contention is
 * bounded to the restarting entity.
 *
 * @author Tran Nhan (nhanth87)
 */
final class EventReplayQueue {

    /** Default per-entity bound used by the 4-argument supervisor ctor. */
    static final int DEFAULT_PER_ENTITY_CAPACITY = 256;

    /** One parked event plus the activity context it was routed to. */
    record ParkedEvent(SleeEvent event, ActivityContextInterface aci) {
    }

    private final ConcurrentHashMap<String, Bounded> perEntity =
            new ConcurrentHashMap<String, Bounded>();
    private final int perEntityCapacity;

    private final AtomicLong parkedTotal = new AtomicLong();
    private final AtomicLong replayedTotal = new AtomicLong();
    private final AtomicLong droppedTotal = new AtomicLong();

    EventReplayQueue(int perEntityCapacity) {
        this.perEntityCapacity = perEntityCapacity;
    }

    /**
     * Park {@code event} for {@code sbbId}. Returns {@code false} (and
     * counts a drop) when the buffer is disabled ({@code capacity <= 0}),
     * {@code sbbId} / {@code event} is {@code null}, or the per-entity
     * bound is exhausted.
     */
    boolean park(String sbbId, SleeEvent event, ActivityContextInterface aci) {
        if (perEntityCapacity <= 0 || sbbId == null || event == null) {
            return false;
        }
        Bounded queue = perEntity.computeIfAbsent(sbbId,
                id -> new Bounded(perEntityCapacity));
        boolean accepted;
        synchronized (queue) {
            accepted = queue.events.size() < queue.capacity;
            if (accepted) {
                queue.events.addLast(new ParkedEvent(event, aci));
            }
        }
        if (accepted) {
            parkedTotal.incrementAndGet();
        } else {
            droppedTotal.incrementAndGet();
        }
        return accepted;
    }

    /**
     * Atomically remove and return every event parked for {@code sbbId}
     * (FIFO order). The returned events count as replayed.
     */
    List<ParkedEvent> drain(String sbbId) {
        Bounded queue = sbbId == null ? null : perEntity.remove(sbbId);
        if (queue == null) {
            return List.of();
        }
        List<ParkedEvent> drained;
        synchronized (queue) {
            drained = new ArrayList<ParkedEvent>(queue.events);
            queue.events.clear();
        }
        replayedTotal.addAndGet(drained.size());
        return drained;
    }

    /**
     * Drop every event parked for {@code sbbId} (entity DEAD or restart
     * refused / failed). Returns how many were dropped.
     */
    int abandon(String sbbId) {
        Bounded queue = sbbId == null ? null : perEntity.remove(sbbId);
        if (queue == null) {
            return 0;
        }
        int dropped;
        synchronized (queue) {
            dropped = queue.events.size();
            queue.events.clear();
        }
        if (dropped > 0) {
            droppedTotal.addAndGet(dropped);
        }
        return dropped;
    }

    /** Drop everything without touching the metrics (container stop). */
    void clear() {
        perEntity.clear();
    }

    long parkedTotal() {
        return parkedTotal.get();
    }

    long replayedTotal() {
        return replayedTotal.get();
    }

    long droppedTotal() {
        return droppedTotal.get();
    }

    /** Bounded FIFO parking lot for a single entity id. */
    private static final class Bounded {
        private final ArrayDeque<ParkedEvent> events;
        private final int capacity;

        Bounded(int capacity) {
            this.capacity = capacity;
            this.events = new ArrayDeque<ParkedEvent>(Math.max(capacity, 1));
        }
    }
}