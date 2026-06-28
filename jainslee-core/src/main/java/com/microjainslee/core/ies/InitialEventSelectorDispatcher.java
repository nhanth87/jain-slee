/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core.ies;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.annotations.InitialEventSelect;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Initial Event Selector (IES) dispatcher — Perfect Core S3.
 *
 * <p>Critical piece missing from the legacy router. Without IES every
 * incoming event creates a brand-new SBB entity, breaking all
 * stateful protocols (USSD, SIP dialogs, custom dialogs…).
 *
 * <p>Spec reference: JAIN SLEE 1.1 §7.5 — Initial Event Selection.
 *
 * <h2>USSD example</h2>
 * <pre>{@code
 *   Message 1 (UssdBegin)     → IES says convergence="4479…:d5", initial=true
 *                                → pool.allocateNew() → SBB_001, indexed
 *   Message 2 (UssdContinue)  → IES says convergence="4479…:d5", initial=false
 *                                → lookup → SBB_001 ✓ (state preserved)
 * }</pre>
 *
 * <h2>Wiring</h2>
 * The dispatcher is bound to {@code EventRouter} via
 * {@link com.microjainslee.core.EventRouter#bindInitialEventSelectorDispatcher(Object)}
 * from {@link com.microjainslee.core.MicroSleeContainer#setInitialEventSelectorDispatcher(Object)}.
 * When no dispatcher is bound the router skips IES and falls back to its
 * legacy behaviour (allocate-per-event).
 *
 * @author Tran Nhan (nhanth87)
 */
public class InitialEventSelectorDispatcher {

    private static final Logger LOG = LogManager.getLogger(InitialEventSelectorDispatcher.class);

    /**
     * Minimal pool contract used by the dispatcher. {@code EventRouter}
     * exposes this through an adapter that wraps
     * {@code VirtualThreadSbbEntityPool}; the kernel binds the adapter
     * reflectively so the core stays IES-package-free at compile time.
     */
    public interface SbbEntityPool {
        /** Allocate a brand-new SBB entity and return its id. */
        String allocateNew(Class<?> sbbClass);

        /** Returns {@code true} if the entity currently exists (not yet removed). */
        boolean contains(String entityId);

        /**
         * Register a cleanup callback invoked when the entity is removed
         * (e.g. {@code sbbRemove}). Used to drop stale convergence entries.
         */
        void onEntityRemoved(String entityId, Consumer<String> callback);
    }

    private final SbbEntityPool pool;

    /** convergenceName → entityId (e.g. USSD: "447911123456:d5" → "sbb-uuid-001"). */
    private final ConcurrentHashMap<String, String> convergenceIndex = new ConcurrentHashMap<>();

    /** sbbClass → IES method (reflection cache). */
    private final ConcurrentHashMap<Class<?>, Method> iesMethodCache = new ConcurrentHashMap<>();

    public InitialEventSelectorDispatcher(SbbEntityPool pool) {
        if (pool == null) {
            throw new IllegalArgumentException("SbbEntityPool is required");
        }
        this.pool = pool;
    }

    /**
     * Resolve the target SBB entity for an incoming event.
     *
     * @param event     incoming event object
     * @param aci       activity context interface
     * @param sbbClass  SBB class registered for this event type
     * @return entity id to dispatch to, or {@code null} if the event should
     *         be silently dropped per spec §7.5.5
     */
    public String resolveTarget(Object event, ActivityContextInterface aci,
                                Class<?> sbbClass) {
        if (sbbClass == null) {
            return null;
        }

        Method iesMethod = findIesMethod(sbbClass);

        // No IES declared → default behaviour: always allocate new entity
        // (preserves backward compat for stateless SBBs).
        if (iesMethod == null) {
            String newId = pool.allocateNew(sbbClass);
            LOG.trace("No IES → new entity {} for {}", newId, sbbClass.getSimpleName());
            return newId;
        }

        // Invoke IES method on a TEMP instance (spec: IES must be side-effect free).
        InitialEventSelectResult result = invokeIes(iesMethod, sbbClass, event, aci);
        if (result == null) {
            LOG.warn("IES returned null for {} — dropping event per spec §7.5.5",
                    sbbClass.getSimpleName());
            return null;
        }

        String convergenceName = result.getConvergenceName();

        // Try to reuse an existing entity under the same convergence key.
        if (convergenceName != null) {
            String existingId = convergenceIndex.get(convergenceName);
            if (existingId != null && pool.contains(existingId)) {
                LOG.trace("IES convergence hit: [{}] → entity {}",
                        convergenceName, existingId);
                return existingId;
            }
            // Stale mapping → drop it.
            if (existingId != null) {
                convergenceIndex.remove(convergenceName);
                LOG.debug("IES stale convergence cleaned: [{}]", convergenceName);
            }
        }

        // No existing entity found.
        if (!result.isInitialEvent()) {
            // Spec §7.5.5: non-initial event with no matching entity → drop silently.
            LOG.debug("IES: non-initial event, no entity for convergence [{}] — dropped",
                    convergenceName);
            return null;
        }

        // Initial event ⇒ allocate a fresh entity and index it.
        String newId = pool.allocateNew(sbbClass);

        if (convergenceName != null) {
            convergenceIndex.put(convergenceName, newId);
            LOG.debug("IES new entity: [{}] → {} ({})",
                    convergenceName, newId, sbbClass.getSimpleName());

            // Auto-cleanup on entity removal.
            final String key = convergenceName;
            pool.onEntityRemoved(newId, id -> {
                convergenceIndex.remove(key);
                LOG.debug("IES convergence released: [{}]", key);
            });
        }

        return newId;
    }

    /**
     * Explicit removal — drop every convergence mapping that points at
     * {@code entityId}. Called when an SBB explicitly removes itself.
     */
    public void removeConvergencesFor(String entityId) {
        if (entityId == null) {
            return;
        }
        convergenceIndex.entrySet().removeIf(e -> e.getValue().equals(entityId));
    }

    /** Size of the convergence index — useful for metrics/health. */
    public int activeConvergenceCount() {
        return convergenceIndex.size();
    }

    // ───────────────────────────────────────────────────────────────
    // Private helpers
    // ───────────────────────────────────────────────────────────────

    private InitialEventSelectResult invokeIes(Method iesMethod, Class<?> sbbClass,
                                               Object event, ActivityContextInterface aci) {
        try {
            // IES runs on a TEMP instance — NOT a pool entity.
            Sbb tempSbb = (Sbb) sbbClass.getDeclaredConstructor().newInstance();
            InitialEventSelectCondition condition =
                    new InitialEventSelectCondition(event, aci);
            return (InitialEventSelectResult) iesMethod.invoke(tempSbb, condition);
        } catch (ReflectiveOperationException roe) {
            LOG.error("IES invocation failed for {} — treating as null",
                    sbbClass.getSimpleName(), roe);
            return null;
        } catch (Exception e) {
            // Last-ditch catch: IES implementations must not throw, but if
            // they do we treat it as "drop the event" rather than crash.
            LOG.error("Unexpected IES error for {} — dropping event",
                    sbbClass.getSimpleName(), e);
            return null;
        }
    }

    private Method findIesMethod(Class<?> sbbClass) {
        return iesMethodCache.computeIfAbsent(sbbClass, cls -> {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.isAnnotationPresent(InitialEventSelect.class) &&
                        m.getParameterCount() == 1 &&
                        m.getParameterTypes()[0] == InitialEventSelectCondition.class &&
                        m.getReturnType() == InitialEventSelectResult.class) {
                    m.setAccessible(true);
                    LOG.debug("IES method found: {}#{}",
                            cls.getSimpleName(), m.getName());
                    return m;
                }
            }
            return null; // Sentinel — null means "no IES on this SBB".
        });
    }
}
