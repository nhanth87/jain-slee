/*
 * micro-jainslee
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 */

package com.microjainslee.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Secondary-index buckets must not outlive the rows that filled them. A unique-valued index
 * (request id, dialog id) used to keep one empty bucket per row ever written: unbounded heap
 * growth at any steady create/remove rate (USSD ussdTx: ~0.7 KB per session).
 */
public class InMemoryProfileTableIndexBucketTest {

    /** Rows only need identity here; field values live in the table's own field map. */
    private static final class Row implements com.microjainslee.api.Profile {
        @Override public com.microjainslee.api.ProfileID getProfileID() { return null; }
        @Override public Object getCmpField(String name) { return null; }
        @Override public void setCmpField(String name, Object value) { }
        @Override public String[] getCmpFieldNames() { return new String[0]; }
    }

    @Test
    public void removingRowsDropsTheirUniqueIndexBuckets() {
        InMemoryProfileTable t = new InMemoryProfileTable("tx");
        t.registerIndex("requestId");
        t.registerIndex("state");
        for (int i = 0; i < 1_000; i++) {
            String name = "corr-" + i;
            t.put(name, new Row());
            t.writeField(name, "requestId", "req-" + i);
            t.writeField(name, "state", "ACTIVE");
            t.writeField(name, "state", "AWAITING_AS");
            t.remove(name);
        }
        assertEquals(0, t.indexBucketCount("requestId"));
        assertEquals(0, t.indexBucketCount("state"));
    }

    @Test
    public void overwritingAUniqueValueDropsTheOldBucket() {
        InMemoryProfileTable t = new InMemoryProfileTable("tx");
        t.registerIndex("dialogId");
        t.put("a", new Row());
        t.writeField("a", "dialogId", "d1");
        t.writeField("a", "dialogId", "d2");
        assertEquals(1, t.indexBucketCount("dialogId"));
        assertTrue(t.findByAttribute("dialogId", "d1").isEmpty());
        assertEquals(1, t.findByAttribute("dialogId", "d2").size());
        t.writeField("a", "dialogId", null);
        assertEquals(0, t.indexBucketCount("dialogId"));
    }

    @Test
    public void concurrentAddAndRemoveOnASharedValueNeverLosesALiveRow() throws Exception {
        InMemoryProfileTable t = new InMemoryProfileTable("tx");
        t.registerIndex("state");
        int threads = 8;
        int perThread = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int th = 0; th < threads; th++) {
            final int id = th;
            futures.add(pool.submit(() -> {
                go.await();
                // Each thread churns a row in and out of the shared "S" bucket, so the bucket
                // is repeatedly emptied (dropped) and re-created under contention.
                String churn = "churn-" + id;
                for (int i = 0; i < perThread; i++) {
                    t.put(churn, new Row());
                    t.writeField(churn, "state", "S");
                    t.remove(churn);
                }
                // Then leave one row parked in the bucket; it must be findable.
                String keep = "keep-" + id;
                t.put(keep, new Row());
                t.writeField(keep, "state", "S");
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(threads, t.findByAttribute("state", "S").size());
        assertEquals(1, t.indexBucketCount("state"));
    }
}
