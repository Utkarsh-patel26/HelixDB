package com.helixdb.storage;

/**
 * Global constants for JavaDB storage engine.
 * 
 * These constants define fundamental storage layout, sizes, and limits.
 * Changes to these values require database migration.
 * 
 * <h2>Page Layout</h2>
 * 
 * <pre>
 * Byte:    0                    24                          PAGE_SIZE
 *          |     HEADER          |  SLOTS (grow →)  FREE  TUPLES (← grow)
 *          |PageId|Slots|Live|Ptr|Offset Len|...  SPACE  [TupleN]...[Tuple0]
 * </pre>
 * 
 * - Header: 24 bytes with page metadata
 * - Slot Array: grows downward from header, each slot is 4 bytes (offset +
 * length)
 * - Tuple Data: grows upward from page end, variable-length tuples
 * - Free Space: gap between slot array end and tuple data start
 */
public final class DbConstants {
    private DbConstants() {
    }

    // ========== Version & Format ==========
    /** Database format version for compatibility checking */
    public static final int DB_FORMAT_VERSION = 1;

    // ========== Page Size & Capacity ==========
    /**
     * Physical page size in bytes. Must match OS virtual memory page size (4096).
     * Changing this invalidates all existing database files.
     */
    public static final int PAGE_SIZE = 4096;

    /**
     * Buffer pool capacity: number of pages to cache in memory.
     * 1000 pages × 4096 bytes = ~4 MB RAM cache.
     */
    public static final int BUFFER_POOL_SIZE = 1000;

    /**
     * B+ tree order: maximum keys per node = 2×ORDER.
     * ORDER=100 allows up to 200 keys per internal node.
     */
    public static final int BTREE_ORDER = 100;

    // ========== Page Header Offsets (24 bytes total) ==========
    /** Offset: Page ID (4 bytes, big-endian int) */
    public static final int OFFSET_PAGE_ID = 0;

    /** Offset: Number of slots in page (2 bytes, unsigned short) */
    public static final int OFFSET_NUM_SLOTS = 4;

    /** Offset: Number of live (non-deleted) slots (2 bytes, unsigned short) */
    public static final int OFFSET_NUM_LIVE_SLOTS = 6;

    /** Offset: Tuple data top - where next tuple will be written (2 bytes) */
    public static final int OFFSET_TUPLE_DATA_TOP = 8;

    /** Offset: Next page ID for linked list chains (4 bytes, big-endian int) */
    public static final int OFFSET_NEXT_PAGE_ID = 12;

    /**
     * Offset: Page LSN - last WAL log sequence number (8 bytes, big-endian long)
     */
    public static final int OFFSET_PAGE_LSN = 16;

    /** Total header size in bytes */
    public static final int PAGE_HEADER_SIZE = 24;

    // ========== Slot Entry Structure ==========
    /** Size of each slot entry: 2 bytes (offset) + 2 bytes (length) */
    public static final int SLOT_ENTRY_SIZE = 4;

    /** Offset of offset field within a slot entry */
    public static final int SLOT_OFFSET_FIELD = 0;

    /** Offset of length field within a slot entry */
    public static final int SLOT_LENGTH_FIELD = 2;

    // ========== Sentinel Values ==========
    /** Indicates an invalid/unallocated page ID */
    public static final int INVALID_PAGE_ID = -1;

    /** Indicates an invalid/unallocated slot ID */
    public static final int INVALID_SLOT_ID = -1;

    /** Tombstone offset value for deleted slots */
    public static final short TOMBSTONE_OFFSET = 0;

    /** Tombstone length value for deleted slots */
    public static final short TOMBSTONE_LENGTH = 0;

    // ========== File Extensions ==========
    /** Extension for main database files */
    public static final String DB_EXTENSION = ".db";

    /** Extension for write-ahead log files */
    public static final String WAL_EXTENSION = ".wal";

    // ========== Limits & Constraints ==========
    /** Minimum tuple size: 1 byte */
    public static final int MIN_TUPLE_SIZE = 1;

    /** Maximum tuple size = PAGE_SIZE - PAGE_HEADER_SIZE - SLOT_ENTRY_SIZE */
    public static final int MAX_TUPLE_SIZE = PAGE_SIZE - PAGE_HEADER_SIZE - SLOT_ENTRY_SIZE;

    /** Maximum number of slots per page */
    public static final int MAX_SLOTS_PER_PAGE = (PAGE_SIZE - PAGE_HEADER_SIZE) / SLOT_ENTRY_SIZE;

    // ========== Validation Methods ==========

    /**
     * Validates that a page ID is valid (>= 0).
     * 
     * @param pageId page ID to validate
     * @throws IllegalArgumentException if pageId < 0
     */
    public static void validatePageId(int pageId) {
        if (pageId < 0) {
            throw new IllegalArgumentException("Invalid page ID: " + pageId);
        }
    }

    /**
     * Validates tuple size is within acceptable bounds.
     * 
     * @param size tuple size in bytes
     * @throws IllegalArgumentException if size is invalid
     */
    public static void validateTupleSize(int size) {
        if (size < MIN_TUPLE_SIZE || size > MAX_TUPLE_SIZE) {
            throw new IllegalArgumentException(
                    "Invalid tuple size: " + size +
                            " (must be between " + MIN_TUPLE_SIZE + " and " + MAX_TUPLE_SIZE + ")");
        }
    }

    /**
     * Validates slot ID is within bounds.
     * 
     * @param slotId   slot ID to validate
     * @param numSlots total number of slots in page
     * @throws IllegalArgumentException if slotId is invalid
     */
    public static void validateSlotId(int slotId, int numSlots) {
        if (slotId < 0 || slotId >= numSlots) {
            throw new IllegalArgumentException(
                    "Invalid slot ID: " + slotId + " (num slots: " + numSlots + ")");
        }
    }
}
