package com.helixdb.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static com.helixdb.storage.DbConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class SlottedPageTest {

    @Test
    void constructor_rejectsNullPage() {
        assertThrows(IllegalArgumentException.class, () -> new SlottedPage(null));
    }

    @Test
    void constructor_initializesTupleTopForFreshRawPage() {
        Page page = new Page(new byte[PAGE_SIZE]);
        assertEquals(0, page.getTupleDataTop());

        new SlottedPage(page);

        assertEquals(PAGE_SIZE, page.getTupleDataTop());
    }

    @Test
    void insertTuple_rejectsNullAndInvalidSizes() {
        SlottedPage sp = new SlottedPage(new Page(0));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> sp.insertTuple(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> sp.insertTuple(new byte[0])),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> sp.insertTuple(new byte[MAX_TUPLE_SIZE + 1])));
    }

    @Test
    void insertTuple_returnsConsecutiveSlotIdsAndTupleDataRoundTrips() {
        SlottedPage sp = new SlottedPage(new Page(0));

        int s0 = sp.insertTuple("hello".getBytes(StandardCharsets.UTF_8));
        int s1 = sp.insertTuple("world".getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertEquals(0, s0),
                () -> assertEquals(1, s1),
                () -> assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), sp.readTuple(s0)),
                () -> assertArrayEquals("world".getBytes(StandardCharsets.UTF_8), sp.readTuple(s1)),
                () -> assertEquals(2, sp.getNumSlots()),
                () -> assertEquals(2, sp.getNumLiveSlots()));
    }

    @Test
    void insertTuple_returnsInvalidSlotWhenPageIsFull() {
        SlottedPage sp = new SlottedPage(new Page(0));
        byte[] tuple = new byte[64];

        int inserted = 0;
        while (true) {
            int slot = sp.insertTuple(tuple);
            if (slot == INVALID_SLOT_ID) {
                break;
            }
            inserted++;
        }

        assertTrue(inserted > 0);
        assertFalse(sp.canInsert(tuple.length));
    }

    @Test
    void readTuple_validatesSlotIdAndHandlesOutOfRange() {
        SlottedPage sp = new SlottedPage(new Page(0));
        sp.insertTuple(new byte[] { 1, 2, 3 });

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> sp.readTuple(-1)),
                () -> assertNull(sp.readTuple(1)));
    }

    @Test
    void deleteTuple_marksTombstoneAndIsIdempotent() {
        SlottedPage sp = new SlottedPage(new Page(0));
        int slot = sp.insertTuple(new byte[] { 1, 2, 3 });

        sp.deleteTuple(slot);
        int liveAfterFirstDelete = sp.getNumLiveSlots();
        sp.deleteTuple(slot);

        assertAll(
                () -> assertNull(sp.readTuple(slot)),
                () -> assertEquals(0, liveAfterFirstDelete),
                () -> assertEquals(0, sp.getNumLiveSlots()),
                () -> assertEquals(1, sp.getNumDeletedSlots()));
    }

    @Test
    void deleteTuple_rejectsInvalidSlotIds() {
        SlottedPage sp = new SlottedPage(new Page(0));
        sp.insertTuple(new byte[] { 1 });

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> sp.deleteTuple(-1)),
                () -> assertThrows(IllegalArgumentException.class, () -> sp.deleteTuple(1)));
    }

    @Test
    void updateTuple_updatesInPlaceForEqualLengthPayload() {
        SlottedPage sp = new SlottedPage(new Page(0));
        int slot = sp.insertTuple("ABCDE".getBytes(StandardCharsets.UTF_8));

        sp.updateTuple(slot, "XYZ12".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals("XYZ12".getBytes(StandardCharsets.UTF_8), sp.readTuple(slot));
    }

    @Test
    void updateTuple_rejectsInvalidCases() {
        SlottedPage sp = new SlottedPage(new Page(0));
        int slot = sp.insertTuple("abc".getBytes(StandardCharsets.UTF_8));
        int deleted = sp.insertTuple("def".getBytes(StandardCharsets.UTF_8));
        sp.deleteTuple(deleted);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> sp.updateTuple(-1, new byte[] { 1 })),
                () -> assertThrows(IllegalArgumentException.class, () -> sp.updateTuple(99, new byte[] { 1 })),
                () -> assertThrows(IllegalArgumentException.class, () -> sp.updateTuple(slot, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> sp.updateTuple(slot, new byte[] { 1, 2 })),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> sp.updateTuple(deleted, new byte[] { 1, 2, 3 })));
    }

    @Test
    void spaceAndAccountingMethods_trackLayoutEvolution() {
        SlottedPage sp = new SlottedPage(new Page(0));
        int initialFree = sp.getFreeSpace();

        int s0 = sp.insertTuple(new byte[] { 1, 2, 3, 4, 5 });
        int freeAfterInsert = sp.getFreeSpace();
        sp.deleteTuple(s0);

        assertAll(
                () -> assertTrue(initialFree > freeAfterInsert),
                () -> assertEquals(1, sp.getSlotArraySize() / SLOT_ENTRY_SIZE),
                () -> assertTrue(sp.getTupleDataSize() >= 5),
                () -> assertTrue(sp.getFragmentationWaste() >= 0),
                () -> assertTrue(sp.getFragmentationRatio() >= 0.0),
                () -> assertFalse(sp.canInsert(MIN_TUPLE_SIZE - 1)),
                () -> assertFalse(sp.canInsert(MAX_TUPLE_SIZE + 1)));
    }

    @Test
    void toString_containsPageAndSpaceSummary() {
        SlottedPage sp = new SlottedPage(new Page(55));
        String text = sp.toString();

        assertAll(
                () -> assertTrue(text.contains("SlottedPage(id=55")),
                () -> assertTrue(text.contains("slots=")),
                () -> assertTrue(text.contains("free=")));
    }
}
