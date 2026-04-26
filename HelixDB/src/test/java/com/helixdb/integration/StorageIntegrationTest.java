package com.helixdb.integration;

import com.helixdb.storage.BufferPool;
import com.helixdb.storage.DiskManager;
import com.helixdb.storage.Page;
import com.helixdb.storage.SlottedPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static com.helixdb.storage.DbConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void tupleLifecycle_persistsAcrossFlushAndRestart() throws IOException {
        String base = tempDir.resolve("lifecycle-db").toString();
        int pageId;
        int slotAlpha;
        int slotBeta;

        try (DiskManager disk = new DiskManager(base)) {
            BufferPool pool = new BufferPool(disk, 2);

            pageId = disk.allocatePage();
            Page page = pool.newPage(pageId);
            SlottedPage slotted = new SlottedPage(page);

            slotAlpha = slotted.insertTuple("alpha".getBytes(StandardCharsets.UTF_8));
            slotBeta = slotted.insertTuple("beta!".getBytes(StandardCharsets.UTF_8));

            slotted.updateTuple(slotAlpha, "ALPHA".getBytes(StandardCharsets.UTF_8));
            slotted.deleteTuple(slotBeta);

            page.setNextPageId(777);
            page.setPageLsn(123456L);

            pool.unpinPage(pageId, true);
            pool.flushAll();
            disk.flushAll();
        }

        try (DiskManager disk = new DiskManager(base)) {
            BufferPool pool = new BufferPool(disk, 2);

            Page restoredPage = pool.fetchPage(pageId);
            SlottedPage restored = new SlottedPage(restoredPage);

            assertAll(
                    () -> assertArrayEquals("ALPHA".getBytes(StandardCharsets.UTF_8), restored.readTuple(slotAlpha)),
                    () -> assertNull(restored.readTuple(slotBeta)),
                    () -> assertEquals(2, restored.getNumSlots()),
                    () -> assertEquals(1, restored.getNumLiveSlots()),
                    () -> assertEquals(777, restoredPage.getNextPageId()),
                    () -> assertEquals(123456L, restoredPage.getPageLsn()));

            pool.unpinPage(pageId, false);
        }
    }

    @Test
    void dirtyPage_isFlushedAutomaticallyWhenEvicted() throws IOException {
        String base = tempDir.resolve("eviction-db").toString();

        try (DiskManager disk = new DiskManager(base)) {
            BufferPool pool = new BufferPool(disk, 1);

            int pageA = disk.allocatePage();
            int pageB = disk.allocatePage();

            Page a = pool.newPage(pageA);
            SlottedPage slotted = new SlottedPage(a);
            int slot = slotted.insertTuple("persist-me".getBytes(StandardCharsets.UTF_8));
            pool.unpinPage(pageA, true);

            pool.newPage(pageB); // evicts pageA and must flush it first
            pool.unpinPage(pageB, false);

            assertFalse(pool.containsPage(pageA));

            Page restoredA = pool.fetchPage(pageA);
            SlottedPage restored = new SlottedPage(restoredA);
            assertArrayEquals("persist-me".getBytes(StandardCharsets.UTF_8), restored.readTuple(slot));
            pool.unpinPage(pageA, false);
        }
    }

    @Test
    void walAndDataFiles_remainConsistentAfterReopen() throws IOException {
        String base = tempDir.resolve("wal-db").toString();

        long lsn0;
        long lsn1;
        int pageId;

        try (DiskManager disk = new DiskManager(base)) {
            lsn0 = disk.appendLogRecord(new byte[] { 1, 2, 3, 4 });
            lsn1 = disk.appendLogRecord(new byte[] { 5, 6 });
            disk.flushWal();

            pageId = disk.allocatePage();
            byte[] data = new byte[PAGE_SIZE];
            ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                    .putInt(OFFSET_PAGE_ID, pageId)
                    .putInt(PAGE_HEADER_SIZE, 424242);
            disk.writePage(pageId, data);
            disk.flushAll();
        }

        try (DiskManager reopened = new DiskManager(base)) {
            byte[] read = reopened.readPage(pageId);
            int value = ByteBuffer.wrap(read).order(ByteOrder.BIG_ENDIAN).getInt(PAGE_HEADER_SIZE);

            assertAll(
                    () -> assertEquals(0L, lsn0),
                    () -> assertEquals(4L, lsn1),
                    () -> assertEquals(6L, reopened.getWalFileSize()),
                    () -> assertEquals(1, reopened.getPageCount()),
                    () -> assertEquals(424242, value));
        }
    }
}
