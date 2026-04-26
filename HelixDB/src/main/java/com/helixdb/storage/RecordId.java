package com.helixdb.storage;

import java.util.Objects;

/**
 * Immutable record identifier that uniquely addresses a tuple in the database.
 * 
 * A RecordId consists of:
 * - pageId: which page the tuple is stored in
 * - slotId: which slot within that page
 * 
 * Once a tuple is inserted, its RID never changes unless deleted and
 * re-inserted.
 * This permanence allows indexes to reliably point to tuples.
 * 
 * RecordIds are immutable and can be used as map keys and in collections.
 * They are comparable for sorting and support efficient 64-bit packing.
 * 
 * @see #pack() for 64-bit representation used in B+ tree nodes
 */
public final class RecordId implements Comparable<RecordId> {
    /** Page ID where the tuple resides (>= 0) */
    public final int pageId;

    /** Slot ID within the page (>= 0) */
    public final int slotId;

    /**
     * Constructs a new RecordId.
     * 
     * @param pageId page ID (must be >= 0)
     * @param slotId slot ID within the page (must be >= 0)
     * @throws IllegalArgumentException if either ID is negative
     */
    public RecordId(int pageId, int slotId) {
        if (pageId < 0) {
            throw new IllegalArgumentException("pageId must be >= 0, got " + pageId);
        }
        if (slotId < 0) {
            throw new IllegalArgumentException("slotId must be >= 0, got " + slotId);
        }
        this.pageId = pageId;
        this.slotId = slotId;
    }

    /**
     * Packs this RecordId into a single 64-bit long.
     * Format: upper 32 bits = pageId, lower 32 bits = slotId
     * 
     * Used for efficient storage in B+ tree nodes and indexes.
     * 
     * @return packed long representation
     */
    public long pack() {
        return ((long) pageId << 32) | (slotId & 0xFFFFFFFFL);
    }

    /**
     * Unpacks a RecordId from a 64-bit long.
     * Inverse of pack().
     * 
     * @param packed packed long from pack()
     * @return unpacked RecordId
     */
    public static RecordId unpack(long packed) {
        int pageId = (int) (packed >> 32);
        int slotId = (int) packed;
        return new RecordId(pageId, slotId);
    }

    /**
     * Compares two RecordIds for ordering.
     * Orders by pageId first, then by slotId.
     * 
     * @param other the RecordId to compare with
     * @return comparison result: negative if less, 0 if equal, positive if greater
     */
    @Override
    public int compareTo(RecordId other) {
        int pageCompare = Integer.compare(this.pageId, other.pageId);
        return pageCompare != 0 ? pageCompare : Integer.compare(this.slotId, other.slotId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof RecordId))
            return false;
        RecordId rid = (RecordId) o;
        return pageId == rid.pageId && slotId == rid.slotId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pageId, slotId);
    }

    @Override
    public String toString() {
        return String.format("RID(%d,%d)", pageId, slotId);
    }
}
