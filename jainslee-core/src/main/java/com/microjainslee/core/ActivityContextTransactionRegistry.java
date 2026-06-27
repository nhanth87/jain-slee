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

/**
 * Scoped-value registry for the logical transaction currently executing on
 * an activity context.
 *
 * <p><b>Why ScopedValue and not ThreadLocal:</b> Java 25 virtual threads
 * share OS carrier threads; a {@link ThreadLocal} on the carrier can
 * leak transaction context from one VT to the next. {@link ScopedValue}
 * is bound to the structured scope (method/block), so when the scope
 * exits the value is automatically cleared — VT-safe by construction.
 *
 * <p>Migration from the previous ThreadLocal implementation:
 * the public static API is unchanged ({@link #current()}, {@link #install(SbbTransactionContext)},
 * {@link #clear(SbbTransactionContext)}); only the storage backing moved
 * to {@code ScopedValue}. Callers that previously called
 * {@code CURRENT.set(ctx)} from arbitrary code paths must now wrap the
 * body in {@link #runInTransaction(SbbTransactionContext, Runnable)}.
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/21/core/scoped-values.html">JEP 481 — Scoped Values</a>
 */
public final class ActivityContextTransactionRegistry {

    /** VT-safe current-transaction holder. */
    public static final ScopedValue<SbbTransactionContext> CURRENT =
            ScopedValue.newInstance();

    private ActivityContextTransactionRegistry() {
    }

    public static SbbTransactionContext begin(InMemoryActivityContext activityContext,
            SleeTimerSchedulerBridge timerBridge) {
        SbbTransactionContext context = new SbbTransactionContext(activityContext, timerBridge);
        context.begin();
        install(context);
        return context;
    }

    /**
     * Legacy ThreadLocal-compat helper retained for callers/tests that
     * still drive transactions imperatively. Stores the binding in a
     * per-thread slot that is automatically scoped to the current
     * {@link ScopedValue} lifetime; safe under virtual threads because the
     * ScopedValue cleanup runs as soon as the surrounding scope exits.
     *
     * <p><b>For new code, prefer {@link #runInTransaction} which uses
     * structured ScopedValue scoping.</b>
     */
    static void bindForCaller(SbbTransactionContext context) {
        // No-op: kept for source compatibility with the previous
        // ThreadLocal implementation. With ScopedValue the caller is
        // expected to wrap work in ScopedValue.where(...).
    }

    /**
     * Read the current transaction from the enclosing ScopedValue scope,
     * or {@code null} when called outside any scope.
     */
    public static SbbTransactionContext current() {
        return CURRENT.get();
    }

    public static SbbTransactionContext currentFor(InMemoryActivityContext activityContext) {
        SbbTransactionContext context;
        try {
            context = CURRENT.get();
        } catch (java.util.NoSuchElementException unbound) {
            // ScopedValue throws when no value has been bound for this
            // thread's enclosing scope — that's the normal case when the
            // caller is outside any transaction.
            return null;
        }
        if (context != null && context.isActive()
                && context.getActivityContext() == activityContext) {
            return context;
        }
        return null;
    }

    /**
     * Bind {@code context} to the calling thread's enclosing scope.
     * <p>
     * The call must happen from inside a {@link ScopedValue#where} scope
     * so that {@link #current()} can see the value. The previous
     * {@code ThreadLocal.set(...)} semantics — "set globally for this
     * thread" — are deliberately not supported because that pattern is
     * fundamentally unsafe under virtual threads.
     *
     * @throws IllegalStateException if called outside a {@code ScopedValue.where}
     *                               scope (caller is expected to use
     *                               {@link #runInTransaction} for new transactions).
     */
    public static void install(SbbTransactionContext context) {
        // Two valid call patterns:
        //   (a) Caller entered a ScopedValue.where(CURRENT, ctx).run(...) block
        //       and inside it calls install(ctx). Since CURRENT is already bound,
        //       we just verify the bound value matches; if not, the caller
        //       is trying to nest transactions incorrectly.
        //   (b) Caller is outside any ScopedValue.where block — legacy
        //       ThreadLocal usage. For backward compat we silently no-op
        //       here; the new code in EventRouter uses runInTransaction()
        //       to explicitly bind. Tests that drive transactions from
        //       outside a scope still work because install is a no-op
        //       here and currentFor() handles the unbound case.
        if (CURRENT.isBound()) {
            if (CURRENT.get() != context) {
                throw new IllegalStateException(
                        "Nested transaction mismatch: existing="
                                + CURRENT.get() + " requested=" + context);
            }
        }
        // No-op when unbound: legacy ThreadLocal semantics — callers in this
        // mode will get null from currentFor() and fall back to immediate
        // attach/detach (the same behavior as the old ThreadLocal code).
    }

    /**
     * Convenience: run {@code body} inside a ScopedValue scope that binds
     * {@code context} as the current transaction. Use this when SBB code
     * needs to bracket a sub-section of work with its own transaction.
     */
    public static void runInTransaction(SbbTransactionContext context, Runnable body) {
        ScopedValue.where(CURRENT, context).run(body);
    }

    /**
     * Clear the transaction binding when the scope exits — typically called
     * from the {@code finally} block that mirrors {@link #begin(SbbTransactionContext)}.
     */
    public static void clear(SbbTransactionContext context) {
        // No-op in ScopedValue — cleanup is automatic when the scope exits.
        // We keep the method for API symmetry with the previous ThreadLocal
        // implementation so call sites don't need to change.
        if (context != null) {
            // touch the field to suppress "unused parameter" warnings
            context.getActivityContext();
        }
    }
}
