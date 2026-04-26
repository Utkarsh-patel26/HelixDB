package com.helixdb.storage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.helixdb.storage.DbConstants.*;

/**
 * Represents a single physical database page (4096 bytes).
 * 
 * A Page wraps a byte array and provides typed access to page data via
 * ByteBuffer.
 * It handles the page header (24 bytes) and provides helpers for byte-level
 * operations.
 * 
 * <h2>Layout</h2>
 * 
 * <pre>
 * [Header: 24 bytes][Slot Array grows down...][Free Space][...grows up Tuple Data]
 * </pre>
 * 
 * Pages are NOT thread-safe. Synchronization is done at the buffer pool level.
 * 
 * @see SlottedPage for tuple-level operations on pages
 * @see DbConstants for offset and size constants
 */
public class Page {
    private final byte[] data; // Raw page bytes
    private final ByteBuffer buf; // ByteBuffer wrapper for typed access
    private final int pageId; // Cached for performance

    /**
     * Creates a new empty page with the given ID.
     * 
     * @param pageId page ID (must be >= 0)
     * @throws IllegalArgumentException if pageId < 0
     */
    public Page(int pageId) {
        DbConstants.validatePageId(pageId);
        this.pageId = pageId;
        this.data = new byte[PAGE_SIZE];
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Initialize header
        buf.putInt(OFFSET_PAGE_ID, pageId);
        buf.putShort(OFFSET_TUPLE_DATA_TOP, (short) PAGE_SIZE);
        buf.putInt(OFFSET_NEXT_PAGE_ID, INVALID_PAGE_ID);
        buf.putLong(OFFSET_PAGE_LSN, 0L);
    }

    /**
     * Creates a Page from raw bytes read from disk.
     * 
     * @param rawBytes raw page data (must be exactly PAGE_SIZE bytes)
     * @throws IllegalArgumentException if rawBytes.length != PAGE_SIZE
     */
    public Page(byte[] rawBytes) {
        if (rawBytes == null) {
            throw new IllegalArgumentException("Raw bytes cannot be null");
        }
        if (rawBytes.length != PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid page size: " + rawBytes.length + " (expected " + PAGE_SIZE + ")");
        }
        this.data = rawBytes;
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        this.pageId = buf.getInt(OFFSET_PAGE_ID);
    }

    // ========== Header Field Accessors ==========

    /** Gets the page ID */
    public int getPageId() {
        return pageId;
    }

    /** Gets the number of slots (total, including deleted) */
    public int getNumSlots() {
        return Short.toUnsignedInt(buf.getShort(OFFSET_NUM_SLOTS));
    }

    /** Sets the number of slots */
    public void setNumSlots(int count) {
        if (count < 0 || count > MAX_SLOTS_PER_PAGE) {
            throw new IllegalArgumentException(
                    "Invalid slot count: " + count + " (max " + MAX_SLOTS_PER_PAGE + ")");
        }
        buf.putShort(OFFSET_NUM_SLOTS, (short) count);
    }

    /** Gets the number of live (non-deleted) slots */
    public int getNumLiveSlots() {
        return Short.toUnsignedInt(buf.getShort(OFFSET_NUM_LIVE_SLOTS));
    }

    /** Sets the number of live slots */
    public void setNumLiveSlots(int count) {
        if (count < 0 || count > getNumSlots()) {
            throw new IllegalArgumentException(
                    "Invalid live slot count: " + count + " (num slots: " + getNumSlots() + ")");
        }
        buf.putShort(OFFSET_NUM_LIVE_SLOTS, (short) count);
    }

    /** Gets the top of tuple data (where next tuple will be written from) */
    public int getTupleDataTop() {
        return Short.toUnsignedInt(buf.getShort(OFFSET_TUPLE_DATA_TOP));
    }

    /** Sets the top of tuple data */
    void setTupleDataTop(int offset) {
        if (offset < PAGE_HEADER_SIZE || offset > PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid tuple data top: " + offset +
                            " (must be between " + PAGE_HEADER_SIZE + " and " + PAGE_SIZE + ")");
        }
        buf.putShort(OFFSET_TUPLE_DATA_TOP, (short) offset);
    }

    /** Gets the next page ID (for linked list chains) */
    public int getNextPageId() {
        return buf.getInt(OFFSET_NEXT_PAGE_ID);
    }

    /** Sets the next page ID */
    public void setNextPageId(int pageId) {
        buf.putInt(OFFSET_NEXT_PAGE_ID, pageId);
    }

    /** Gets the page LSN (last WAL log sequence number) */
    public long getPageLsn() {
        return buf.getLong(OFFSET_PAGE_LSN);
    }

    /** Sets the page LSN */
    public void setPageLsn(long lsn) {
        buf.putLong(OFFSET_PAGE_LSN, lsn);
    }

    // ========== Generic Byte-Level I/O ==========

    /** Writes an int at the given offset */
    public void putInt(int offset, int value) {
        checkOffset(offset, 4);
        buf.putInt(offset, value);
    }

    /** Reads an int from the given offset */
    public int getInt(int offset) {
        checkOffset(offset, 4);
        return buf.getInt(offset);
    }

    /** Writes a short at the given offset */
    public void putShort(int offset, short value) {
        checkOffset(offset, 2);
        buf.putShort(offset, value);
    }

    /** Reads a short from the given offset */
    public short getShort(int offset) {
        checkOffset(offset, 2);
        return buf.getShort(offset);
    }

    /** Writes a long at the given offset */
    public void putLong(int offset, long value) {
        checkOffset(offset, 8);
        buf.putLong(offset, value);
    }

    /** Reads a long from the given offset */
    public long getLong(int offset) {
        checkOffset(offset, 8);
        return buf.getLong(offset);
    }

    /** Writes a byte at the given offset */
    public void putByte(int offset, byte value) {
        checkOffset(offset, 1);
        buf.put(offset, value);
    }

    /** Reads a byte from the given offset */
    public byte getByte(int offset) {
        checkOffset(offset, 1);
        return buf.get(offset);
    }

    /** Writes a byte array at the given offset */
    public void putBytes(int offset, byte[] src) {
        if (src == null) {
            throw new IllegalArgumentException("Source bytes cannot be null");
        }
        checkOffset(offset, src.length);
        System.arraycopy(src, 0, data, offset, src.length);
    }

    /** Reads a byte array of the given length from the given offset */
    public byte[] getBytes(int offset, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length cannot be negative");
        }
        checkOffset(offset, length);
        byte[] dst = new byte[length];
        System.arraycopy(data, offset, dst, 0, length);
        return dst;
    }

    // ========== Utilities ==========

    /** Validates that an offset + length don't exceed page size */
    private void checkOffset(int offset, int length) {
        if (offset < 0 || offset + length > PAGE_SIZE) {
            throw new IndexOutOfBoundsException(
                    "Access out of bounds: offset=" + offset +
                            ", length=" + length + ", page_size=" + PAGE_SIZE);
        }
    }

    /** Returns a reference to the raw page data */
    public byte[] getRawData() {
        return data;
    }

    /** Returns a deep copy of the page data (for WAL before/after images) */
    public byte[] copyRawData() {
        byte[] copy = new byte[PAGE_SIZE];
        System.arraycopy(data, 0, copy, 0, PAGE_SIZE);
        return copy;
    }

    @Override
    public String toString() {
        return String.format(
                "Page(id=%d, slots=%d, live=%d, tupleTop=%d, lsn=%d)",
                pageId, getNumSlots(), getNumLiveSlots(), getTupleDataTop(), getPageLsn());
    }
}
