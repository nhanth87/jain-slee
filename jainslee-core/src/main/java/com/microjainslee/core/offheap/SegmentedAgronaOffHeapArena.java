/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.core.offheap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agrona off-heap arena spanning multiple {@link AgronaOffHeapArena}
 * segments so total capacity can reach ~100M slots without a single
 * {@link java.nio.ByteBuffer} exceeding 2 GB.
 *
 * <p>Prop: {@code jainslee.offheap.segment-slots} (0 = auto from slot size).
 */
public final class SegmentedAgronaOffHeapArena implements OffHeapSlotArena {

    private static final Logger LOG = LogManager.getLogger(SegmentedAgronaOffHeapArena.class);

    public static final String PROP_SEGMENT_SLOTS = "jainslee.offheap.segment-slots";

    private final String name;
    private final OffHeapLayout layout;
    private final int maxSlots;
    private final int segmentSlots;
    private final List<AgronaOffHeapArena> segments = new ArrayList<>();
    /** entityId → segment index */
    private final Map<String, Integer> entityToSegment = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public SegmentedAgronaOffHeapArena(String name, OffHeapLayout layout, int maxSlots, int segmentSlots) {
        if (maxSlots < 1) {
            throw new IllegalArgumentException("maxSlots must be >= 1");
        }
        if (segmentSlots < 1) {
            throw new IllegalArgumentException("segmentSlots must be >= 1");
        }
        long segBytes = (long) layout.slotSize() * segmentSlots;
        if (segBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "segment capacity exceeds 2GB: slotSize=" + layout.slotSize()
                            + " × segmentSlots=" + segmentSlots);
        }
        this.name = name;
        this.layout = layout;
        this.maxSlots = maxSlots;
        this.segmentSlots = segmentSlots;
        int segmentCount = (maxSlots + segmentSlots - 1) / segmentSlots;
        for (int i = 0; i < segmentCount; i++) {
            int slots = Math.min(segmentSlots, maxSlots - i * segmentSlots);
            segments.add(new AgronaOffHeapArena(name + "#" + i, layout, slots));
        }
        LOG.info("[offheap-agrona-seg:{}] ready: maxSlots={} segmentSlots={} segments={} slotSize={}",
                name, maxSlots, segmentSlots, segments.size(), layout.slotSize());
    }

    public static int resolveSegmentSlots(int slotSize, int maxSlots) {
        String prop = System.getProperty(PROP_SEGMENT_SLOTS, "0");
        try {
            int configured = Integer.parseInt(prop);
            if (configured > 0) {
                return configured;
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        long maxBytes = (long) (Integer.MAX_VALUE * 0.75);
        int byBytes = (int) Math.max(1L, maxBytes / Math.max(1, slotSize));
        return Math.min(maxSlots, byBytes);
    }

    public static boolean needsSegmentation(int slotSize, int maxSlots) {
        String prop = System.getProperty(PROP_SEGMENT_SLOTS, "0");
        try {
            if (Integer.parseInt(prop) > 0) {
                return true;
            }
        } catch (NumberFormatException ignored) {
            // ignore
        }
        return (long) slotSize * (long) maxSlots > Integer.MAX_VALUE;
    }

    public void setSlotMovedListener(AgronaOffHeapArena.SlotMovedListener listener) {
        for (AgronaOffHeapArena seg : segments) {
            seg.setSlotMovedListener(listener);
        }
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public OffHeapLayout layout() {
        return layout;
    }

    @Override
    public int maxSlots() {
        return maxSlots;
    }

    @Override
    public synchronized long allocate(String entityId) {
        checkOpen();
        Integer existing = entityToSegment.get(entityId);
        if (existing != null) {
            return segments.get(existing).resolve(entityId);
        }
        for (int i = 0; i < segments.size(); i++) {
            AgronaOffHeapArena seg = segments.get(i);
            if (seg.occupiedCount() >= seg.maxSlots()) {
                continue;
            }
            try {
                long addr = seg.allocate(entityId);
                entityToSegment.put(entityId, i);
                return addr;
            } catch (IllegalStateException exhausted) {
                // race with another allocator — try next segment
            }
        }
        throw new IllegalStateException("[offheap-agrona-seg:" + name + "] exhausted ("
                + maxSlots + " slots)");
    }

    @Override
    public synchronized void free(String entityId) {
        Integer segIdx = entityToSegment.remove(entityId);
        if (segIdx == null) {
            return;
        }
        segments.get(segIdx).free(entityId);
    }

    @Override
    public long resolve(String entityId) {
        Integer segIdx = entityToSegment.get(entityId);
        if (segIdx == null) {
            return 0L;
        }
        return segments.get(segIdx).resolve(entityId);
    }

    @Override
    public int compact() {
        int moved = 0;
        for (AgronaOffHeapArena seg : segments) {
            moved += seg.compact();
        }
        return moved;
    }

    @Override
    public int occupiedCount() {
        return entityToSegment.size();
    }

    @Override
    public int freeListSize() {
        int n = 0;
        for (AgronaOffHeapArena seg : segments) {
            n += seg.freeListSize();
        }
        return n;
    }

    @Override
    public int highWaterMark() {
        int hwm = 0;
        for (AgronaOffHeapArena seg : segments) {
            hwm += seg.highWaterMark();
        }
        return hwm;
    }

    @Override
    public double fragmentationRatio() {
        int hwm = highWaterMark();
        return hwm == 0 ? 0.0 : (double) freeListSize() / hwm;
    }

    @Override
    public synchronized void close() {
        closed = true;
        entityToSegment.clear();
        for (AgronaOffHeapArena seg : segments) {
            seg.close();
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("[offheap-agrona-seg:" + name + "] closed");
        }
    }
}
