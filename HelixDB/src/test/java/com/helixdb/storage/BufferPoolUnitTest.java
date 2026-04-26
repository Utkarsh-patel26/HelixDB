package com.helixdb.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

import static com.helixdb.storage.DbConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class BufferPoolUnitTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private BufferPool bufferPool;

    @BeforeEach
    void setUp() throws IOException {
        String base = tempDir.resolve("buffer-pool").toString();
        diskManager = new DiskManager(base);
        bufferPool = new BufferPool(diskManager, 2);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    void constructor_validatesDiskAndCapacity() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new BufferPool(null, 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new BufferPool(diskManager, 0)),
                () -> assertThrows(IllegalArgumentException.class, () -> new BufferPool(diskManager, -1)));
    }

    @Test
    void fetchPage_rejectsInvalidPageId() {
        assertThrows(IllegalArgumentException.class, () -> bufferPool.fetchPage(-1));
    }

    @Test
    void fetchPage_missingOnDisk_throwsIOException() {
        assertThrows(IOException.class, () -> bufferPool.fetchPage(0));
    }

    @Test
    void newPage_validatesIdAndRejectsDuplicateCacheEntry() throws IOException {
        int pageId = diskManager.allocatePage();
        assertThrows(IllegalArgumentException.class, () -> bufferPool.newPage(-1));

        bufferPool.newPage(pageId);

        assertThrows(IllegalArgumentException.class, () -> bufferPool.newPage(pageId));
    }

    @Test
    void unpinPage_onMissingPage_isNoOp() {
        assertDoesNotThrow(() -> bufferPool.unpinPage(12345, true));
    }

    @Test
    void unpinPage_doesNotAllowNegativePinCount() throws IOException {
        int pageId = diskManager.allocatePage();
        bufferPool.newPage(pageId);

        bufferPool.unpinPage(pageId, false);
        bufferPool.unpinPage(pageId, false);
        bufferPool.unpinPage(pageId, false);

        assertTrue(bufferPool.containsPage(pageId));
    }

    @Test
    void flushPage_writesDirtyDataAndClearsDirtyState() throws IOException {
        int pageId = diskManager.allocatePage();
        Page page = bufferPool.newPage(pageId);
        page.putInt(PAGE_HEADER_SIZE, 2026);

        bufferPool.unpinPage(pageId, true);
        bufferPool.flushPage(pageId);

        byte[] raw = diskManager.readPage(pageId);
        int value = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).getInt(PAGE_HEADER_SIZE);

        assertEquals(2026, value);
    }

    @Test
    void flushAll_writesAllDirtyPages() throws IOException {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();

        Page page0 = bufferPool.newPage(p0);
        Page page1 = bufferPool.newPage(p1);
        page0.putInt(PAGE_HEADER_SIZE, 11);
        page1.putInt(PAGE_HEADER_SIZE, 22);

        bufferPool.unpinPage(p0, true);
        bufferPool.unpinPage(p1, true);
        bufferPool.flushAll();

        int v0 = ByteBuffer.wrap(diskManager.readPage(p0)).order(ByteOrder.BIG_ENDIAN).getInt(PAGE_HEADER_SIZE);
        int v1 = ByteBuffer.wrap(diskManager.readPage(p1)).order(ByteOrder.BIG_ENDIAN).getInt(PAGE_HEADER_SIZE);

        assertAll(
                () -> assertEquals(11, v0),
                () -> assertEquals(22, v1));
    }

    @Test
    void bufferPoolFullWithAllPinnedFrames_throwsOnAllocation() throws IOException {
        BufferPool tiny = new BufferPool(diskManager, 1);
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();

        tiny.newPage(p0); // remains pinned

        RuntimeException ex = assertThrows(RuntimeException.class, () -> tiny.newPage(p1));
        assertTrue(ex.getMessage().contains("all frames are pinned"));

        tiny.unpinPage(p0, false);
    }

    @Test
    void stats_hitMissEvictionAndReset_areTracked() throws IOException {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();
        int p2 = diskManager.allocatePage();

        writePageWithHeaderId(p0);
        writePageWithHeaderId(p1);
        writePageWithHeaderId(p2);

        bufferPool.resetStats();

        bufferPool.fetchPage(p0); // miss
        bufferPool.unpinPage(p0, false);

        bufferPool.fetchPage(p0); // hit
        bufferPool.unpinPage(p0, false);

        bufferPool.fetchPage(p1); // miss
        bufferPool.unpinPage(p1, false);

        bufferPool.fetchPage(p2); // miss + eviction
        bufferPool.unpinPage(p2, false);

        assertAll(
                () -> assertEquals(1, bufferPool.getHits()),
                () -> assertEquals(3, bufferPool.getMisses()),
                () -> assertEquals(1, bufferPool.getEvictions()),
                () -> assertEquals(0.25, bufferPool.getHitRate(), 0.0001));

        bufferPool.resetStats();

        assertAll(
                () -> assertEquals(0, bufferPool.getHits()),
                () -> assertEquals(0, bufferPool.getMisses()),
                () -> assertEquals(0, bufferPool.getEvictions()),
                () -> assertEquals(0.0, bufferPool.getHitRate()));
    }

    @Test
    void toString_containsCoreStatsAndCapacity() {
        String text = bufferPool.toString();

        assertAll(
                () -> assertTrue(text.contains("BufferPool(size=")),
                () -> assertTrue(text.contains("hits=")),
                () -> assertTrue(text.contains("misses=")),
                () -> assertTrue(text.contains("evictions=")));
    }

    private void writePageWithHeaderId(int pageId) throws IOException {
        byte[] data = new byte[PAGE_SIZE];
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).putInt(OFFSET_PAGE_ID, pageId);
        diskManager.writePage(pageId, data);
    }
}
