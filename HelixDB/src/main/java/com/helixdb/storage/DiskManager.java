package com.helixdb.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.atomic.AtomicInteger;

import static com.helixdb.storage.DbConstants.*;

/**
 * Manages all disk I/O operations for the database.
 * 
 * DiskManager handles:
 * - Main database file (.db) for table data
 * - Write-ahead log file (.wal) for durability
 * - Page allocation and sequential I/O
 * - Fsync coordination for crash recovery
 * 
 * <h2>Thread Safety</h2>
 * DiskManager is fully thread-safe. Multiple threads can read/write different
 * pages
 * concurrently as long as they're coordinated by the buffer pool.
 * 
 * <h2>File Layout</h2>
 * Pages are stored at fixed byte offsets in the .db file:
 * - Page 0 at byte offset 0
 * - Page 1 at byte offset 4096
 * - Page N at byte offset N × 4096
 * 
 * @see Page for page structure
 * @see BufferPool for caching strategy
 */
public class DiskManager implements Closeable {
    private final RandomAccessFile dbFile;
    private final FileChannel dbChannel;
    private final RandomAccessFile walFile;
    private final FileChannel walChannel;
    private final AtomicInteger nextPageId; // Thread-safe page counter

    /**
     * Opens or creates the database files.
     * 
     * @param dbName base name for the database (without extension)
     * @throws IOException              if files cannot be opened/created
     * @throws IllegalArgumentException if dbName is null or empty
     */
    public DiskManager(String dbName) throws IOException {
        if (dbName == null || dbName.isEmpty()) {
            throw new IllegalArgumentException("dbName cannot be null or empty");
        }

        String dbPath = dbName + DB_EXTENSION;
        String walPath = dbName + WAL_EXTENSION;

        RandomAccessFile tmpDbFile = null;
        FileChannel tmpDbChannel = null;
        RandomAccessFile tmpWalFile = null;
        FileChannel tmpWalChannel = null;

        try {
            tmpDbFile = new RandomAccessFile(dbPath, "rw");
            tmpDbChannel = tmpDbFile.getChannel();
            tmpWalFile = new RandomAccessFile(walPath, "rw");
            tmpWalChannel = tmpWalFile.getChannel();
        } catch (IOException e) {
            // Clean up if one succeeds but the other fails
            if (tmpDbChannel != null && tmpDbChannel.isOpen()) {
                try {
                    tmpDbChannel.close();
                } catch (IOException ex) {
                    /* ignore */ }
            }
            if (tmpDbFile != null) {
                try {
                    tmpDbFile.close();
                } catch (IOException ex) {
                    /* ignore */ }
            }
            if (tmpWalChannel != null && tmpWalChannel.isOpen()) {
                try {
                    tmpWalChannel.close();
                } catch (IOException ex) {
                    /* ignore */ }
            }
            if (tmpWalFile != null) {
                try {
                    tmpWalFile.close();
                } catch (IOException ex) {
                    /* ignore */ }
            }
            throw new IOException("Failed to open database files at " + dbName, e);
        }

        this.dbFile = tmpDbFile;
        this.dbChannel = tmpDbChannel;
        this.walFile = tmpWalFile;
        this.walChannel = tmpWalChannel;

        // Calculate existing page count from file size
        long dbSize = dbFile.length();
        if (dbSize % PAGE_SIZE != 0) {
            throw new IOException(
                    "Database file size (" + dbSize + ") is not a multiple of PAGE_SIZE (" + PAGE_SIZE + ")");
        }
        int existingPages = (int) (dbSize / PAGE_SIZE);
        nextPageId = new AtomicInteger(existingPages);
    }

    /**
     * Allocates a new page ID and extends the database file.
     * The allocated page is zero-filled.
     * 
     * @return the ID of the newly allocated page
     * @throws IOException if write fails
     */
    public int allocatePage() throws IOException {
        int pageId = nextPageId.getAndIncrement();
        long pos = (long) pageId * PAGE_SIZE;

        // Write zero-filled page to extend the file
        synchronized (dbChannel) {
            dbChannel.position(pos);
            dbChannel.write(ByteBuffer.wrap(new byte[PAGE_SIZE]));
        }

        return pageId;
    }

    /**
     * Reads a page from disk.
     * 
     * @param pageId page ID to read (must be >= 0)
     * @return raw page bytes (exactly PAGE_SIZE)
     * @throws IOException              if page doesn't exist or read fails
     * @throws IllegalArgumentException if pageId < 0
     */
    public byte[] readPage(int pageId) throws IOException {
        DbConstants.validatePageId(pageId);

        long pos = (long) pageId * PAGE_SIZE;
        long fileSize = dbFile.length();

        if (pos + PAGE_SIZE > fileSize) {
            throw new IOException(
                    "Page " + pageId + " does not exist (file size: " + fileSize +
                            ", position: " + pos + ")");
        }

        byte[] buf = new byte[PAGE_SIZE];
        ByteBuffer wrap = ByteBuffer.wrap(buf);

        synchronized (dbChannel) {
            int bytesRead = dbChannel.read(wrap, pos);
            if (bytesRead < PAGE_SIZE) {
                throw new IOException(
                        "Incomplete page read: expected " + PAGE_SIZE + ", got " + bytesRead);
            }
        }

        return buf;
    }

    /**
     * Writes a page to disk (not necessarily durable until flushPage is called).
     * 
     * @param pageId page ID to write (must be >= 0)
     * @param data   raw page data (must be exactly PAGE_SIZE bytes)
     * @throws IOException              if write fails
     * @throws IllegalArgumentException if pageId < 0 or data size is invalid
     */
    public void writePage(int pageId, byte[] data) throws IOException {
        DbConstants.validatePageId(pageId);

        if (data == null) {
            throw new IllegalArgumentException("Page data cannot be null");
        }
        if (data.length != PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid page data size: " + data.length + " (expected " + PAGE_SIZE + ")");
        }

        long pos = (long) pageId * PAGE_SIZE;

        synchronized (dbChannel) {
            dbChannel.write(ByteBuffer.wrap(data), pos);
        }
    }

    /**
     * Appends a log record to the WAL file.
     * Returns the LSN (log sequence number) = byte offset of the record.
     * 
     * @param record log record bytes (must not be null)
     * @return LSN (byte offset in WAL file)
     * @throws IOException              if write fails
     * @throws IllegalArgumentException if record is null
     */
    public long appendLogRecord(byte[] record) throws IOException {
        if (record == null) {
            throw new IllegalArgumentException("Log record cannot be null");
        }

        long lsn;
        synchronized (walChannel) {
            lsn = walChannel.size();
            walChannel.write(ByteBuffer.wrap(record), lsn);
        }

        return lsn;
    }

    /**
     * Forces the WAL file to durable storage.
     * MUST be called before transaction commit returns.
     * 
     * @throws IOException if fsync fails
     */
    public void flushWal() throws IOException {
        synchronized (walChannel) {
            walChannel.force(true); // true = also sync file metadata
        }
    }

    /**
     * Forces a data page to durable storage.
     * Note: This forces the entire database channel; production systems
     * would use per-page fsync or io_uring for better granularity.
     * 
     * @param pageId page ID (for documentation)
     * @throws IOException              if fsync fails
     * @throws IllegalArgumentException if pageId < 0
     */
    public void flushPage(int pageId) throws IOException {
        DbConstants.validatePageId(pageId);
        synchronized (dbChannel) {
            dbChannel.force(false); // false = don't sync metadata
        }
    }

    /**
     * Forces all data pages and metadata to durable storage.
     * 
     * @throws IOException if fsync fails
     */
    public void flushAll() throws IOException {
        synchronized (dbChannel) {
            dbChannel.force(true);
        }
        synchronized (walChannel) {
            walChannel.force(true);
        }
    }

    /** Gets the current page count */
    public int getPageCount() {
        return nextPageId.get();
    }

    /** Gets the total size of the database file in bytes */
    public long getDatabaseFileSize() throws IOException {
        return dbFile.length();
    }

    /** Gets the total size of the WAL file in bytes */
    public long getWalFileSize() throws IOException {
        return walFile.length();
    }

    @Override
    public void close() throws IOException {
        synchronized (dbChannel) {
            dbChannel.force(true);
            dbFile.close();
        }

        synchronized (walChannel) {
            walChannel.force(true);
            walFile.close();
        }
    }

    @Override
    public String toString() {
        try {
            return String.format(
                    "DiskManager(pages=%d, dbSize=%dKB, walSize=%dKB)",
                    nextPageId.get(),
                    getDatabaseFileSize() / 1024,
                    getWalFileSize() / 1024);
        } catch (IOException e) {
            return "DiskManager(error: " + e.getMessage() + ")";
        }
    }
}
