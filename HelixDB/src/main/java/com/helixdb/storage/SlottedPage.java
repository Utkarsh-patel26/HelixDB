package com.helixdb.storage;

import static com.helixdb.storage.DbConstants.*;

/**
 * Slotted page layout management for variable-length tuples.
 * 
 * A slotted page uses an indirection layer (slot array) to allow tuples to move
 * without changing their record IDs. This enables efficient defragmentation.
 * 
 * <h2>Layout</h2>
 * 
 * <pre>
 * [Header: 24][Slot 0][Slot 1]...[SlotN][Free][TupleN]...[Tuple1][Tuple0]
 * Byte 0                              ↓            ↑
 *                                   grows        grows
 *                                    down        down
 * </pre>
 * 
 * - Slot array grows downward from header
 * - Tuple data grows downward from page end
 * - Free space is the gap between them
 * 
 * Each slot entry: 2 bytes (offset) + 2 bytes (length) = 4 bytes
 * 
 * <h2>Deletion</h2>
 * Deleted slots are marked with tombstones (offset=0, length=0).
 * Tuple data is NOT immediately freed; defragmentation must be done separately.
 * 
 * @see Page for the underlying page implementation
 * @see RecordId for tuple addressing
 */
public class SlottedPage {
    private final Page page;

    /**
     * Wraps a Page as a slotted page.
     * 
     * @param page the underlying Page object (must not be null)
     * @throws IllegalArgumentException if page is null
     */
    public SlottedPage(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page cannot be null");
        }
        this.page = page;

        // Initialize tuple data top for fresh pages
        if (page.getTupleDataTop() == 0) {
            page.setTupleDataTop(PAGE_SIZE);
        }
    }

    /**
     * Inserts a tuple into this page.
     * 
     * @param tupleBytes the tuple data (must not be null)
     * @return slot ID if successful, INVALID_SLOT_ID if no space
     * @throws IllegalArgumentException if tuple size is invalid
     */
    public int insertTuple(byte[] tupleBytes) {
        if (tupleBytes == null) {
            throw new IllegalArgumentException("Tuple data cannot be null");
        }
        DbConstants.validateTupleSize(tupleBytes.length);

        int tupleLen = tupleBytes.length;
        int tupleTop = page.getTupleDataTop();
        int numSlots = page.getNumSlots();
        int slotArrayEnd = PAGE_HEADER_SIZE + numSlots * SLOT_ENTRY_SIZE;
        int newTupleTop = tupleTop - tupleLen;

        // Check if new slot entry + tuple data fits in remaining space
        if (newTupleTop - SLOT_ENTRY_SIZE < slotArrayEnd) {
            return INVALID_SLOT_ID; // No space
        }

        // Write tuple data at its new position
        page.putBytes(newTupleTop, tupleBytes);

        // Write slot entry: offset (2 bytes) + length (2 bytes)
        int slotOffset = PAGE_HEADER_SIZE + numSlots * SLOT_ENTRY_SIZE;
        page.putShort(slotOffset, (short) newTupleTop);
        page.putShort(slotOffset + SLOT_LENGTH_FIELD, (short) tupleLen);

        // Update page header
        page.setNumSlots(numSlots + 1);
        page.setNumLiveSlots(page.getNumLiveSlots() + 1);
        page.setTupleDataTop(newTupleTop);

        return numSlots; // slotId = index in slot array
    }

    /**
     * Reads a tuple by slot ID.
     * 
     * @param slotId slot ID
     * @return tuple data, or null if slot is deleted or out of range
     * @throws IllegalArgumentException if slotId is negative
     */
    public byte[] readTuple(int slotId) {
        if (slotId < 0) {
            throw new IllegalArgumentException("slotId cannot be negative: " + slotId);
        }

        if (slotId >= page.getNumSlots()) {
            return null; // Slot doesn't exist
        }

        int slotOffset = PAGE_HEADER_SIZE + slotId * SLOT_ENTRY_SIZE;
        int tupleOffset = Short.toUnsignedInt(page.getShort(slotOffset));
        int tupleLen = Short.toUnsignedInt(page.getShort(slotOffset + SLOT_LENGTH_FIELD));

        // Check for tombstone (deleted marker)
        if (tupleOffset == TOMBSTONE_OFFSET && tupleLen == TOMBSTONE_LENGTH) {
            return null; // Slot is deleted
        }

        return page.getBytes(tupleOffset, tupleLen);
    }

    /**
     * Marks a slot as deleted (tombstone).
     * Does NOT free the tuple data or compact the page.
     * 
     * @param slotId slot ID to delete
     * @throws IllegalArgumentException if slotId is invalid
     */
    public void deleteTuple(int slotId) {
        if (slotId < 0 || slotId >= page.getNumSlots()) {
            throw new IllegalArgumentException(
                    "Invalid slotId: " + slotId + " (numSlots: " + page.getNumSlots() + ")");
        }

        int slotOffset = PAGE_HEADER_SIZE + slotId * SLOT_ENTRY_SIZE;

        // Check if already deleted
        int offset = Short.toUnsignedInt(page.getShort(slotOffset));
        int length = Short.toUnsignedInt(page.getShort(slotOffset + SLOT_LENGTH_FIELD));
        if (offset == TOMBSTONE_OFFSET && length == TOMBSTONE_LENGTH) {
            return; // Already deleted
        }

        // Write tombstone
        page.putShort(slotOffset, (short) TOMBSTONE_OFFSET);
        page.putShort(slotOffset + SLOT_LENGTH_FIELD, (short) TOMBSTONE_LENGTH);

        page.setNumLiveSlots(page.getNumLiveSlots() - 1);
    }

    /**
     * Updates a tuple in place.
     * New tuple size must exactly match the old size.
     * If size changes, caller must delete + re-insert (which may change RID).
     * 
     * @param slotId   slot ID
     * @param newBytes new tuple data (must not be null)
     * @throws IllegalArgumentException if slot doesn't exist or sizes don't match
     */
    public void updateTuple(int slotId, byte[] newBytes) {
        if (slotId < 0 || slotId >= page.getNumSlots()) {
            throw new IllegalArgumentException(
                    "Invalid slotId: " + slotId + " (numSlots: " + page.getNumSlots() + ")");
        }
        if (newBytes == null) {
            throw new IllegalArgumentException("New tuple data cannot be null");
        }

        int slotOffset = PAGE_HEADER_SIZE + slotId * SLOT_ENTRY_SIZE;
        int tupleOffset = Short.toUnsignedInt(page.getShort(slotOffset));
        int tupleLen = Short.toUnsignedInt(page.getShort(slotOffset + SLOT_LENGTH_FIELD));

        if (tupleOffset == TOMBSTONE_OFFSET && tupleLen == TOMBSTONE_LENGTH) {
            throw new IllegalArgumentException("Cannot update deleted slot " + slotId);
        }

        if (newBytes.length != tupleLen) {
            throw new IllegalArgumentException(
                    "In-place update requires same tuple size. Old: " + tupleLen + ", New: " + newBytes.length);
        }

        page.putBytes(tupleOffset, newBytes);
    }

    // ========== Query Methods ==========

    /** Gets the number of slot entries (including deleted ones) */
    public int getNumSlots() {
        return page.getNumSlots();
    }

    /** Gets the number of live (non-deleted) slots */
    public int getNumLiveSlots() {
        return page.getNumLiveSlots();
    }

    /** Gets the number of deleted slots */
    public int getNumDeletedSlots() {
        return getNumSlots() - getNumLiveSlots();
    }

    /** Gets the underlying Page object */
    public Page getPage() {
        return page;
    }

    /** Calculates available free space for a new tuple */
    public int getFreeSpace() {
        int tupleTop = page.getTupleDataTop();
        int numSlots = page.getNumSlots();
        int slotArrayEnd = PAGE_HEADER_SIZE + numSlots * SLOT_ENTRY_SIZE;

        // Need space for: the new slot entry + the new tuple
        return tupleTop - slotArrayEnd - SLOT_ENTRY_SIZE;
    }

    /**
     * Checks if the page has space for at least one more tuple of the given size
     */
    public boolean canInsert(int tupleSize) {
        if (tupleSize < MIN_TUPLE_SIZE || tupleSize > MAX_TUPLE_SIZE) {
            return false;
        }
        return getFreeSpace() >= tupleSize;
    }

    /** Gets the space used by the slot array */
    public int getSlotArraySize() {
        return page.getNumSlots() * SLOT_ENTRY_SIZE;
    }

    /** Gets the space used by tuple data */
    public int getTupleDataSize() {
        return PAGE_SIZE - page.getTupleDataTop();
    }

    /**
     * Gets space wasted by deleted tuples (cannot be recovered without
     * defragmentation)
     */
    public int getFragmentationWaste() {
        // Approximation: unused space minus free space = fragmented
        return PAGE_SIZE - PAGE_HEADER_SIZE - getSlotArraySize() - getTupleDataSize() - getFreeSpace();
    }

    /** Gets the fragmentation ratio (0.0 to 1.0) */
    public double getFragmentationRatio() {
        int wasted = getFragmentationWaste();
        return wasted == 0 ? 0.0 : (double) wasted / PAGE_SIZE;
    }

    @Override
    public String toString() {
        return String.format(
                "SlottedPage(id=%d, slots=%d, live=%d, free=%d, fragmented=%d, frag_ratio=%.1f%%)",
                page.getPageId(), getNumSlots(), getNumLiveSlots(),
                getFreeSpace(), getFragmentationWaste(), getFragmentationRatio() * 100);
    }
}
