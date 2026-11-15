/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.clickhouse;

import com.microjainslee.api.OutboundCommand;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Batching, spool-on-failure and token-preserving replay, against a fake ClickHouse. */
public class ClickHouseWriterTest {

    static class Insert {
        final String token;
        final String body;
        Insert(String token, String body) { this.token = token; this.body = body; }
    }

    private HttpServer server;
    private final List<Insert> inserts = new CopyOnWriteArrayList<>();
    private final AtomicBoolean down = new AtomicBoolean();
    private ClickHouseHttp ch;

    @Rule
    public TemporaryFolder spoolParent = new TemporaryFolder();

    private java.nio.file.Path spool;

    private static byte[] codec(List<String> rows, String node) {
        StringBuilder b = new StringBuilder();
        for (String r : rows) {
            b.append("{\"v\":\"").append(r).append("\",\"node\":\"").append(node).append("\"}\n");
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            int code = down.get() ? 503 : 200;
            if (!down.get()) {
                Map<String, String> q = query(ex.getRequestURI());
                if (q.getOrDefault("query", "").startsWith("INSERT")) {
                    inserts.add(new Insert(q.get("insert_deduplication_token"),
                            new String(body, StandardCharsets.UTF_8)));
                }
            }
            byte[] resp = down.get() ? "Code: 210. DB::NetException".getBytes() : new byte[0];
            ex.sendResponseHeaders(code, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) {
                ex.getResponseBody().write(resp);
            }
            ex.close();
        });
        server.start();
        ch = new ClickHouseHttp("http://127.0.0.1:" + server.getAddress().getPort(), "u", "p",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        spool = spoolParent.newFolder("spool").toPath();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private ClickHouseWriter<String> writer() {
        return new ClickHouseWriter<>(ch, "app.events", 10_000, 100, 50, spool, 1 << 20, "node1",
                ClickHouseWriterTest::codec);
    }

    @Test
    public void batchesAreInsertedWithADedupToken() {
        ClickHouseWriter<String> w = writer();
        w.writeBatch(List.of("a", "b"));
        assertEquals(1, inserts.size());
        assertFalse(inserts.get(0).token == null || inserts.get(0).token.isBlank());
        assertEquals(2, inserts.get(0).body.lines().count());
        assertEquals(2, w.inserted());
    }

    @Test
    public void downServerSpoolsAndReplayKeepsTheSameToken() throws Exception {
        ClickHouseWriter<String> w = writer();
        down.set(true);
        w.writeBatch(List.of("a", "b", "c"));
        assertEquals(3, w.spooledRows());
        assertEquals(1, w.insertFailures());
        List<java.nio.file.Path> files;
        try (var s = java.nio.file.Files.list(spool)) {
            files = s.toList();
        }
        assertEquals(1, files.size());
        String name = files.get(0).getFileName().toString();
        String token = name.substring(name.indexOf('-') + 1, name.length() - ".ndjson".length());

        down.set(false);
        Thread.sleep(600);  // first backoff is 500 ms
        w.replaySpool(10);
        assertEquals(1, inserts.size());
        assertEquals(token, inserts.get(0).token);
        assertEquals(3, inserts.get(0).body.lines().count());
        assertEquals(0, w.spoolBytes());
        try (var s = java.nio.file.Files.list(spool)) {
            assertTrue(s.toList().isEmpty());
        }
    }

    @Test
    public void writerThreadDrainsTheQueueOnStop() {
        ClickHouseWriter<String> w = writer();
        try {
            w.start();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        for (int i = 0; i < 250; i++) {
            assertTrue(w.offer("r" + i));
        }
        w.stop(5_000);
        long rows = inserts.stream().mapToLong(i -> i.body.lines().count()).sum();
        assertEquals(250, rows);
        for (Insert i : inserts) {
            assertTrue(i.body.lines().count() <= 100);
        }
    }

    @Test
    public void spoolBudgetIsEnforcedAndCounted() throws Exception {
        ClickHouseWriter<String> w = new ClickHouseWriter<>(ch, "app.events", 10_000, 100, 50,
                spool, 10, "n", ClickHouseWriterTest::codec);
        java.nio.file.Files.createDirectories(spool);
        down.set(true);
        w.writeBatch(List.of("a"));
        assertEquals(1, w.droppedSpoolFull());
        assertEquals(0, w.spooledRows());
    }

    @Test
    public void queueFullIsCountedNotBlocking() {
        ClickHouseWriter<String> w = new ClickHouseWriter<>(ch, "app.events", 1_000, 100, 50,
                spool, 1 << 20, "n", ClickHouseWriterTest::codec);
        int accepted = 0;
        for (int i = 0; i < 1_005; i++) {
            if (w.offer("r" + i)) {
                accepted++;
            }
        }
        // queueCap floors at 1_000: 5 extra rows drop, never block.
        assertEquals(1_000, accepted);
        assertEquals(5, w.droppedQueueFull());
    }

    private static Map<String, String> query(java.net.URI uri) {
        String raw = uri.getRawQuery();
        Map<String, String> m = new ConcurrentHashMap<>();
        if (raw == null) {
            return m;
        }
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = java.net.URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }
}
