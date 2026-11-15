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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 3-port contract of the ClickHouse store RA: lifecycle starts/stops I/O, commands carry rows. */
public class ClickHouseRaEndpointTest {

    private HttpServer server;
    private final List<String> statements = new CopyOnWriteArrayList<>();

    @Rule
    public TemporaryFolder spoolParent = new TemporaryFolder();

    private Path spool;

    @Before
    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String q = ex.getRequestURI().getRawQuery() == null ? "" : java.net.URLDecoder.decode(
                    ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            statements.add(q.contains("INSERT INTO") ? "INSERT " + body.lines().count() : body.strip());
            byte[] resp = body.startsWith("SELECT engine_full")
                    ? "MergeTree TTL toDateTime(recorded_at) + toIntervalMonth(3)\n".getBytes() : new byte[0];
            ex.sendResponseHeaders(200, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) {
                ex.getResponseBody().write(resp);
            }
            ex.close();
        });
        server.start();
        spool = spoolParent.newFolder("spool").toPath();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private ClickHouseRaEndpoint<String> endpoint() {
        ClickHouseHttp ch = new ClickHouseHttp("http://127.0.0.1:" + server.getAddress().getPort(), "u", "p",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        ClickHouseWriter<String> w = new ClickHouseWriter<>(ch, "app.events", 10_000, 100, 20, spool,
                1 << 20, "n1",
                (rows, node) -> String.join("\n", rows).getBytes(StandardCharsets.UTF_8));
        return new ClickHouseRaEndpoint<>("app-store-ra", ch, w, c -> {
            c.execute("CREATE DATABASE IF NOT EXISTS app");
            c.execute("CREATE TABLE IF NOT EXISTS app.events (v String) ENGINE = Memory");
        });
    }

    @Test
    public void lifecycleCommandsAndSchema() throws Exception {
        ClickHouseRaEndpoint<String> ep = endpoint();
        assertEquals("app-store-ra", ep.getRaName());
        ep.activate(null);
        ep.activate(null);  // idempotent
        for (int i = 0; i < 7; i++) {
            ep.sendCommand(new ClickHouseCommand.Store<>("r" + i));
        }
        ep.sendCommand(new OutboundCommand() { });  // unknown command: ignored, never thrown
        for (int i = 0; i < 50 && !ep.isSchemaReady(); i++) {
            Thread.sleep(50);
        }
        assertTrue(ep.isSchemaReady());
        ep.deactivate();
        ep.deactivate();  // idempotent
        assertTrue(statements.stream().anyMatch(s -> s.startsWith("CREATE DATABASE IF NOT EXISTS app")));
        assertTrue(statements.stream().anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS app.events")));
        long inserted = statements.stream().filter(s -> s.startsWith("INSERT "))
                .mapToLong(s -> Long.parseLong(s.substring(7))).sum();
        assertEquals(7, inserted);
        assertEquals(7, ep.writer().inserted());
    }
}
