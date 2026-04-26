package com.helixdb;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MainTest {

    @Test
    void main_printsExpectedBannerAndStatus() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();

        System.setOut(new PrintStream(outBuffer));
        try {
            Main.main(new String[0]);
        } finally {
            System.setOut(originalOut);
        }

        String output = outBuffer.toString(StandardCharsets.UTF_8);

        assertAll(
                () -> assertTrue(output.contains("HelixDB - A Database Engine in Java")),
                () -> assertTrue(output.contains("Phase 1: Disk I/O, Page Layout, and Buffer Pool")),
                () -> assertTrue(output.contains("Status: Ready for Phase 2")),
                () -> assertTrue(output.contains("Disk manager for file I/O")),
                () -> assertTrue(output.contains("LRU buffer pool with page cache")));
    }
}
