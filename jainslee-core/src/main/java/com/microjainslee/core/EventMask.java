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

import com.microjainslee.api.SleeEvent;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import org.jctools.maps.NonBlockingHashSet;

/**
 * JAIN-SLEE 1.1 §8.6 — Event Mask.
 *
 * <p>An {@code EventMask} declares the set of {@link SleeEvent} types an SBB
 * is willing to receive. {@link EventRouter} consults the mask before
 * invoking {@code onEvent} so SBBs are not woken up for irrelevant traffic.
 * Without this filter, a busy router spends cycles and transactions on
 * every attached SBB on every event — which is the single biggest hot-loop
 * waste called out in the micro-jainslee audit (see
 * {@code docs/micro-jainslee-audit-v2.md}, G1).
 *
 * <h2>Memory model</h2>
 * The {@link #EMPTY} singleton uses {@link Collections#emptySet()} so the
 * "SBB accepts nothing" path has zero allocation overhead. The active mask
 * uses {@link NonBlockingHashSet} (JCTools) for lock-free concurrent reads
 * on the hot dispatch path; writes happen only during
 * {@link MicroSleeContainer#registerSbb(String, com.microjainslee.api.Sbb, EventMask)}
 * so read-mostly is the correct design point.
 *
 * <h2>{@link #ACCEPT_ALL} vs {@link #EMPTY}</h2>
 * <ul>
 *   <li>{@link #ACCEPT_ALL} — every {@link SleeEvent} passes the mask
 *       check (cheap one-call fast path, no allocation).</li>
 *   <li>{@link #EMPTY} — no event passes; used by SBBs that never
 *       consume events (timer-only SBBs, glue services).</li>
 * </ul>
 *
 * @author Tran Nhan (nhanth87)
 */
public final class EventMask {

    /**
     * Sentinel "accept no events at all" mask. Calls to
     * {@link #matches(SleeEvent)} always return {@code false}.
     */
    public static final EventMask EMPTY = new EventMask(Mode.EMPTY, Collections.<Class<?>>emptySet());

    /**
     * Sentinel "accept every event type" mask. Calls to
     * {@link #matches(SleeEvent)} always return {@code true}.
     */
    public static final EventMask ACCEPT_ALL = new EventMask(Mode.ACCEPT_ALL, null);

    /** Internal discriminator — lets us avoid touching the underlying set on the hot path. */
    private enum Mode { EMPTY, ACCEPT_ALL, FILTER }

    private final Mode mode;
    private final Set<Class<?>> accepted;

    /** Default — equivalent to {@link #ACCEPT_ALL}; kept for backwards compat. */
    public EventMask() {
        this(Mode.ACCEPT_ALL, null);
    }

    /**
     * Build a mask from explicit event types. An empty {@code types}
     * array (or {@code null}) is treated as {@link #ACCEPT_ALL} so
     * callers who pass an empty varargs don't silently disable event
     * delivery.
     */
    public EventMask(Class<?>... types) {
        this(resolveMode(types), toNonBlockingSet(types));
    }

    /**
     * Build a mask from any {@link Collection} of event types. An empty
     * or {@code null} collection is treated as {@link #ACCEPT_ALL} (see
     * the rationale on the varargs constructor).
     */
    public EventMask(Collection<Class<?>> types) {
        this(resolveMode(types), toNonBlockingSet(types));
    }

    private static Mode resolveMode(Object types) {
        if (types == null) {
            return Mode.ACCEPT_ALL;
        }
        if (types instanceof Class<?>[]) {
            return ((Class<?>[]) types).length == 0 ? Mode.ACCEPT_ALL : Mode.FILTER;
        }
        if (types instanceof Collection<?>) {
            return ((Collection<?>) types).isEmpty() ? Mode.ACCEPT_ALL : Mode.FILTER;
        }
        return Mode.FILTER;
    }

    private EventMask(Mode mode, Set<Class<?>> accepted) {
        this.mode = mode;
        this.accepted = accepted;
    }

    /**
     * §8.6 — does this mask accept {@code event}?
     *
     * <p>This is on the hot dispatch path; do not allocate, do not log,
     * do not block. Implementation is a single {@code switch} on the
     * mode discriminator followed by a lock-free {@link Set#contains}
     * for {@code FILTER} mode.
     */
    public boolean matches(SleeEvent event) {
        if (event == null) {
            return false;
        }
        switch (mode) {
            case ACCEPT_ALL:
                return true;
            case EMPTY:
                return false;
            case FILTER:
            default:
                return accepted.contains(event.getClass());
        }
    }

    /**
     * @return {@code true} when this mask is the {@link #EMPTY} sentinel
     *         (no event ever passes the filter).
     */
    public boolean isEmpty() {
        return mode == Mode.EMPTY;
    }

    /**
     * @return {@code true} when this mask is the {@link #ACCEPT_ALL} sentinel
     *         (every event passes the filter).
     */
    public boolean isAcceptAll() {
        return mode == Mode.ACCEPT_ALL;
    }

    /**
     * @return number of accepted event classes, or {@code 0} for
     *         {@link #EMPTY}, or {@link Integer#MAX_VALUE} for
     *         {@link #ACCEPT_ALL}.
     */
    public int size() {
        switch (mode) {
            case ACCEPT_ALL:
                return Integer.MAX_VALUE;
            case EMPTY:
                return 0;
            case FILTER:
            default:
                return accepted.size();
        }
    }

    private static Set<Class<?>> toNonBlockingSet(Class<?>[] types) {
        // JCTools NonBlockingHashSet only ships a no-arg constructor; we
        // therefore lose the capacity hint and rely on the lock-free
        // internal resizing. That is fine because EventMask instances are
        // long-lived (one per SBB registration) and grow at most once.
        Set<Class<?>> set = new NonBlockingHashSet<Class<?>>();
        if (types == null) {
            return set;
        }
        for (Class<?> t : types) {
            if (t != null) {
                set.add(t);
            }
        }
        return set;
    }

    private static Set<Class<?>> toNonBlockingSet(Collection<Class<?>> types) {
        Set<Class<?>> set = new NonBlockingHashSet<Class<?>>();
        if (types == null) {
            return set;
        }
        for (Class<?> t : types) {
            if (t != null) {
                set.add(t);
            }
        }
        return set;
    }

    /**
     * For diagnostic / logging only. Returns the underlying allow-list
     * (read-only view), or {@code null} for {@link #ACCEPT_ALL}.
     */
    Set<Class<?>> rawAccepted() {
        return accepted;
    }

    @Override
    public String toString() {
        switch (mode) {
            case ACCEPT_ALL:
                return "EventMask[ACCEPT_ALL]";
            case EMPTY:
                return "EventMask[EMPTY]";
            default:
                return "EventMask[filter=" + accepted + "]";
        }
    }
}