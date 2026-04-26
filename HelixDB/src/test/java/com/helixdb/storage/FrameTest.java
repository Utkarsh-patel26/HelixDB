package com.helixdb.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FrameTest {

    @Test
    void constructor_rejectsNullPage() {
        assertThrows(IllegalArgumentException.class, () -> new Frame(null));
    }

    @Test
    void constructor_setsExpectedDefaults() {
        Page page = new Page(9);
        Frame frame = new Frame(page);

        assertAll(
                () -> assertSame(page, frame.page),
                () -> assertFalse(frame.dirty),
                () -> assertEquals(0, frame.getPinCount()),
                () -> assertFalse(frame.isPinned()),
                () -> assertEquals(9, frame.getPageId()));
    }

    @Test
    void pinHelpers_reflectAtomicPinCount() {
        Frame frame = new Frame(new Page(1));

        frame.pinCount.incrementAndGet();
        frame.pinCount.incrementAndGet();

        assertAll(
                () -> assertTrue(frame.isPinned()),
                () -> assertEquals(2, frame.getPinCount()));
    }

    @Test
    void getAgeMs_reflectsElapsedTimeSinceLastUse() {
        Frame frame = new Frame(new Page(1));
        frame.lastUsedTime = System.nanoTime() - 5_000_000L;

        assertTrue(frame.getAgeMs() >= 4);
    }

    @Test
    void toString_containsDiagnosticFields() {
        Frame frame = new Frame(new Page(5));
        frame.pinCount.set(1);
        frame.dirty = true;

        String text = frame.toString();

        assertAll(
                () -> assertTrue(text.contains("page=5")),
                () -> assertTrue(text.contains("dirty=Y")),
                () -> assertTrue(text.contains("pins=1")));
    }
}
