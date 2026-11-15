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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Batched store write path. The hot path only calls {@link #offer} (non-blocking); one
 * writer thread batches rows into {@code INSERT … FORMAT JSONEachRow}.
 *
 * <p>Row type {@code R} is app-defined (e.g. a CDR entity); the app supplies the NDJSON
 * serialisation via {@link BatchCodec}. This RA never sees app classes.</p>
 *
 * <p><b>No silent loss while ClickHouse is down.</b> A batch that cannot be inserted is written
 * to the spool directory as NDJSON (named by its deduplication token) and replayed, oldest
 * first, once ClickHouse answers again; the token makes a replay of an insert that did land
 * a no-op. Only two things drop a row, and both are counted: the in-memory queue being full
 * (the writer is behind by {@code queueCap} rows) and the spool exceeding its byte budget.</p>
 */
public final class ClickHouseWriter<R> {
    private static final Logger LOG = LogManager.getLogger(ClickHouseWriter.class);
    private static final String SPOOL_SUFFIX = ".ndjson";
    private static final long MAX_BACKOFF_MS = 30_000L;

    /** App-side NDJSON serialisation: one JSON object per row, LF-separated. */
    public interface BatchCodec<R> {
        byte[] encode(List<R> rows, String nodeId) throws IOException;
    }

    private final ClickHouseHttp ch;
    private final String table;
    private final ArrayBlockingQueue<R> queue;
    private final BatchCodec<R> codec;
    private final int batchSize;
    private final long flushMs;
    private final Path spoolDir;
    private final long spoolMaxBytes;
    private final String nodeId;

    private final LongAdder enqueued = new LongAdder();
    private final LongAdder inserted = new LongAdder();
    private final LongAdder droppedQueueFull = new LongAdder();
    private final LongAdder droppedSpoolFull = new LongAdder();
    private final LongAdder spooledRows = new LongAdder();
    private final LongAdder replayedBatches = new LongAdder();
    private final LongAdder insertFailures = new LongAdder();
    private final AtomicLong spoolBytes = new AtomicLong();
    private volatile String lastError = "";
    private volatile long lastInsertAtMs;
    private volatile boolean running;
    private volatile Thread worker;
    private long backoffUntilMs;
    private long backoffMs = 500L;

    public ClickHouseWriter(ClickHouseHttp ch, String qualifiedTable, int queueCap, int batchSize,
                            long flushMs, Path spoolDir, long spoolMaxBytes, String nodeId,
                            BatchCodec<R> codec) {
        if (codec == null) {
            throw new IllegalArgumentException("ClickHouseWriter codec is required");
        }
        this.ch = ch;
        this.table = qualifiedTable;
        this.queue = new ArrayBlockingQueue<>(Math.max(1_000, queueCap));
        this.codec = codec;
        this.batchSize = Math.max(1, batchSize);
        this.flushMs = Math.max(10L, flushMs);
        this.spoolDir = spoolDir;
        this.spoolMaxBytes = spoolMaxBytes;
        this.nodeId = nodeId == null ? "" : nodeId;
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        Files.createDirectories(spoolDir);
        spoolBytes.set(currentSpoolBytes());
        running = true;
        Thread t = Thread.ofPlatform().daemon().name("clickhouse-writer").unstarted(this::loop);
        worker = t;
        t.start();
        LOG.info("[clickhouse] writer started table={} batch={} flushMs={} queueCap={} spool={} ({} bytes pending)",
                table, batchSize, flushMs, queue.remainingCapacity(), spoolDir, spoolBytes.get());
    }

    /** Stop, flushing what is queued (to ClickHouse, else to the spool). */
    public void stop(long timeoutMs) {
        // No interrupt: it would abort an INSERT in flight (HttpClient throws on interrupt) and
        // spool a batch that was about to land. The poll returns within flushMs anyway.
        running = false;
        Thread t = worker;
        if (t != null) {
            try {
                t.join(Math.max(1L, timeoutMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ hot path

    /** Non-blocking. False (and counted) when the queue is full. */
    public boolean offer(R row) {
        if (row == null) {
            return false;
        }
        if (queue.offer(row)) {
            enqueued.increment();
            return true;
        }
        droppedQueueFull.increment();
        return false;
    }

    // ------------------------------------------------------------------ writer

    private void loop() {
        List<R> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                if (batch.isEmpty()) {
                    R first = running ? queue.poll(flushMs, TimeUnit.MILLISECONDS) : queue.poll();
                    if (first != null) {
                        batch.add(first);
                    }
                }
                queue.drainTo(batch, batchSize - batch.size());
                if (!batch.isEmpty()) {
                    writeBatch(batch);
                    batch.clear();
                }
                if (running) {
                    replaySpool(4);
                }
            } catch (InterruptedException e) {
                // stop(): fall through and drain what is left without waiting.
                running = false;
            } catch (RuntimeException e) {
                LOG.warn("[clickhouse] writer loop error: {}", e.toString());
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
        LOG.info("[clickhouse] writer stopped inserted={} spooledRows={} droppedQueueFull={}",
                inserted.sum(), spooledRows.sum(), droppedQueueFull.sum());
    }

    /** Insert one batch; on failure (or while backing off) spool it. */
    void writeBatch(List<R> batch) {
        byte[] ndjson;
        try {
            ndjson = codec.encode(batch, nodeId);
        } catch (IOException e) {
            LOG.warn("[clickhouse] cannot serialise {} rows: {}", batch.size(), e.toString());
            return;
        }
        String token = UUID.randomUUID().toString();
        if (System.currentTimeMillis() >= backoffUntilMs) {
            try {
                ch.insertJsonEachRow(table, ndjson, token);
                inserted.add(batch.size());
                lastInsertAtMs = System.currentTimeMillis();
                backoffMs = 500L;
                return;
            } catch (IOException e) {
                noteFailure(e);
            }
        }
        spool(ndjson, token, batch.size());
    }

    private void noteFailure(IOException e) {
        insertFailures.increment();
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (!msg.equals(lastError)) {
            LOG.warn("[clickhouse] insert failed, spooling until ClickHouse answers: {}", msg);
        }
        lastError = msg;
        backoffUntilMs = System.currentTimeMillis() + backoffMs;
        backoffMs = Math.min(MAX_BACKOFF_MS, backoffMs * 2);
    }

    private void spool(byte[] ndjson, String token, int rows) {
        if (spoolBytes.get() + ndjson.length > spoolMaxBytes) {
            droppedSpoolFull.add(rows);
            LOG.error("[clickhouse] spool full ({} bytes >= {}): {} rows not queued for ClickHouse",
                    spoolBytes.get(), spoolMaxBytes, rows);
            return;
        }
        Path tmp = spoolDir.resolve(token + ".tmp");
        Path dst = spoolDir.resolve(System.currentTimeMillis() + "-" + token + SPOOL_SUFFIX);
        try {
            Files.write(tmp, ndjson);
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE);
            spoolBytes.addAndGet(ndjson.length);
            spooledRows.add(rows);
        } catch (IOException e) {
            droppedSpoolFull.add(rows);
            LOG.error("[clickhouse] cannot spool {} rows to {}: {}", rows, spoolDir, e.toString());
        }
    }

    /** Replay up to {@code maxFiles} spooled batches, oldest first; stop at the first failure. */
    void replaySpool(int maxFiles) {
        if (spoolBytes.get() <= 0 || System.currentTimeMillis() < backoffUntilMs) {
            return;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(spoolDir, "*" + SPOOL_SUFFIX)) {
            ds.forEach(files::add);
        } catch (IOException e) {
            return;
        }
        files.sort(null);  // name starts with the spool time in ms → oldest first
        int done = 0;
        for (Path f : files) {
            if (done >= maxFiles) {
                break;
            }
            String name = f.getFileName().toString();
            String token = name.substring(name.indexOf('-') + 1, name.length() - SPOOL_SUFFIX.length());
            try {
                byte[] body = Files.readAllBytes(f);
                ch.insertJsonEachRow(table, body, token);
                Files.deleteIfExists(f);
                spoolBytes.addAndGet(-body.length);
                inserted.add(countLines(body));
                replayedBatches.increment();
                lastInsertAtMs = System.currentTimeMillis();
                backoffMs = 500L;
                done++;
            } catch (IOException e) {
                noteFailure(e);
                return;
            }
        }
    }

    private static long countLines(byte[] body) {
        long n = 0;
        for (byte b : body) {
            if (b == '\n') {
                n++;
            }
        }
        return n;
    }

    private long currentSpoolBytes() {
        long total = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(spoolDir, "*" + SPOOL_SUFFIX)) {
            for (Path p : ds) {
                total += Files.size(p);
            }
        } catch (IOException ignored) {
            // empty / unreadable spool → 0
        }
        return total;
    }

    // ------------------------------------------------------------------ metrics

    public int queueDepth() { return queue.size(); }
    public long enqueued() { return enqueued.sum(); }
    public long inserted() { return inserted.sum(); }
    public long droppedQueueFull() { return droppedQueueFull.sum(); }
    public long droppedSpoolFull() { return droppedSpoolFull.sum(); }
    public long spooledRows() { return spooledRows.sum(); }
    public long spoolBytes() { return spoolBytes.get(); }
    public long replayedBatches() { return replayedBatches.sum(); }
    public long insertFailures() { return insertFailures.sum(); }
    public String lastError() { return lastError; }
    public long lastInsertAtMs() { return lastInsertAtMs; }
}
