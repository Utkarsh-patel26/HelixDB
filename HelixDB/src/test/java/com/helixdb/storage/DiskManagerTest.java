package com.helixdb.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.helixdb.storage.DbConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class DiskManagerTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;

    @BeforeEach
    void setUp() throws IOException {
        String base = tempDir.resolve("disk-manager").toString();
        diskManager = new DiskManager(base);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (diskManager != null) {
            diskManager.close();
        }
    }

    @Test
    void constructor_rejectsNullOrEmptyName() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new DiskManager(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new DiskManager("")));
    }

    @Test
    void constructor_createsDbAndWalFiles() throws IOException {
        String base = tempDir.resolve("fresh-db").toString();
        try (DiskManager dm = new DiskManager(base)) {
            assertEquals(0, dm.getPageCount());
        }

        assertAll(
                () -> assertTrue(Files.exists(Path.of(base + DB_EXTENSION))),
                () -> assertTrue(Files.exists(Path.of(base + WAL_EXTENSION))));
    }

    @Test
    void allocatePage_returnsSequentialIdsAndGrowsFile() throws IOException {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();
        int p2 = diskManager.allocatePage();

        assertAll(
                () -> assertEquals(0, p0),
                () -> assertEquals(1, p1),
                () -> assertEquals(2, p2),
                () -> assertEquals(3, diskManager.getPageCount()),
                () -> assertEquals(3L * PAGE_SIZE, diskManager.getDatabaseFileSize()));
    }

    @Test
    void readPage_validatesInputAndMissingPageRange() {
        assertThrows(IllegalArgumentException.class, () -> diskManager.readPage(-1));
        assertThrows(IOException.class, () -> diskManager.readPage(0));
    }

    @Test
    void writePage_validatesInputArguments() throws IOException {
        int pageId = diskManager.allocatePage();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> diskManager.writePage(-1, new byte[PAGE_SIZE])),
                () -> assertThrows(IllegalArgumentException.class, () -> diskManager.writePage(pageId, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> diskManager.writePage(pageId, new byte[PAGE_SIZE - 1])),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> diskManager.writePage(pageId, new byte[PAGE_SIZE + 1])));
    }

    @Test
    void writeAndReadPage_roundTripsBytesExactly() throws IOException {
        int pageId = diskManager.allocatePage();

        byte[] data = new byte[PAGE_SIZE];
        ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                .putInt(OFFSET_PAGE_ID, pageId)
                .putInt(PAGE_HEADER_SIZE, 1234567)
                .putLong(PAGE_HEADER_SIZE + 4, 9876543210L);

        diskManager.writePage(pageId, data);
        byte[] read = diskManager.readPage(pageId);

        assertArrayEquals(data, read);
    }

    @Test
    void appendLogRecord_returnsMonotonicLsnsAndTracksWalSize() throws IOException {
        long lsn0 = diskManager.appendLogRecord(new byte[] { 10, 20, 30 });
        long lsn1 = diskManager.appendLogRecord(new byte[] { 40, 50 });

        assertAll(
                () -> assertEquals(0L, lsn0),
                () -> assertEquals(3L, lsn1),
                () -> assertEquals(5L, diskManager.getWalFileSize()));
    }

    @Test
    void appendLogRecord_rejectsNullInput() {
        assertThrows(IllegalArgumentException.class, () -> diskManager.appendLogRecord(null));
    }

    @Test
    void flushMethods_acceptValidOperations() throws IOException {
        int pageId = diskManager.allocatePage();
        byte[] page = new byte[PAGE_SIZE];
        ByteBuffer.wrap(page).order(ByteOrder.BIG_ENDIAN).putInt(OFFSET_PAGE_ID, pageId);

        diskManager.writePage(pageId, page);
        diskManager.appendLogRecord(new byte[] { 1, 2, 3 });

        assertAll(
                () -> assertDoesNotThrow(() -> diskManager.flushPage(pageId)),
                () -> assertDoesNotThrow(() -> diskManager.flushWal()),
                () -> assertDoesNotThrow(() -> diskManager.flushAll()));
    }

    @Test
    void reopen_existingDbRestoresAllocatedPageCount() throws IOException {
        String base = tempDir.resolve("reopen-db").toString();

        try (DiskManager dm = new DiskManager(base)) {
            dm.allocatePage();
            dm.allocatePage();
            dm.allocatePage();
        }

        try (DiskManager reopened = new DiskManager(base)) {
            assertEquals(3, reopened.getPageCount());
            assertEquals(3L * PAGE_SIZE, reopened.getDatabaseFileSize());
        }
    }

    @Test
    void toString_includesPageAndSizeSummary() {
        String text = diskManager.toString();

        assertAll(
                () -> assertTrue(text.contains("DiskManager(")),
                () -> assertTrue(text.contains("pages=")),
                () -> assertTrue(text.contains("dbSize=")),
                () -> assertTrue(text.contains("walSize=")));
    }
}
