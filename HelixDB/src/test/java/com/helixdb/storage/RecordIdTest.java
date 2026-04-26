package com.helixdb.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordIdTest {

    @Test
    void constructor_rejectsNegativePageId() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new RecordId(-1, 0));
        assertTrue(ex.getMessage().contains("pageId"));
    }

    @Test
    void constructor_rejectsNegativeSlotId() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new RecordId(0, -1));
        assertTrue(ex.getMessage().contains("slotId"));
    }

    @Test
    void packAndUnpack_roundTripPreservesIds() {
        RecordId original = new RecordId(123456789, 987654321);

        long packed = original.pack();
        RecordId unpacked = RecordId.unpack(packed);

        assertEquals(original, unpacked);
    }

    @Test
    void packAndUnpack_supportLarge32BitValues() {
        RecordId original = new RecordId(Integer.MAX_VALUE, Integer.MAX_VALUE);

        RecordId unpacked = RecordId.unpack(original.pack());

        assertEquals(Integer.MAX_VALUE, unpacked.pageId);
        assertEquals(Integer.MAX_VALUE, unpacked.slotId);
    }

    @Test
    void compareTo_ordersByPageThenSlot() {
        RecordId a = new RecordId(1, 5);
        RecordId b = new RecordId(2, 0);
        RecordId c = new RecordId(2, 3);
        RecordId d = new RecordId(2, 3);

        assertAll(
                () -> assertTrue(a.compareTo(b) < 0),
                () -> assertTrue(b.compareTo(c) < 0),
                () -> assertEquals(0, c.compareTo(d)));
    }

    @Test
    void equalsAndHashCode_followValueSemantics() {
        RecordId left = new RecordId(10, 20);
        RecordId same = new RecordId(10, 20);
        RecordId different = new RecordId(10, 21);

        assertAll(
                () -> assertEquals(left, same),
                () -> assertEquals(left.hashCode(), same.hashCode()),
                () -> assertNotEquals(left, different),
                () -> assertNotEquals(left, null),
                () -> assertNotEquals(left, "RID"));
    }

    @Test
    void toString_usesStableReadableFormat() {
        assertEquals("RID(7,9)", new RecordId(7, 9).toString());
    }
}
