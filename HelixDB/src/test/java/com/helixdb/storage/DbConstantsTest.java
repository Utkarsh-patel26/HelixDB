package com.helixdb.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DbConstantsTest {

    @Test
    void constants_haveExpectedRelationships() {
        assertAll(
                () -> assertEquals(1, DbConstants.DB_FORMAT_VERSION),
                () -> assertEquals(DbConstants.PAGE_SIZE - DbConstants.PAGE_HEADER_SIZE - DbConstants.SLOT_ENTRY_SIZE,
                        DbConstants.MAX_TUPLE_SIZE),
                () -> assertEquals((DbConstants.PAGE_SIZE - DbConstants.PAGE_HEADER_SIZE) / DbConstants.SLOT_ENTRY_SIZE,
                        DbConstants.MAX_SLOTS_PER_PAGE),
                () -> assertEquals(DbConstants.OFFSET_PAGE_LSN + Long.BYTES, DbConstants.PAGE_HEADER_SIZE),
                () -> assertEquals(0, DbConstants.TOMBSTONE_OFFSET),
                () -> assertEquals(0, DbConstants.TOMBSTONE_LENGTH));
    }

    @Test
    void validatePageId_acceptsZeroAndPositiveValues() {
        assertDoesNotThrow(() -> DbConstants.validatePageId(0));
        assertDoesNotThrow(() -> DbConstants.validatePageId(123));
    }

    @Test
    void validatePageId_rejectsNegativeValues() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> DbConstants.validatePageId(-1));
        assertTrue(ex.getMessage().contains("Invalid page ID"));
    }

    @Test
    void validateTupleSize_acceptsBoundaryValues() {
        assertDoesNotThrow(() -> DbConstants.validateTupleSize(DbConstants.MIN_TUPLE_SIZE));
        assertDoesNotThrow(() -> DbConstants.validateTupleSize(DbConstants.MAX_TUPLE_SIZE));
    }

    @Test
    void validateTupleSize_rejectsOutOfRangeValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DbConstants.validateTupleSize(DbConstants.MIN_TUPLE_SIZE - 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DbConstants.validateTupleSize(DbConstants.MAX_TUPLE_SIZE + 1)));
    }

    @Test
    void validateSlotId_acceptsValidBounds() {
        assertDoesNotThrow(() -> DbConstants.validateSlotId(0, 3));
        assertDoesNotThrow(() -> DbConstants.validateSlotId(2, 3));
    }

    @Test
    void validateSlotId_rejectsInvalidBounds() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DbConstants.validateSlotId(-1, 3)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DbConstants.validateSlotId(3, 3)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> DbConstants.validateSlotId(0, 0)));
    }
}
