/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.cluster;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production-2 SS7 HA lab metrics (ADR 0001). Always-on atomics; optional
 * {@link LongConsumer} sinks (e.g. Micrometer) via {@link #bindSink}.
 *
 * <p>Not a claim of production multi-ASP HA — operators use these to prove
 * the lab soak (export / CONTINUE-miss / sticky reject).</p>
 */
public final class TcapFailoverMetrics {

    public static final String EXPORT_OK = "ss7_tcap_failover_export_ok_total";
    public static final String EXPORT_FAIL = "ss7_tcap_failover_export_fail_total";
    public static final String IMPORT_OK = "ss7_tcap_failover_import_ok_total";
    public static final String IMPORT_FAIL = "ss7_tcap_failover_import_fail_total";
    public static final String CONTINUE_MISS = "ss7_tcap_failover_continue_miss_total";
    public static final String CONTINUE_RESOLVE_FAIL = "ss7_tcap_failover_continue_resolve_fail_total";
    public static final String TAKEOVER_OK = "ss7_tcap_failover_takeover_ok_total";
    public static final String TAKEOVER_FAIL = "ss7_tcap_failover_takeover_fail_total";
    public static final String STICKY_REJECT = "ss7_tcap_sticky_reject_total";
    public static final String STICKY_MISS = "ss7_tcap_sticky_miss_total";

    @FunctionalInterface
    public interface NamedCounterSink {
        void increment(String metricName);
    }

    private final AtomicLong exportOk = new AtomicLong();
    private final AtomicLong exportFail = new AtomicLong();
    private final AtomicLong importOk = new AtomicLong();
    private final AtomicLong importFail = new AtomicLong();
    private final AtomicLong continueMiss = new AtomicLong();
    private final AtomicLong continueResolveFail = new AtomicLong();
    private final AtomicLong takeoverOk = new AtomicLong();
    private final AtomicLong takeoverFail = new AtomicLong();
    private final AtomicLong stickyReject = new AtomicLong();
    private final AtomicLong stickyMiss = new AtomicLong();

    private volatile NamedCounterSink sink;

    public void bindSink(NamedCounterSink sink) {
        this.sink = sink;
    }

    public void exportOk() {
        bump(exportOk, EXPORT_OK);
    }

    public void exportFail() {
        bump(exportFail, EXPORT_FAIL);
    }

    public void importOk() {
        bump(importOk, IMPORT_OK);
    }

    public void importFail() {
        bump(importFail, IMPORT_FAIL);
    }

    public void continueMiss() {
        bump(continueMiss, CONTINUE_MISS);
    }

    public void continueResolveFail() {
        bump(continueResolveFail, CONTINUE_RESOLVE_FAIL);
    }

    public void takeoverOk() {
        bump(takeoverOk, TAKEOVER_OK);
    }

    public void takeoverFail() {
        bump(takeoverFail, TAKEOVER_FAIL);
    }

    public void stickyReject() {
        bump(stickyReject, STICKY_REJECT);
    }

    /** No dialog owner for Continue/End — sticky miss. */
    public void stickyMiss() {
        bump(stickyMiss, STICKY_MISS);
    }

    public long exportOkCount() {
        return exportOk.get();
    }

    public long exportFailCount() {
        return exportFail.get();
    }

    public long importOkCount() {
        return importOk.get();
    }

    public long importFailCount() {
        return importFail.get();
    }

    public long continueMissCount() {
        return continueMiss.get();
    }

    public long stickyRejectCount() {
        return stickyReject.get();
    }

    public long stickyMissCount() {
        return stickyMiss.get();
    }

    public long takeoverOkCount() {
        return takeoverOk.get();
    }

    public long takeoverFailCount() {
        return takeoverFail.get();
    }

    /** Stable map for lab scrape / admin JSON. */
    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(EXPORT_OK, exportOk.get());
        m.put(EXPORT_FAIL, exportFail.get());
        m.put(IMPORT_OK, importOk.get());
        m.put(IMPORT_FAIL, importFail.get());
        m.put(CONTINUE_MISS, continueMiss.get());
        m.put(CONTINUE_RESOLVE_FAIL, continueResolveFail.get());
        m.put(TAKEOVER_OK, takeoverOk.get());
        m.put(TAKEOVER_FAIL, takeoverFail.get());
        m.put(STICKY_REJECT, stickyReject.get());
        m.put(STICKY_MISS, stickyMiss.get());
        return Map.copyOf(m);
    }

    private void bump(AtomicLong counter, String name) {
        counter.incrementAndGet();
        NamedCounterSink s = sink;
        if (s != null) {
            s.increment(name);
        }
    }
}
