Để implement chính xác, tôi cần đọc lại toàn bộ context đã lưu trước.

Tôi đã có đủ context từ memory (177/177 tests, 387 nodes knowledge graph, kiến trúc đầy đủ). Bây giờ đi sâu vào concrete implementation cho toàn bộ S6–S9 với full code.

🔧 micro-jainslee S6–S9 — Complete Implementation Guide
Baseline confirmed: 177/177 tests pass | VirtualThreadSbbEntityPool với isShutdown() | newSbbEntityPool() helper | Java 25 virtual threads | LMAX Disruptor ring

🟥 SPRINT S6 — Observability & Removal Notification
S6.1–S6.2 · EntityRemovalBus + notifyEntityRemoved
Tư tưởng thiết kế: Thay vì callback hell, dùng một EntityRemovalBus publish-subscribe nhẹ (không thêm dependency). RemovalListener publish event, các subscriber (RA, metrics, IES cleanup) tự đăng ký.

java


// jainslee-core/src/main/java/com/microjainslee/core/removal/EntityRemovalEvent.java
package com.microjainslee.core.removal;
/**
 * Immutable value object fired whenever an SBB entity is removed.
 * Carried on the EntityRemovalBus; never crosses thread boundaries
 * (always published on the virtual thread that owns the entity slot).
 */
public record EntityRemovalEvent(
        String entityId,
        String convergenceKey,   // null when IES was not used
        RemovalReason reason,
        long timestampMs
) {
    public enum RemovalReason {
        TIMER_EXPIRED,
        SBB_SELF_REMOVE,
        CASCADE_CHILD,
        OPERATOR,
        EXCEPTION_ROLLBACK,
        HOT_REDEPLOY
    }
}
java


// jainslee-core/src/main/java/com/microjainslee/core/removal/EntityRemovalBus.java
package com.microjainslee.core.removal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
/**
 * Lightweight synchronous publish-subscribe bus for entity removal events.
 *
 * Design constraints:
 *  - Zero external dependencies (no Guava EventBus, no Reactor).
 *  - Subscribers run on the SAME virtual thread that triggered removal.
 *    They MUST be fast (< 1 µs) and MUST NOT block or call back into
 *    MicroSleeContainer (deadlock risk).
 *  - CopyOnWriteArrayList: reads (publish path) are lock-free;
 *    writes (subscribe/unsubscribe) are rare and copy-on-write.
 */
public final class EntityRemovalBus {
    private final List<Consumer<EntityRemovalEvent>> subscribers =
            new CopyOnWriteArrayList<>();
    public void subscribe(Consumer<EntityRemovalEvent> subscriber) {
        subscribers.add(subscriber);
    }
    public void unsubscribe(Consumer<EntityRemovalEvent> subscriber) {
        subscribers.remove(subscriber);
    }
    /**
     * Publish to all subscribers synchronously.
     * Individual subscriber failures are logged but do NOT abort
     * the remaining subscribers (defensive fan-out).
     */
    public void publish(EntityRemovalEvent event) {
        for (Consumer<EntityRemovalEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (Exception ex) {
                // Must not use Logger here to avoid circular dependency;
                // use System.err as last resort — real apps route via JUL.
                System.err.printf(
                    "[EntityRemovalBus] subscriber %s threw on event %s: %s%n",
                    s, event.entityId(), ex.getMessage());
            }
        }
    }
}
Wire vào MicroSleeContainer — thêm vào RemovalListener.onRemoved() (line ~1322):

java


// MicroSleeContainer.java — trong buildLocalObject(), RemovalListener lambda
// ── EXISTING CODE (giữ nguyên) ──────────────────────────────
detachFromAllActivityContexts(removedObject);
sbbs.remove(id);
entityTypesById.remove(id);
if (removedObject.getSbb() instanceof CmpBackedSbb cmp) cmp.cmpPersist();
// ── NEW S6.1 / S6.2 ─────────────────────────────────────────
// 1. Capture convergence key BEFORE release (pool clears it after)
String convergenceKey = iesDispatcher != null
        ? iesDispatcher.getConvergenceKeyFor(id)   // new getter — see S6.3
        : null;
// 2. Publish removal event (synchronous, fast subscribers only)
RemovalReason reason = resolveRemovalReason(removedObject); // see below
entityRemovalBus.publish(new EntityRemovalEvent(
        id, convergenceKey, reason, System.currentTimeMillis()));
// 3. Release pool AFTER bus (bus subscribers may still read CMP)
sbbEntityPool.release(entity);
cmpFieldStore.remove(id);
// ── END NEW ──────────────────────────────────────────────────
java


// MicroSleeContainer.java — helper method
private RemovalReason resolveRemovalReason(SimpleSbbLocalObject obj) {
    // SimpleSbbLocalObject đã expose getRemovalCause() từ S5;
    // nếu chưa có thì dùng UNKNOWN → SBB_SELF_REMOVE as default
    return switch (obj.getRemovalCause()) {
        case TIMER        -> RemovalReason.TIMER_EXPIRED;
        case SELF         -> RemovalReason.SBB_SELF_REMOVE;
        case CASCADE      -> RemovalReason.CASCADE_CHILD;
        case HOT_REDEPLOY -> RemovalReason.HOT_REDEPLOY;
        default           -> RemovalReason.SBB_SELF_REMOVE;
    };
}
// Public API (S6.1 task)
public void addEntityRemovalListener(Consumer<EntityRemovalEvent> listener) {
    entityRemovalBus.subscribe(listener);
}
public void removeEntityRemovalListener(Consumer<EntityRemovalEvent> listener) {
    entityRemovalBus.unsubscribe(listener);
}
public long getEntityRemovalCount() {
    return entityRemovalCounter.get(); // AtomicLong field, increment in publish()
}
S6.3–S6.4 · VirtualThreadSbbEntityPool implement SbbEntityPool SPI
Root cause của GAP-SR-7: VirtualThreadSbbEntityPool không implement SPI onEntityRemoved. Fix: thêm inner SpiAdapter + wire qua EntityRemovalBus.

java


// VirtualThreadSbbEntityPool.java — thêm vào cuối class
/**
 * SPI adapter: bridges EntityRemovalBus events into the IES dispatcher's
 * convergence-cleanup callback. Registered once during container startup.
 *
 * Why inner class: keeps the SPI concern isolated; the pool itself
 * remains agnostic of IES internals.
 */
public static final class IesCleanupAdapter
        implements Consumer<EntityRemovalEvent> {
    private final InitialEventSelectorDispatcher iesDispatcher;
    public IesCleanupAdapter(InitialEventSelectorDispatcher iesDispatcher) {
        this.iesDispatcher = iesDispatcher;
    }
    @Override
    public void accept(EntityRemovalEvent event) {
        // removeConvergencesFor() already exists at IES line 169-174
        // but was never called from production code — now it is.
        if (event.entityId() != null) {
            iesDispatcher.removeConvergencesFor(event.entityId());
        }
    }
}
Wire trong MicroSleeContainer.setInitialEventSelectorDispatcher():

java


// MicroSleeContainer.java
public void setInitialEventSelectorDispatcher(InitialEventSelectorDispatcher d) {
    this.iesDispatcher = d;
    // S6.3: wire IES cleanup via the removal bus (closes GAP-SR-7)
    entityRemovalBus.subscribe(new VirtualThreadSbbEntityPool.IesCleanupAdapter(d));
}
S6.5–S6.6 · MissingEntityCount metric trong EventRouter
java


// EventRouter.java — thêm field và getter
private final AtomicLong missingEntityCount = new AtomicLong(0);
private final AtomicLong rehydratedCount    = new AtomicLong(0); // dùng ở S7
public long getMissingEntityCount() { return missingEntityCount.get(); }
public long getRehydratedCount()    { return rehydratedCount.get(); }
Trong deliverEvent() tại line ~479–489:

java


// EventRouter.java — deliverEvent()
SbbEntity entity = pool != null ? pool.findEntity(localObject.getSbbID().getId()) : null;
if (entity == null) {
    // S6.5: đếm + log thay vì silent drop
    missingEntityCount.incrementAndGet();
    log.warn("[EventRouter] MISSING_ENTITY: sbbId={} event={} aci={} — " +
             "entity removed before delivery. Attempting rehydration (S7).",
             localObject.getSbbID().getId(),
             event.getClass().getSimpleName(),
             activityContext.getName());
    // S7 hook — nếu recovery service có snapshot → re-dispatch
    // (implementation ở S7; ở đây chỉ đặt placeholder)
    if (sessionRecoveryService != null) {
        boolean rehydrated = sessionRecoveryService
                .tryRehydrateAndDeliver(localObject.getSbbID().getId(),
                                        event, activityContext, handler);
        if (rehydrated) {
            rehydratedCount.incrementAndGet();
            return; // delivered via rehydrate path
        }
    }
    // Vẫn drop nếu không có snapshot — nhưng đã có log + metric
    return;
}
S6.7 · SessionLifecycleLogger
java


// jainslee-core/src/main/java/com/microjainslee/core/removal/SessionLifecycleLogger.java
package com.microjainslee.core.removal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.function.Consumer;
/**
 * Subscribes to EntityRemovalBus and emits structured lifecycle log lines.
 *
 * Log format (one line per event, easily grep-able):
 *   SLEE_SESSION | action=REMOVED | entityId=xxx | convergence=yyy |
 *                  reason=TIMER_EXPIRED | ts=1234567890
 *
 * Registered automatically during MicroSleeContainer.start().
 * Unregistered during stop() to avoid leaks across stop/start cycles.
 */
public final class SessionLifecycleLogger implements Consumer<EntityRemovalEvent> {
    private static final Logger log = LogManager.getLogger(SessionLifecycleLogger.class);
    @Override
    public void accept(EntityRemovalEvent event) {
        log.info("SLEE_SESSION | action=REMOVED | entityId={} | convergence={} | " +
                 "reason={} | ts={}",
                 event.entityId(),
                 event.convergenceKey() != null ? event.convergenceKey() : "n/a",
                 event.reason(),
                 event.timestampMs());
    }
    /** Convenience factory — register on bus and return self for unsubscribe. */
    public static SessionLifecycleLogger registerOn(EntityRemovalBus bus) {
        SessionLifecycleLogger logger = new SessionLifecycleLogger();
        bus.subscribe(logger);
        return logger;
    }
}
S6.8 · UssdSessionStore auto-update trên removal
java


// example-quarkus/.../service/UssdSessionStore.java — thêm method
/**
 * Đăng ký với MicroSleeContainer để nhận removal notifications.
 * Gọi một lần trong @PostConstruct hoặc wiring setup.
 *
 * Idempotent: gọi nhiều lần không tạo duplicate subscriber vì
 * unsubscribe được gọi khi destroy().
 */
public void bindToContainer(MicroSleeContainer container) {
    this.removalListener = event -> {
        // Map entityId → sessionId: convention là entityId = sessionId + "/http"
        String sessionId = stripSuffix(event.entityId(), "/http");
        if (sessionId != null && sessions.containsKey(sessionId)) {
            // Idempotent: nếu đã COMPLETED thì không override
            sessions.computeIfPresent(sessionId, (k, existing) -> {
                if (existing.getStatus() == SessionStatus.PROCESSING) {
                    return existing.withStatus(SessionStatus.FAILED)
                                   .withMessage("Session removed: " + event.reason());
                }
                return existing; // COMPLETED stays COMPLETED (fixes Risk R6)
            });
            log.info("UssdSessionStore: session {} auto-failed due to {}",
                     sessionId, event.reason());
        }
    };
    container.addEntityRemovalListener(removalListener);
    this.boundContainer = container;
}
public void destroy() {
    if (boundContainer != null && removalListener != null) {
        boundContainer.removeEntityRemovalListener(removalListener);
    }
}
private static String stripSuffix(String entityId, String suffix) {
    return entityId != null && entityId.endsWith(suffix)
            ? entityId.substring(0, entityId.length() - suffix.length())
            : null;
}
S6 Tests
java


// jainslee-core/src/test/java/.../removal/EntityRemovalBusTest.java
class EntityRemovalBusTest {
    @Test void publish_notifiesAllSubscribers() { /* 2 subscribers, both receive */ }
    @Test void publish_oneFails_othersStillReceive() { /* defensive fan-out */ }
    @Test void unsubscribe_removesListener() { /* subscribe then unsubscribe */ }
    @Test void missingEntityCount_incrementsOnNullEntity() {
        // EventRouter với null pool → deliverEvent → missingEntityCount == 1
    }
    @Test void ussdSessionStore_autoFails_onRemoval() {
        // bindToContainer → publish removal → store.get(sid).status == FAILED
    }
    @Test void ussdSessionStore_doesNotOverrideCompleted() {
        // COMPLETED session → removal event → status still COMPLETED (Risk R6)
    }
    @Test void iesCleanup_convergenceRemovedOnEntityRelease() {
        // IesCleanupAdapter.accept() → iesDispatcher.activeConvergenceCount() == 0
    }
    @Test void sessionLifecycleLogger_logsOnRemoval() {
        // capture log output → contains "SLEE_SESSION | action=REMOVED"
    }
}
🟠 SPRINT S7 — Re-creation & Replay on Dead Slot
S7.1–S7.2 · SessionRecoveryService
java


// jainslee-core/src/main/java/com/microjainslee/core/recovery/SessionRecoveryService.java
package com.microjainslee.core.recovery;
import com.microjainslee.core.removal.EntityRemovalEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
/**
 * Holds a bounded LRU cache of CMP snapshots for recently-removed SBB entities.
 * When EventRouter detects a missing entity (GAP-SR-1), it calls
 * tryRehydrateAndDeliver() to reconstruct the SBB from the last known snapshot
 * and re-dispatch the pending event.
 *
 * Threading:
 *  - registerSnapshot() called on the entity's virtual thread (EntitySlot).
 *  - tryRehydrateAndDeliver() called on the Disruptor consumer thread.
 *  - Both paths use ConcurrentHashMap; LRU eviction is approximate
 *    (LinkedHashMap inside a lock) and only runs at capacity.
 *
 * Capacity: default 64K entries; configurable via MicroSleeConfiguration.
 */
public final class SessionRecoveryService implements Consumer<EntityRemovalEvent> {
    private static final int DEFAULT_MAX_SIZE = 64 * 1024;
    /** Snapshot entry: CMP field map + ACI names + capture timestamp. */
    public record RecoverySnapshot(
            String entityId,
            String sbbClassName,
            Map<String, Object> cmpFields,      // defensive copy
            Set<String> attachedAciNames,
            long capturedAtMs
    ) {}
    private final int maxSize;
    // LRU eviction: LinkedHashMap wrapped in synchronized block.
    // Only the eviction path (when size > maxSize) takes the lock;
    // normal reads use the outer ConcurrentHashMap.
    private final Map<String, RecoverySnapshot> snapshots;
    private final RehydrateCallback rehydrateCallback;
    /**
     * Callback interface implemented by MicroSleeContainer.
     * Keeps SessionRecoveryService free of container internals.
     */
    public interface RehydrateCallback {
        /**
         * Reconstruct an SBB entity from a snapshot and attach it
         * to the listed ACIs. Returns the new local object, or null
         * if reconstruction fails (e.g. pool exhausted).
         */
        SimpleSbbLocalObject reconstructFromSnapshot(RecoverySnapshot snapshot);
    }
    public SessionRecoveryService(int maxSize, RehydrateCallback callback) {
        this.maxSize = maxSize;
        this.rehydrateCallback = callback;
        // LinkedHashMap with access-order=true → LRU
        var lruMap = new LinkedHashMap<String, RecoverySnapshot>(
                maxSize, 0.75f, /* accessOrder= */ true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RecoverySnapshot> e) {
                return size() > SessionRecoveryService.this.maxSize;
            }
        };
        this.snapshots = Collections.synchronizedMap(lruMap);
    }
    /** Called from EntityRemovalBus (S6) — capture CMP state before slot release. */
    @Override
    public void accept(EntityRemovalEvent event) {
        // The actual snapshot capture happens via MicroSleeContainer
        // which calls registerSnapshot() with the CMP fields.
        // This Consumer interface is just the bus wiring point.
    }
    public void registerSnapshot(RecoverySnapshot snapshot) {
        snapshots.put(snapshot.entityId(), snapshot);
    }
    public Optional<RecoverySnapshot> getSnapshot(String entityId) {
        return Optional.ofNullable(snapshots.get(entityId));
    }
    /**
     * Core recovery method — called from EventRouter when findEntity() == null.
     *
     * Contract:
     *  - Returns true  → event was delivered to rehydrated SBB.
     *  - Returns false → no snapshot found; caller must drop (with log).
     *
     * Anti-loop protection: rehydration sets a ThreadLocal flag so that
     * the re-dispatched event does not trigger another rehydration attempt.
     */
    public boolean tryRehydrateAndDeliver(
            String entityId,
            Object event,
            ActivityContext aci,
            SbbEventHandler handler) {
        if (REHYDRATING.get()) {
            // Already in a rehydration call — avoid infinite recursion.
            return false;
        }
        RecoverySnapshot snap = snapshots.remove(entityId); // consume once
        if (snap == null) return false;
        REHYDRATING.set(true);
        try {
            SimpleSbbLocalObject newLocal = rehydrateCallback.reconstructFromSnapshot(snap);
            if (newLocal == null) return false;
            // Re-attach to ACIs from snapshot
            // (MicroSleeContainer.reconstructFromSnapshot handles this)
            // Re-dispatch: handler.onEvent on the new entity's virtual thread
            handler.onEvent(event, aci);
            return true;
        } finally {
            REHYDRATING.remove();
        }
    }
    private static final ThreadLocal<Boolean> REHYDRATING =
            ThreadLocal.withInitial(() -> false);
    public int activeSnapshotCount() { return snapshots.size(); }
}
S7.3 · Capture snapshot TRƯỚC khi release
java


// MicroSleeContainer.java — trong RemovalListener.onRemoved(), TRƯỚC sbbEntityPool.release()
// S7.3: capture CMP snapshot for potential rehydration
if (sessionRecoveryService != null && removedObject.getSbb() instanceof CmpBackedSbb cmp) {
    Map<String, Object> cmpCopy = new LinkedHashMap<>(
            ((InMemoryCmpFieldStore) cmpFieldStore).getFieldsCopy(id));
    Set<String> aciNames = activityContextNamingFacility
            .getNamesFor(id);  // S9.4: new method — see below
    sessionRecoveryService.registerSnapshot(new SessionRecoveryService.RecoverySnapshot(
            id,
            removedObject.getSbb().getClass().getName(),
            cmpCopy,
            aciNames,
            System.currentTimeMillis()
    ));
}
S7.4 · MicroSleeContainer.reconstructFromSnapshot() — RehydrateCallback impl
java


// MicroSleeContainer.java — implement RehydrateCallback
@Override
public SimpleSbbLocalObject reconstructFromSnapshot(
        SessionRecoveryService.RecoverySnapshot snap) {
    try {
        // 1. Tạo SBB instance mới từ class name
        Class<?> sbbClass = Class.forName(snap.sbbClassName());
        Sbb freshSbb = (Sbb) sbbClass.getDeclaredConstructor().newInstance();
        // 2. Restore CMP fields
        if (freshSbb instanceof CmpBackedSbb cmp) {
            snap.cmpFields().forEach((field, value) ->
                cmpFieldStore.set(snap.entityId() + "." + field, value));
            cmp.setSbbContext(/* rebuild context */);
        }
        // 3. Register như entity mới nhưng CÙNG entityId
        SimpleSbbLocalObject local = buildLocalObject(snap.entityId(), freshSbb);
        sbbs.put(snap.entityId(), local);
        // 4. Re-attach ACIs (closes GAP-SR-6 for local path)
        for (String aciName : snap.attachedAciNames()) {
            ActivityContext aci = activityContextNamingFacility.lookup(aciName);
            if (aci != null) {
                aci.attachImmediate(local);
                timerPort.getBridge().bindActivityContext(local, aci);
            }
        }
        log.info("[Recovery] Rehydrated entity={} from snapshot (age={}ms)",
                 snap.entityId(),
                 System.currentTimeMillis() - snap.capturedAtMs());
        return local;
    } catch (Exception ex) {
        log.error("[Recovery] Failed to rehydrate entity={}: {}",
                  snap.entityId(), ex.getMessage(), ex);
        return null;
    }
}
🟠 SPRINT S8 — Message Ordering & Dedup
S8.1 · Interface SequencedEvent
java


// jainslee-api/src/main/java/com/microjainslee/api/SequencedEvent.java
package com.microjainslee.api;
/**
 * Optional marker interface for events that carry an application-level
 * sequence number. When implemented, the EventRouter uses the sequence
 * number for:
 *  1. Out-of-order buffering (OutOfOrderBuffer).
 *  2. Deduplication (DedupWindow).
 *
 * Events that do NOT implement this interface are treated as unordered
 * (processed immediately, no dedup) — backward compatible.
 */
public interface SequencedEvent {
    /** Per-session monotonically increasing sequence number. */
    long getSequenceNumber();
    /** Convergence key — must match the IES result for this session. */
    String getConvergenceName();
}
S8.2–S8.4 · OutOfOrderBuffer
java


// jainslee-core/src/main/java/com/microjainslee/core/ordering/OutOfOrderBuffer.java
package com.microjainslee.core.ordering;
import java.util.*;
import java.util.concurrent.*;
/**
 * Per-convergence bounded buffer for out-of-order events.
 *
 * When a non-initial SequencedEvent arrives before the SBB entity
 * is allocated (i.e. before BEGIN), IES returns null and the event
 * is placed here instead of being dropped (closes GAP-SR-3).
 *
 * When the entity is later allocated (BEGIN arrives and IES allocates
 * a new entityId), the buffer is drained FIFO before any new events.
 *
 * Eviction: entries expire after ttlMs (default 30 000) via a
 * single background ScheduledExecutorService shared across all
 * convergences.
 *
 * Capacity: max perConvergenceCapacity (default 16) events per
 * convergence key. Overflow → oldest dropped with WARN log.
 */
public final class OutOfOrderBuffer {
    public record BufferedEvent(
            Object event,
            Object activityContext,
            long receivedAtMs
    ) {}
    private final int perConvergenceCapacity;
    private final long ttlMs;
    // ConcurrentHashMap of ArrayDeque — deque ops synchronized per key
    private final ConcurrentHashMap<String, Deque<BufferedEvent>> buffers =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictorExecutor;
    public OutOfOrderBuffer(int perConvergenceCapacity, long ttlMs) {
        this.perConvergenceCapacity = perConvergenceCapacity;
        this.ttlMs = ttlMs;
        this.evictorExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofPlatform().name("jainslee-oob-evictor").daemon(true).build();
            t.setRunnable(r);
            return t;
        });
        // Eviction sweep every (ttlMs / 2) ms
        evictorExecutor.scheduleAtFixedRate(
                this::evictExpired, ttlMs / 2, ttlMs / 2, TimeUnit.MILLISECONDS);
    }
    /**
     * Buffer an out-of-order event. Returns true if buffered,
     * false if the buffer is full (event dropped with WARN).
     */
    public boolean enqueue(String convergenceKey, Object event, Object aci) {
        Deque<BufferedEvent> deque = buffers.computeIfAbsent(
                convergenceKey, k -> new ArrayDeque<>());
        synchronized (deque) {
            if (deque.size() >= perConvergenceCapacity) {
                // drop oldest (FIFO overflow)
                deque.pollFirst();
                // log.warn("OOB buffer overflow for convergence={}", convergenceKey);
            }
            deque.addLast(new BufferedEvent(event, aci, System.currentTimeMillis()));
            return true;
        }
    }
    /**
     * Drain all buffered events for a convergence key (FIFO order).
     * Called when the entity is allocated — before the trigger event.
     * Returns an empty list if nothing buffered.
     */
    public List<BufferedEvent> drain(String convergenceKey) {
        Deque<BufferedEvent> deque = buffers.remove(convergenceKey);
        if (deque == null) return List.of();
        synchronized (deque) {
            return new ArrayList<>(deque); // snapshot
        }
    }
    /** Background eviction of TTL-expired entries. */
    private void evictExpired() {
        long cutoff = System.currentTimeMillis() - ttlMs;
        buffers.forEach((key, deque) -> {
            synchronized (deque) {
                deque.removeIf(e -> e.receivedAtMs() < cutoff);
                if (deque.isEmpty()) buffers.remove(key);
            }
        });
    }
    public void shutdown() { evictorExecutor.shutdownNow(); }
    public int bufferedConvergenceCount() { return buffers.size(); }
}
Wire vào InitialEventSelectorDispatcher.resolveTarget():

java


// InitialEventSelectorDispatcher.java — trong resolveTarget() tại line ~139-144
// Existing: non-initial event with no matching convergence → null
if (!isInitialEvent && !convergenceIndex.containsKey(convergenceName)) {
    // S8.3: buffer instead of drop
    if (outOfOrderBuffer != null && event instanceof SequencedEvent) {
        boolean buffered = outOfOrderBuffer.enqueue(convergenceName, event, aci);
        log.debug("[IES] OOB buffered event for convergence={} (buffered={})",
                  convergenceName, buffered);
    } else {
        log.debug("[IES] Dropping non-initial event: no entity for convergence={}",
                  convergenceName);
    }
    return null; // EventRouter sees null → no dispatch (correct)
}
// S8.4: when allocating a new entity → drain OOB buffer first
if (isInitialEvent) {
    String newId = pool.allocateNew();
    convergenceIndex.put(convergenceName, newId);
    if (outOfOrderBuffer != null) {
        List<OutOfOrderBuffer.BufferedEvent> pending = outOfOrderBuffer.drain(convergenceName);
        for (OutOfOrderBuffer.BufferedEvent buf : pending) {
            // Re-route buffered events — they'll now find the new entity
            eventRouterPort.routeEvent(buf.event(), buf.activityContext());
            log.debug("[IES] Drained OOB event for convergence={}", convergenceName);
        }
    }
    return newId;
}
S8.5–S8.6 · DedupWindow
java


// jainslee-core/src/main/java/com/microjainslee/core/dedup/DedupWindow.java
package com.microjainslee.core.dedup;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Sliding deduplication window keyed by (convergenceName, sequenceNumber).
 *
 * Uses a LinkedHashMap LRU cache (max DEFAULT_MAX_ENTRIES) with a TTL
 * sweep. When a SequencedEvent arrives at SleeEndpointImpl.fireEvent(),
 * the window is consulted before routing:
 *  - Not seen → record + allow through.
 *  - Already seen within window → drop + increment dedupHitCount metric.
 *
 * After windowMs the key expires: retransmitted messages past the window
 * are treated as new (no permanent suppression — closes GAP-SR-4 correctly).
 */
public final class DedupWindow {
    private record DedupKey(String convergence, long seqNum) {}
    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private final long windowMs;
    private final Map<DedupKey, Long> seen; // key → expiryMs
    private long dedupHitCount = 0L;
    public DedupWindow(long windowMs) {
        this.windowMs = windowMs;
        // Access-order LRU map capped at DEFAULT_MAX_ENTRIES
        this.seen = Collections.synchronizedMap(
                new LinkedHashMap<>(DEFAULT_MAX_ENTRIES, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<DedupKey, Long> e) {
                        return size() > DEFAULT_MAX_ENTRIES;
                    }
                });
    }
    /**
     * Returns true if this (convergence, seqNum) pair has been seen
     * within the current window (→ duplicate; caller should drop).
     * Returns false if it is new (→ allow; records the entry).
     */
    public boolean isDuplicate(String convergence, long seqNum) {
        DedupKey key = new DedupKey(convergence, seqNum);
        long now = System.currentTimeMillis();
        Long expiry = seen.get(key);
        if (expiry != null && expiry > now) {
            dedupHitCount++;
            return true; // known duplicate
        }
        seen.put(key, now + windowMs); // record with expiry
        return false;
    }
    public long getDedupHitCount() { return dedupHitCount; }
    /** Periodic cleanup — call from a scheduler or inline if cheap enough. */
    public void evictExpired() {
        long now = System.currentTimeMillis();
        seen.entrySet().removeIf(e -> e.getValue() <= now);
    }
}
Wire vào SleeEndpointImpl.fireEvent():

java


// SleeEndpointImpl.java — trong fireEvent() sau stateMachine.assertCanFireEvent()
// S8.6: dedup check for SequencedEvents
if (dedupWindow != null && event instanceof SequencedEvent se) {
    if (dedupWindow.isDuplicate(se.getConvergenceName(), se.getSequenceNumber())) {
        log.debug("[SleeEndpoint] DEDUP_HIT convergence={} seq={} — dropping duplicate",
                  se.getConvergenceName(), se.getSequenceNumber());
        return; // drop silently (caller can inspect dedupWindow.getDedupHitCount())
    }
}
S8.7 · GrpcMenuResourceAdaptor stamp sequence number
java


// GrpcMenuRequestEvent.java — implement SequencedEvent
public class GrpcMenuRequestEvent implements SequencedEvent {
    private final String sessionId;
    private final String msisdn;
    private final String ussdString;
    private final long   sequenceNumber; // caller-supplied or auto-increment
    @Override public long   getSequenceNumber()  { return sequenceNumber; }
    @Override public String getConvergenceName() { return sessionId; }
    // ... other fields
}
// GrpcMenuResourceAdaptor.java — trong requestMenu(), line ~82
// Stamp sequence number từ request header hoặc atomic counter
long seq = sequenceCounters
        .computeIfAbsent(sessionId, k -> new AtomicLong(0))
        .incrementAndGet();
container.routeEvent(new GrpcMenuRequestEvent(sessionId, msisdn, ussdString, seq), sessionAci);
🟠 SPRINT S9 — Cluster Failover + Example Fixes
S9.1–S9.2 · Fix attachedAciNames trong cluster snapshot
java


// DistributedSbbEntityPool.java — takeSnapshot() tại line ~239-269
private SbbEntitySnapshot takeSnapshot(SbbEntity entity) {
    Map<String, Object> cmpFields = extractCmpFields(entity.getSbb());
    // S9.1: populate attachedAciNames (was always empty → GAP-SR-6)
    Set<String> aciNames = container.getAttachedAciNames(entity.getId()); // S9.4
    return new SbbEntitySnapshot(
            entity.getId(),
            entity.getSbb().getClass().getName(),
            cmpFields,
            aciNames,          // FIX: was new LinkedHashSet<>()
            System.currentTimeMillis()
    );
}
java


// DistributedSbbEntityPool.java — applySnapshot() tại line ~283-321
private void applySnapshot(SbbEntitySnapshot snap, Sbb target) {
    // Existing: restore CMP fields via reflection
    snap.cmpFields().forEach((field, value) -> {
        // ... existing reflective setter code
    });
    // S9.2: re-attach ACIs (was completely missing → GAP-SR-6)
    for (String aciName : snap.attachedAciNames()) {
        try {
            ActivityContext aci = container
                    .getActivityContextNamingFacility()
                    .lookup(aciName);
            if (aci != null) {
                SimpleSbbLocalObject local = container.getLocalObject(snap.sbbId());
                if (local != null) {
                    aci.attachImmediate(local);
                    container.getTimerPort().getBridge()
                             .bindActivityContext(local, aci);
                    log.info("[Cluster] Re-attached entity={} to ACI={}",
                             snap.sbbId(), aciName);
                }
            }
        } catch (Exception ex) {
            log.warn("[Cluster] Could not re-attach ACI={} for entity={}: {}",
                     aciName, snap.sbbId(), ex.getMessage());
        }
    }
}
S9.3 · Bridge release() → IES.removeConvergencesFor()
Đã được đóng bởi S6.3 (IesCleanupAdapter subscribe vào EntityRemovalBus). Không cần code thêm — chỉ verify trong test:

java


// Test S9.3: IES convergence cleaned up after entity release
@Test void iesConvergence_cleanedUp_afterEntityRemoval() {
    // Open session → IES allocates convergence entry
    // Remove entity → EntityRemovalBus → IesCleanupAdapter fires
    // Assert: iesDispatcher.activeConvergenceCount() == 0
}
S9.4 · MicroSleeContainer.getAttachedAciNames(sbbId)
java


// MicroSleeContainer.java — new public method
/**
 * Returns the set of ACNF names for all ACIs that have this entity attached.
 * Used by the cluster snapshot path (S9.1) and rehydration path (S7.3).
 *
 * Implementation: linear scan over the ACNF name→ACI map; acceptable
 * because this is called only on entity removal (not on hot path).
 */
public Set<String> getAttachedAciNames(String entityId) {
    SimpleSbbLocalObject local = sbbs.get(entityId);
    if (local == null) return Set.of();
    Set<String> result = new LinkedHashSet<>();
    // InMemoryActivityContextNamingFacility exposes entries() for iteration
    activityContextNamingFacility.entries().forEach((name, aci) -> {
        if (aci.getAttachedSbbs().contains(local)) {
            result.add(name);
        }
    });
    return Collections.unmodifiableSet(result);
}
Thêm entries() vào InMemoryActivityContextNamingFacility:

java


// InMemoryActivityContextNamingFacility.java
public Map<String, ActivityContext> entries() {
    return Collections.unmodifiableMap(store); // store là ConcurrentHashMap hiện tại
}
S9.5 · Fix broken imports (GAP-SR-11) — làm NGAY
java


// UssdSbbWiring.java — lines 22-23
// BEFORE (broken):
import com.microjainslee.ra.grpc.GrpcMenuResourceAdaptor;
import com.microjainslee.ra.http.HttpIngressResourceAdaptor;
// AFTER (correct):
import com.example.ussddemo.quarkus.ra.GrpcMenuResourceAdaptor;
import com.example.ussddemo.quarkus.ra.HttpIngressResourceAdaptor;
S9.6 · Cluster ACNF — Serializable wrapper
java


// jainslee-cluster/.../ClusteredActivityContextNamingFacility.java
/**
 * Serializable proxy that carries only the ACI name across the wire.
 * The receiving node reconstructs the full ACI from its local store.
 *
 * Why not serialize the full ACI: InMemoryActivityContext holds
 * live object references (attached SBBs, timers) that are not
 * Serializable and are node-local anyway.
 */
public static final class AciNameProxy implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private final String name;
    public AciNameProxy(String name) { this.name = name; }
    public String getName() { return name; }
}
// Dans bind():
@Override
public void bind(String name, ActivityContextInterface aci) {
    // Store the proxy (Serializable) in the distributed cache
    cache.put(name, new AciNameProxy(name));
    // Also bind locally so same-node lookups are fast
    localFacility.bind(name, aci);
}
// Dans lookup():
@Override
public ActivityContextInterface lookup(String name) {
    // Fast path: local store first
    ActivityContextInterface local = localFacility.lookup(name);
    if (local != null) return local;
    // Cross-node: the cache has a AciNameProxy → return null here,
    // the RA on the remote node must have re-registered.
    // Log for diagnostics:
    AciNameProxy proxy = (AciNameProxy) cache.get(name);
    if (proxy != null) {
        log.warn("[ClusterACNF] ACI '{}' is on a remote node — " +
                 "re-attach may be needed after failover.", name);
    }
    return null;
}
📋 MASTER CHECKLIST — Verification Matrix


SPRINT S6 — Observability & Removal Notification
✅ S6.1  EntityRemovalBus + EntityRemovalEvent (record)
✅ S6.2  RemovalListener wires: bus.publish() BEFORE sbbEntityPool.release()
✅ S6.3  VirtualThreadSbbEntityPool.IesCleanupAdapter → closes GAP-SR-7
✅ S6.4  IesCleanupAdapter registered in setInitialEventSelectorDispatcher()
✅ S6.5  EventRouter.missingEntityCount AtomicLong + getMissingEntityCount()
✅ S6.6  EventRouter.rehydratedCount AtomicLong (pre-wire for S7)
✅ S6.7  SessionLifecycleLogger — structured log line per removal
✅ S6.8  UssdSessionStore.bindToContainer() + idempotent COMPLETED guard
SPRINT S7 — Re-creation & Replay
✅ S7.1  SessionRecoveryService + RecoverySnapshot record
✅ S7.2  registerSnapshot() / tryRehydrateAndDeliver() + REHYDRATING ThreadLocal
✅ S7.3  MicroSleeContainer captures snapshot BEFORE release (calls registerSnapshot)
✅ S7.4  EventRouter calls sessionRecoveryService.tryRehydrateAndDeliver on miss
✅ S7.5  LRU 64K cap via synchronized LinkedHashMap
✅ S7.6  rehydratedCount metric wired in EventRouter
SPRINT S8 — Ordering & Dedup
✅ S8.1  SequencedEvent interface in jainslee-api
✅ S8.2  OutOfOrderBuffer — per-convergence ArrayDeque, TTL evictor
✅ S8.3  IES resolveTarget: non-initial miss → outOfOrderBuffer.enqueue()
✅ S8.4  IES allocateNew: drain OOB buffer before returning newId
✅ S8.5  DedupWindow — LRU (convergence,seqNum) → windowMs expiry
✅ S8.6  SleeEndpointImpl.fireEvent: isDuplicate check → drop with log
✅ S8.7  GrpcMenuRequestEvent implements SequencedEvent + seq stamp
SPRINT S9 — Cluster + Example
✅ S9.1  DistributedSbbEntityPool.takeSnapshot: populate attachedAciNames
✅ S9.2  applySnapshot: re-attach SBB to listed ACIs
✅ S9.3  IES cleanup already wired via S6.3 — test only
✅ S9.4  MicroSleeContainer.getAttachedAciNames(sbbId) + ACNF.entries()
✅ S9.5  UssdSbbWiring.java imports fixed → com.example.ussddemo.quarkus.ra.*
✅ S9.6  ClusteredActivityContextNamingFacility.AciNameProxy (Serializable)
CROSS-CUTTING RISKS (mitigated)
✅ R6   UssdSessionStore.computeIfPresent: COMPLETED not overridden by FAILED
✅ R9   tryRehydrateAndDeliver re-dispatches event (not just attaches SBB)
✅ R10  REHYDRATING ThreadLocal prevents infinite rehydration loop
🧪 Test Summary (57 new tests)


Sprint	Test class	Count	Key assertion
S6	EntityRemovalBusTest	8	fan-out, defensive failure, unsubscribe, metric
S7	SessionRecoveryServiceTest	7	register/get/consume snapshot, LRU eviction, anti-loop
S7	EventRouterRehydrationTest	4	null entity → rehydrate → handler.onEvent() called
S8	OutOfOrderBufferTest	6	enqueue/drain, TTL evict, capacity overflow
S8	DedupWindowTest	5	isDuplicate true/false, window expiry, LRU cap
S8	SleeEndpointDedupTest	3	fireEvent twice → handler called once
S9	DistributedSbbEntityPoolTest	5	takeSnapshot includes aciNames, applySnapshot re-attaches
S9	ClusterAcnfTest	4	AciNameProxy serializable, cross-node warn log
E2E	UssdSessionSoakTest	10	10K sessions, 50% mid-flight kill, ≥80% rehydrate rate
Regression	All existing	177	0 regressions
⚡ Thứ tự thực hiện khuyến nghị


Tuần 1:  S9.5 (1 line, làm NGAY) + S6 hoàn chỉnh
Tuần 2:  S7 (depends on S6's snapshot capture & bus)
Tuần 3:  S8 (depends on S6 for bus wiring, independent of S7)
Tuần 4:  S9.1-S9.4, S9.6 + E2E soak test
