package com.helixdb.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.helixdb.storage.DbConstants.*;
import static org.junit.jupiter.api.Assertions.*;

public class BufferPoolTest {
    private DiskManager diskManager;
    private BufferPool bufferPool;

    @TempDir
    Path tempDir;

    @BeforeEach
    public void setUp() throws IOException {
        String dbPath = tempDir.resolve("testdb").toString();
        diskManager = new DiskManager(dbPath);
        bufferPool = new BufferPool(diskManager, 3);
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (diskManager != null) {
            diskManager.close();
        }
    }

    // ─── DiskManager tests ────────────────────────────────────────────────────

    @Test
    public void diskManager_createsDbAndWalFiles() throws IOException {
        String base = tempDir.resolve("fresh").toString();
        try (DiskManager dm = new DiskManager(base)) {
            dm.allocatePage();
        }
        assertTrue(Files.exists(Path.of(base + DB_EXTENSION)), ".db file must exist");
        assertTrue(Files.exists(Path.of(base + WAL_EXTENSION)), ".wal file must exist");
    }

    @Test
    public void diskManager_allocatePage_returnsMonotonicallyIncreasingIds() throws IOException {
        int a = diskManager.allocatePage();
        int b = diskManager.allocatePage();
        int c = diskManager.allocatePage();
        assertTrue(a < b && b < c, "Page IDs must increase: " + a + " < " + b + " < " + c);
    }

    @Test
    public void diskManager_writeAndReadPage_roundTrips() throws IOException {
        int pageId = diskManager.allocatePage();
        byte[] written = new byte[PAGE_SIZE];
        for (int i = 0; i < PAGE_SIZE; i++) written[i] = (byte) (i % 127);
        diskManager.writePage(pageId, written);

        byte[] read = diskManager.readPage(pageId);
        assertArrayEquals(written, read, "Round-trip must be byte-for-byte identical");
    }

    // ─── SlottedPage tests ────────────────────────────────────────────────────

    @Test
    public void slottedPage_insertTuple_returnsConsecutiveSlotIds() {
        Page page = new Page(0);
        SlottedPage sp = new SlottedPage(page);

        int s0 = sp.insertTuple(new byte[]{1, 2, 3});
        int s1 = sp.insertTuple(new byte[]{4, 5, 6});
        int s2 = sp.insertTuple(new byte[]{7, 8, 9});

        assertEquals(0, s0);
        assertEquals(1, s1);
        assertEquals(2, s2);
    }

    @Test
    public void slottedPage_readTuple_returnsCorrectBytes() {
        Page page = new Page(0);
        SlottedPage sp = new SlottedPage(page);

        byte[] t0 = {10, 20, 30};
        byte[] t1 = {40, 50, 60};
        byte[] t2 = {70, 80, 90};
        sp.insertTuple(t0);
        sp.insertTuple(t1);
        sp.insertTuple(t2);

        assertArrayEquals(t0, sp.readTuple(0), "Slot 0 data mismatch");
        assertArrayEquals(t1, sp.readTuple(1), "Slot 1 data mismatch");
        assertArrayEquals(t2, sp.readTuple(2), "Slot 2 data mismatch");
    }

    @Test
    public void slottedPage_deleteTuple_causeReadToReturnNull() {
        Page page = new Page(0);
        SlottedPage sp = new SlottedPage(page);

        sp.insertTuple(new byte[]{1, 2, 3});
        sp.insertTuple(new byte[]{4, 5, 6});
        sp.insertTuple(new byte[]{7, 8, 9});

        sp.deleteTuple(1);

        assertNotNull(sp.readTuple(0), "Slot 0 must still be alive");
        assertNull(sp.readTuple(1),    "Slot 1 must return null after deletion");
        assertNotNull(sp.readTuple(2), "Slot 2 must still be alive");
        assertEquals(2, sp.getNumLiveSlots(), "Live slot count must drop to 2");
        assertEquals(3, sp.getNumSlots(),     "Total slot count stays 3");
    }

    // ─── BufferPool tests ─────────────────────────────────────────────────────

    @Test
    public void bufferPool_fetchAndStore_pageRoundTrips() throws IOException {
        int pageId = diskManager.allocatePage();
        Page page = bufferPool.newPage(pageId);

        page.putInt(PAGE_HEADER_SIZE, 12345);
        bufferPool.unpinPage(pageId, true);
        bufferPool.flushPage(pageId);

        Page page2 = bufferPool.fetchPage(pageId);
        assertEquals(12345, page2.getInt(PAGE_HEADER_SIZE));
        bufferPool.unpinPage(pageId, false);
    }

    @Test
    public void bufferPool_dirtyPageFlushedOnEviction() throws IOException {
        // Pool size = 3. Fill it with pages 0-2 (all dirty), then force eviction of 0.
        int[] ids = new int[4];
        for (int i = 0; i < 4; i++) ids[i] = diskManager.allocatePage();

        // Insert sentinel value into page 0 and unpin it (makes it evictable)
        Page p0 = bufferPool.newPage(ids[0]);
        p0.putInt(PAGE_HEADER_SIZE, 99999);
        bufferPool.unpinPage(ids[0], true);

        // Fill remaining slots — evicts p0 because it's LRU and unpinned
        bufferPool.newPage(ids[1]);
        bufferPool.unpinPage(ids[1], false);
        bufferPool.newPage(ids[2]);
        bufferPool.unpinPage(ids[2], false);
        bufferPool.newPage(ids[3]);   // triggers eviction of ids[0]
        bufferPool.unpinPage(ids[3], false);

        assertFalse(bufferPool.containsPage(ids[0]), "Evicted page must not be in pool");

        // Now fetch it fresh from disk — sentinel must be there
        Page reloaded = bufferPool.fetchPage(ids[0]);
        assertEquals(99999, reloaded.getInt(PAGE_HEADER_SIZE),
                "Dirty page must have been flushed to disk before eviction");
        bufferPool.unpinPage(ids[0], false);
    }

    @Test
    public void bufferPool_pinnedPageNeverEvicted() throws IOException {
        // Pool size = 3. Pin page 0, fill the remaining 2 slots, then try to add a 4th.
        int[] ids = new int[4];
        for (int i = 0; i < 4; i++) ids[i] = diskManager.allocatePage();

        bufferPool.newPage(ids[0]);
        // ids[0] stays PINNED (pinCount = 1, never unpinned)

        bufferPool.newPage(ids[1]);
        bufferPool.unpinPage(ids[1], false);

        bufferPool.newPage(ids[2]);
        bufferPool.unpinPage(ids[2], false);

        // Fetching ids[3] must evict ids[1] or ids[2] — not the pinned ids[0]
        bufferPool.newPage(ids[3]);
        bufferPool.unpinPage(ids[3], false);

        assertTrue(bufferPool.containsPage(ids[0]),
                "Pinned page must never be evicted");

        bufferPool.unpinPage(ids[0], false); // cleanup
    }

    @Test
    public void bufferPool_lruOrder_evictsLeastRecentlyUsedFirst() throws IOException {
        // Pool size = 3. Load A, B, C. Access A again (makes C the LRU). Expect C evicted.
        int[] ids = new int[4];
        for (int i = 0; i < 4; i++) ids[i] = diskManager.allocatePage();

        // Write pages to disk first so fetchPage can load them
        for (int id : ids) {
            byte[] data = new byte[PAGE_SIZE];
            java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(0, id);
            diskManager.writePage(id, data);
        }

        // Load A(0), B(1), C(2) in order — C is LRU candidate
        bufferPool.fetchPage(ids[0]); // A
        bufferPool.fetchPage(ids[1]); // B
        bufferPool.fetchPage(ids[2]); // C  (LRU at this point)

        // Access A again — now B is second-oldest, C stays LRU
        bufferPool.fetchPage(ids[0]); // A touched again

        // Unpin all so any can be evicted
        bufferPool.unpinPage(ids[0], false);
        bufferPool.unpinPage(ids[0], false); // second pin from re-fetch
        bufferPool.unpinPage(ids[1], false);
        bufferPool.unpinPage(ids[2], false);

        // Fetch D — pool is full, must evict LRU which should be C(ids[2]) or B(ids[1])
        // but NOT A which was accessed most recently
        bufferPool.fetchPage(ids[3]);
        bufferPool.unpinPage(ids[3], false);

        assertTrue(bufferPool.containsPage(ids[0]),
                "Most recently accessed page A must still be in pool");
    }

    @Test
    public void bufferPool_hitMissStats_trackedCorrectly() throws IOException {
        int pageId = diskManager.allocatePage();
        byte[] data = new byte[PAGE_SIZE];
        java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(0, pageId);
        diskManager.writePage(pageId, data);

        bufferPool.resetStats();

        bufferPool.fetchPage(pageId);           // miss (first load)
        bufferPool.unpinPage(pageId, false);
        bufferPool.fetchPage(pageId);           // hit (already cached)
        bufferPool.unpinPage(pageId, false);

        assertEquals(1, bufferPool.getMisses());
        assertEquals(1, bufferPool.getHits());
        assertEquals(0.5, bufferPool.getHitRate(), 0.001);
    }
}
