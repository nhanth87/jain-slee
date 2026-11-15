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
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.RaEndpointPort;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * ClickHouse store RA — the only place that talks to ClickHouse on the write path.
 *
 * <p>3-port contract (micro-jainslee README § "How RAs work"):</p>
 * <ol>
 *   <li><b>Lifecycle</b> ({@link RaEndpointPort}) — {@link #activate} starts the batching writer and
 *       the schema bootstrap; {@link #deactivate} flushes (to ClickHouse, else to the spool).</li>
 *   <li><b>Commands</b> ({@link RaCommandPort}) — {@link ClickHouseCommand.Store}: non-blocking
 *       hand-off from the service the SBBs call. SBBs never open the socket.</li>
 *   <li><b>Events</b> ({@link RaBootstrapPort}) — held per contract; the store raises no SLEE
 *       events today (its health is on the app's status endpoint).</li>
 * </ol>
 *
 * <p>No business logic here: what a row contains and how reads roll up stay in the app.
 * The app supplies the schema bootstrap (DDL) and names the RA instance; the CDR store
 * convention is {@code cdr-clickhouse-ra}.</p>
 *
 * @param <R> app row type, carried from command to writer untouched
 */
public final class ClickHouseRaEndpoint<R> implements RaEndpointPort, RaCommandPort {
    private static final Logger LOG = LogManager.getLogger(ClickHouseRaEndpoint.class);

    /** App DDL bootstrap: runs until it stops throwing; the RA retries with backoff. */
    public interface SchemaBootstrap {
        void ensureReady(ClickHouseHttp ch) throws IOException;
    }

    private final String raName;
    private final ClickHouseHttp ch;
    private final SchemaBootstrap schema;
    private final ClickHouseWriter<R> writer;

    private volatile RaBootstrapPort bootstrap;
    private volatile boolean active;
    private volatile boolean schemaReady;
    private volatile String schemaError = "";

    public ClickHouseRaEndpoint(String raName, ClickHouseHttp ch,
                                ClickHouseWriter<R> writer, SchemaBootstrap schema) {
        if (raName == null || raName.isBlank()) {
            throw new IllegalArgumentException("ClickHouse RA name is required");
        }
        if (schema == null) {
            throw new IllegalArgumentException("ClickHouse schema bootstrap is required");
        }
        this.raName = raName.trim();
        this.ch = ch;
        this.writer = writer;
        this.schema = schema;
    }

    @Override
    public String getRaName() {
        return raName;
    }

    // ---------------------------------------------------------------- port 1: lifecycle

    @Override
    public synchronized void activate(RaBootstrapPort bootstrap) {
        if (active) {
            return;
        }
        this.bootstrap = bootstrap;
        try {
            writer.start();
        } catch (IOException e) {
            throw new IllegalStateException(raName + ": cannot create the spool directory: " + e, e);
        }
        active = true;
        Thread.ofPlatform().daemon().name("clickhouse-schema").start(this::ensureSchema);
        LOG.info("{} activated", raName);
    }

    @Override
    public synchronized void deactivate() {
        if (!active) {
            return;
        }
        active = false;
        writer.stop(10_000L);
        LOG.info("{} deactivated", raName);
    }

    private void ensureSchema() {
        long delay = 1_000L;
        while (active) {
            try {
                schema.ensureReady(ch);
                schemaReady = true;
                schemaError = "";
                LOG.info("[clickhouse] schema ready");
                return;
            } catch (IOException | RuntimeException e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (!msg.equals(schemaError)) {
                    LOG.warn("[clickhouse] schema not ready ({}), retrying — rows are spooled meanwhile", msg);
                }
                schemaError = msg;
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delay = Math.min(30_000L, delay * 2);
            }
        }
    }

    // ---------------------------------------------------------------- port 2: commands

    @Override
    @SuppressWarnings("unchecked")
    public void sendCommand(OutboundCommand command) {
        if (command instanceof ClickHouseCommand.Store<?> s) {
            // The container routes commands by RA name, so the row type is the app's own R.
            writer.offer((R) s.row());
        } else if (command != null) {
            LOG.debug("{} ignoring unknown command {}", raName, command.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- state (admin plane)

    public boolean isActive() { return active; }
    public boolean isSchemaReady() { return schemaReady; }
    public String schemaError() { return schemaError; }
    public ClickHouseWriter<R> writer() { return writer; }
    RaBootstrapPort bootstrapPort() { return bootstrap; }
}
