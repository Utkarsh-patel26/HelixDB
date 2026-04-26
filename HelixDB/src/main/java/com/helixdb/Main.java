package com.helixdb;

/**
 * HelixDB entry point.
 * 
 * HelixDB is a relational database engine built from scratch in Java.
 * Phase 1 implements the storage layer with disk I/O, page management, and
 * buffer pool.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("  HelixDB - A Database Engine in Java");
        System.out.println("========================================");
        System.out.println();
        System.out.println("Phase 1: Disk I/O, Page Layout, and Buffer Pool");
        System.out.println();
        System.out.println("Components:");
        System.out.println("  - Page layout with slotted tuples");
        System.out.println("  - Disk manager for file I/O");
        System.out.println("  - Write-ahead log for durability");
        System.out.println("  - LRU buffer pool with page cache");
        System.out.println();
        System.out.println("Status: Ready for Phase 2 (Transactions & WAL)");
    }
}
