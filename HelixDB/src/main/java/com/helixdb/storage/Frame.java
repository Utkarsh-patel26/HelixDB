package com.helixdb.storage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a cached page frame in the buffer pool.
 * 
 * A Frame wraps a Page with metadata needed for cache management:
 * - dirty flag: whether the frame has been modified
 * - pinCount: how many threads currently hold this frame pinned
 * - lastUsedTime: timestamp for LRU eviction ordering
 * 
 * <h2>Thread Safety</h2>
 * Frames are NOT directly thread-safe. Synchronization must be done at
 * the buffer pool level. The pinCount uses atomic operations for convenience.
 * 
 * @see BufferPool for the cache management implementation
 * @see Page for the underlying page data
 */
public class Frame {
    /** The cached page data */
    public volatile Page page;

    /** Whether this frame has been modified since loading from disk */
    public volatile boolean dirty;

    /** Number of threads currently holding this frame pinned */
    public final AtomicInteger pinCount = new AtomicInteger(0);

    /** Timestamp of last access (nanoseconds), used for LRU ordering */
    public volatile long lastUsedTime;

    /**
     * Creates a new cache frame wrapping a page.
     * 
     * @param page the page to cache (must not be null)
     * @throws IllegalArgumentException if page is null
     */
    public Frame(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        this.page = page;
        this.dirty = false;
        this.lastUsedTime = System.nanoTime();
    }

    /** Checks if this frame is pinned by any thread */
    public boolean isPinned() {
        return pinCount.get() > 0;
    }

    /** Gets the number of pins */
    public int getPinCount() {
        return pinCount.get();
    }

    /** Gets the page ID for convenience */
    public int getPageId() {
        return page.getPageId();
    }

    /** Gets age in milliseconds since last access */
    public long getAgeMs() {
        return (System.nanoTime() - lastUsedTime) / 1_000_000;
    }

    @Override
    public String toString() {
        return String.format(
                "Frame(page=%d, dirty=%s, pins=%d, age=%dms)",
                page.getPageId(),
                dirty ? "Y" : "N",
                pinCount.get(),
                getAgeMs());
    }
}
