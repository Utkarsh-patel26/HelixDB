package com.helixdb.storage;

import org.junit.jupiter.api.Test;

import static com.helixdb.storage.DbConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class PageTest {

    @Test
    void constructor_withPageId_initializesHeaderDefaults() {
        Page page = new Page(42);

        assertAll(
                () -> assertEquals(42, page.getPageId()),
                () -> assertEquals(0, page.getNumSlots()),
                () -> assertEquals(0, page.getNumLiveSlots()),
                () -> assertEquals(PAGE_SIZE, page.getTupleDataTop()),
                () -> assertEquals(INVALID_PAGE_ID, page.getNextPageId()),
                () -> assertEquals(0L, page.getPageLsn()));
    }

    @Test
    void constructor_fromRawBytes_rejectsNullAndWrongSize() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new Page((byte[]) null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new Page(new byte[PAGE_SIZE - 1])),
                () -> assertThrows(IllegalArgumentException.class, () -> new Page(new byte[PAGE_SIZE + 1])));
    }

    @Test
    void constructor_fromRawBytes_usesPersistedHeaderValues() {
        Page original = new Page(9);
        original.setNumSlots(3);
        original.setNumLiveSlots(2);
        original.setTupleDataTop(PAGE_SIZE - 20);
        original.setNextPageId(10);
        original.setPageLsn(99L);

        Page restored = new Page(original.copyRawData());

        assertAll(
                () -> assertEquals(9, restored.getPageId()),
                () -> assertEquals(3, restored.getNumSlots()),
                () -> assertEquals(2, restored.getNumLiveSlots()),
                () -> assertEquals(PAGE_SIZE - 20, restored.getTupleDataTop()),
                () -> assertEquals(10, restored.getNextPageId()),
                () -> assertEquals(99L, restored.getPageLsn()));
    }

    @Test
    void setNumSlots_validatesBounds() {
        Page page = new Page(0);

        page.setNumSlots(MAX_SLOTS_PER_PAGE);
        assertEquals(MAX_SLOTS_PER_PAGE, page.getNumSlots());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> page.setNumSlots(-1)),
                () -> assertThrows(IllegalArgumentException.class, () -> page.setNumSlots(MAX_SLOTS_PER_PAGE + 1)));
    }

    @Test
    void setNumLiveSlots_validatesAgainstNumSlots() {
        Page page = new Page(0);
        page.setNumSlots(2);

        page.setNumLiveSlots(2);
        assertEquals(2, page.getNumLiveSlots());

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> page.setNumLiveSlots(-1)),
                () -> assertThrows(IllegalArgumentException.class, () -> page.setNumLiveSlots(3)));
    }

    @Test
    void setTupleDataTop_validatesRange() {
        Page page = new Page(0);

        page.setTupleDataTop(PAGE_HEADER_SIZE);
        page.setTupleDataTop(PAGE_SIZE);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> page.setTupleDataTop(PAGE_HEADER_SIZE - 1)),
                () -> assertThrows(IllegalArgumentException.class, () -> page.setTupleDataTop(PAGE_SIZE + 1)));
    }

    @Test
    void primitiveReadWrite_roundTripsValues() {
        Page page = new Page(1);

        page.putInt(100, 123456789);
        page.putShort(104, (short) 12345);
        page.putLong(106, 123456789101112L);
        page.putByte(114, (byte) 0x7F);

        assertAll(
                () -> assertEquals(123456789, page.getInt(100)),
                () -> assertEquals((short) 12345, page.getShort(104)),
                () -> assertEquals(123456789101112L, page.getLong(106)),
                () -> assertEquals((byte) 0x7F, page.getByte(114)));
    }

    @Test
    void readWrite_methods_failOnOutOfBoundsAccess() {
        Page page = new Page(0);

        assertAll(
                () -> assertThrows(IndexOutOfBoundsException.class, () -> page.putInt(PAGE_SIZE - 3, 1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> page.getLong(PAGE_SIZE - 7)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> page.putByte(-1, (byte) 1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> page.getByte(PAGE_SIZE)));
    }

    @Test
    void byteArrayReadWrite_roundTripsAndValidatesArguments() {
        Page page = new Page(0);
        byte[] bytes = new byte[] { 1, 2, 3, 4, 5 };

        page.putBytes(200, bytes);
        assertArrayEquals(bytes, page.getBytes(200, bytes.length));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> page.putBytes(0, null)),
                () -> assertThrows(IllegalArgumentException.class, () -> page.getBytes(0, -1)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> page.putBytes(PAGE_SIZE - 2, bytes)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> page.getBytes(PAGE_SIZE - 1, 2)));
    }

    @Test
    void copyRawData_returnsDeepCopy() {
        Page page = new Page(123);
        page.putByte(50, (byte) 11);

        byte[] copy = page.copyRawData();
        copy[50] = 22;

        assertEquals(11, page.getByte(50));
    }

    @Test
    void toString_containsCoreHeaderState() {
        Page page = new Page(7);
        String text = page.toString();

        assertAll(
                () -> assertTrue(text.contains("Page(id=7")),
                () -> assertTrue(text.contains("slots=0")),
                () -> assertTrue(text.contains("live=0")));
    }
}
