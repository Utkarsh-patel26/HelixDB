package com.helixdb.storage;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static com.helixdb.storage.DbConstants.*;

public class BufferPool {
    private final int capacity;
    private final DiskManager disk;
    // Plain HashMap so that metadata operations (unpin, flush) don't corrupt LRU order.
    // LRU is tracked via Frame.lastUsedTime, updated only on actual page accesses.
    private final HashMap<Integer, Frame> pool;

    private long hits;
    private long misses;
    private long evictions;

    public BufferPool(DiskManager disk, int capacity) {
        if (disk == null) {
            throw new IllegalArgumentException("DiskManager cannot be null");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be > 0");
        }

        this.capacity = capacity;
        this.disk = disk;
        this.hits = 0;
        this.misses = 0;
        this.evictions = 0;
        this.pool = new HashMap<>(capacity * 2, 0.75f);
    }

    public synchronized Page fetchPage(int pageId) throws IOException {
        DbConstants.validatePageId(pageId);

        Frame f = pool.get(pageId);
        if (f != null) {
            f.pinCount.incrementAndGet();
            f.lastUsedTime = System.nanoTime(); // record access for LRU
            hits++;
            return f.page;
        }

        misses++;
        if (pool.size() >= capacity) {
            evictOne();
        }

        byte[] raw = disk.readPage(pageId);
        Page page = new Page(raw);
        Frame nf = new Frame(page);
        nf.pinCount.set(1);
        nf.lastUsedTime = System.nanoTime();
        pool.put(pageId, nf);
        return page;
    }

    public synchronized Page newPage(int pageId) {
        DbConstants.validatePageId(pageId);

        if (pool.containsKey(pageId)) {
            throw new IllegalArgumentException("Page already cached");
        }

        if (pool.size() >= capacity) {
            try {
                evictOne();
            } catch (IOException e) {
                throw new RuntimeException("Failed to evict", e);
            }
        }

        Page page = new Page(pageId);
        Frame f = new Frame(page);
        f.pinCount.set(1);
        f.dirty = true;
        f.lastUsedTime = System.nanoTime();
        pool.put(pageId, f);
        return page;
    }

    public synchronized void unpinPage(int pageId, boolean isDirty) {
        Frame f = pool.get(pageId);
        if (f == null)
            return;
        if (isDirty)
            f.dirty = true;
        int newCount = f.pinCount.decrementAndGet();
        if (newCount < 0) {
            f.pinCount.set(0);
        }
        // intentionally NOT updating lastUsedTime — unpin is not a data access
    }

    public synchronized void flushPage(int pageId) throws IOException {
        Frame f = pool.get(pageId);
        if (f != null && f.dirty) {
            disk.writePage(pageId, f.page.getRawData());
            f.dirty = false;
        }
    }

    public synchronized void flushAll() throws IOException {
        for (Map.Entry<Integer, Frame> e : pool.entrySet()) {
            Frame f = e.getValue();
            if (f.dirty) {
                disk.writePage(e.getKey(), f.page.getRawData());
                f.dirty = false;
            }
        }
    }

    // Evicts the frame with the smallest lastUsedTime among unpinned frames (true LRU).
    private void evictOne() throws IOException {
        Frame victim = null;
        int victimKey = -1;
        for (Map.Entry<Integer, Frame> e : pool.entrySet()) {
            Frame f = e.getValue();
            if (f.pinCount.get() == 0) {
                if (victim == null || f.lastUsedTime < victim.lastUsedTime) {
                    victim = f;
                    victimKey = e.getKey();
                }
            }
        }
        if (victim == null) {
            throw new RuntimeException("Buffer pool full — all frames are pinned. Increase pool size.");
        }
        if (victim.dirty) {
            disk.writePage(victimKey, victim.page.getRawData());
        }
        pool.remove(victimKey);
        evictions++;
    }

    public synchronized boolean containsPage(int pageId) {
        return pool.containsKey(pageId);
    }

    public synchronized int size() {
        return pool.size();
    }

    public int getCapacity() {
        return capacity;
    }

    public synchronized long getHits() {
        return hits;
    }

    public synchronized long getMisses() {
        return misses;
    }

    public synchronized long getEvictions() {
        return evictions;
    }

    public synchronized double getHitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public synchronized void resetStats() {
        hits = 0;
        misses = 0;
        evictions = 0;
    }

    @Override
    public synchronized String toString() {
        return String.format(
                "BufferPool(size=%d/%d, hits=%d, misses=%d, evictions=%d)",
                size(), capacity, hits, misses, evictions);
    }
}