# HelixDB - A Relational Database Engine Built from Scratch in Java

> A fully functional mini relational database engine featuring a B+ Tree storage engine, hand-written SQL parser, volcano-model query planner, buffer pool manager, and ACID-compliant transaction system — all implemented from scratch in pure Java with zero external dependencies.

---

## Table of Contents

1. [What This Project Is](#what-this-project-is)
2. [Why Build This](#why-build-this)
3. [System Architecture](#system-architecture)
4. [Project Structure](#project-structure)
5. [Layer 1 — Client Interface](#layer-1--client-interface)
6. [Layer 2 — SQL Parser](#layer-2--sql-parser)
   - [The Lexer](#the-lexer)
   - [The Parser and AST](#the-parser-and-ast)
7. [Layer 3 — Query Planner and Optimizer](#layer-3--query-planner-and-optimizer)
   - [Semantic Analyzer](#semantic-analyzer)
   - [Logical Plan](#logical-plan)
   - [Physical Plan](#physical-plan)
   - [The Volcano Iterator Model](#the-volcano-iterator-model)
8. [Layer 4 — Storage Engine](#layer-4--storage-engine)
   - [Disk Manager](#disk-manager)
   - [Page Layout — Slotted Pages](#page-layout--slotted-pages)
   - [Buffer Pool Manager](#buffer-pool-manager)
   - [Heap Files](#heap-files)
   - [Catalog and Metadata](#catalog-and-metadata)
   - [B+ Tree Index](#b-tree-index)
     - [Node Structure](#node-structure)
     - [Search](#search)
     - [Insertion and Node Splitting](#insertion-and-node-splitting)
     - [Deletion and Rebalancing](#deletion-and-rebalancing)
     - [Range Scans](#range-scans)
9. [Layer 5 — Transaction Manager (ACID)](#layer-5--transaction-manager-acid)
   - [Atomicity — Write-Ahead Log](#atomicity--write-ahead-log)
   - [Consistency — Schema Enforcement](#consistency--schema-enforcement)
   - [Isolation — Two-Phase Locking](#isolation--two-phase-locking)
   - [Durability — Force Flush on Commit](#durability--force-flush-on-commit)
   - [Crash Recovery — REDO and UNDO](#crash-recovery--redo-and-undo)
10. [Data Types](#data-types)
11. [Supported SQL Syntax](#supported-sql-syntax)
12. [Concurrency Model](#concurrency-model)
13. [On-Disk File Format](#on-disk-file-format)
14. [Zero External Dependencies — Design Philosophy](#zero-external-dependencies--design-philosophy)
15. [Known Limitations and Intentional Simplifications](#known-limitations-and-intentional-simplifications)
16. [Concepts You Will Deeply Understand After Reading This](#concepts-you-will-deeply-understand-after-reading-this)

---

## What This Project Is

HelixDB is a from-scratch implementation of a relational database management system (RDBMS) written entirely in Java. It is not a wrapper around SQLite, H2, or any other embedded database. Every component — from byte-level page I/O all the way up to SQL string parsing — is hand-written.

The engine supports a meaningful subset of SQL: `CREATE TABLE`, `DROP TABLE`, `INSERT`, `SELECT` (with `WHERE`, `ORDER BY`, `LIMIT`, `JOIN`), `UPDATE`, and `DELETE`. It stores data in a custom binary file format on disk, indexes columns using a B+ Tree, executes queries using a volcano-model operator pipeline, and guarantees ACID properties using a Write-Ahead Log and Two-Phase Locking.

The project is structured as five distinct layers, each with a clean interface boundary:

```
SQL string
    │
    ▼
[ SQL Parser ]          → AST (Abstract Syntax Tree)
    │
    ▼
[ Query Planner ]       → Physical Plan (tree of operators)
    │
    ▼
[ Executor ]            → pulls tuples through volcano pipeline
    │
    ▼
[ Storage Engine ]      → B+ Tree + Heap File + Buffer Pool
    │
    ▼
[ Transaction Manager ] → WAL + 2PL Locking + Crash Recovery
    │
    ▼
[ Disk ]                → *.db pages + *.wal log file
```

---

## Why Build This

Most developers use databases every day without understanding what happens between `db.query("SELECT * FROM users")` and the rows appearing on screen. This project forces you to answer questions that are otherwise black boxes:

- Why does adding an index make a query faster — and when does it not?
- What actually happens when you call `COMMIT`?
- Why does your database survive a power cut but a plain file write does not?
- What is a dirty page, and why does it matter?
- How does a database prevent two concurrent transactions from corrupting the same row?
- Why does `SELECT COUNT(*) FROM users` take longer on a cold database than a warm one?

Building HelixDB does not just teach you how databases work. It teaches you how to reason about systems that must be both correct under concurrency and durable under failure — two of the hardest constraints in software engineering.

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Client Interface                            │
│          Connection · Statement · ResultSet · Cursor            │
└───────────────────────────┬─────────────────────────────────────┘
                            │ raw SQL string
┌───────────────────────────▼─────────────────────────────────────┐
│                       SQL Parser                                │
│   Lexer (tokenizer) → Token stream → Recursive-descent parser   │
│   → AST: SelectStatement · InsertStatement · CreateTableNode…   │
└───────────────────────────┬─────────────────────────────────────┘
                            │ AST node
┌───────────────────────────▼─────────────────────────────────────┐
│                    Query Planner / Optimizer                     │
│   Semantic Analyzer → Logical Plan → Physical Plan              │
│   SeqScanPlan · IndexScanPlan · FilterPlan · ProjectionPlan      │
│   NestedLoopJoinPlan · SortPlan · LimitPlan · AggregatePlan      │
└───────────────────────────┬─────────────────────────────────────┘
                            │ physical plan tree
┌───────────────────────────▼─────────────────────────────────────┐
│                      Storage Engine                             │
│  ┌──────────────────────────┐  ┌──────────────────────────────┐ │
│  │       B+ Tree Index      │  │   Heap File + Page Manager   │ │
│  │  internal nodes + leaves │  │   slotted pages · buffer pool│ │
│  │  split · merge · range   │  │   LRU eviction · dirty pages │ │
│  └──────────────────────────┘  └──────────────────────────────┘ │
└───────────────────────────┬─────────────────────────────────────┘
                            │ page reads / writes
┌───────────────────────────▼─────────────────────────────────────┐
│                   Transaction Manager                           │
│   WAL (Write-Ahead Log) · Two-Phase Locking (2PL)               │
│   REDO / UNDO recovery · Checkpoint · Deadlock detection        │
└───────────────────────────┬─────────────────────────────────────┘
                            │
                     ┌──────▼──────┐
                     │    Disk     │
                     │  *.db files │
                     │  *.wal file │
                     └─────────────┘
```

---

## Project Structure

```
HelixDB/
├── src/
│   └── main/
│       └── java/
│           └── HelixDB/
│               ├── client/
│               │   ├── Connection.java
│               │   ├── Statement.java
│               │   └── ResultSet.java
│               │
│               ├── parser/
│               │   ├── Lexer.java
│               │   ├── Token.java
│               │   ├── TokenType.java
│               │   ├── Parser.java
│               │   └── ast/
│               │       ├── AstNode.java
│               │       ├── SelectStatement.java
│               │       ├── InsertStatement.java
│               │       ├── UpdateStatement.java
│               │       ├── DeleteStatement.java
│               │       ├── CreateTableStatement.java
│               │       ├── DropTableStatement.java
│               │       ├── ColumnDefinition.java
│               │       ├── Expression.java
│               │       ├── BinaryExpression.java
│               │       ├── UnaryExpression.java
│               │       ├── ColumnRef.java
│               │       ├── Literal.java
│               │       ├── FunctionCall.java
│               │       ├── JoinClause.java
│               │       └── OrderByClause.java
│               │
│               ├── planner/
│               │   ├── Analyzer.java
│               │   ├── LogicalPlanner.java
│               │   ├── PhysicalPlanner.java
│               │   ├── StatisticsCollector.java
│               │   └── plans/
│               │       ├── PlanNode.java
│               │       ├── SeqScanPlan.java
│               │       ├── IndexScanPlan.java
│               │       ├── FilterPlan.java
│               │       ├── ProjectionPlan.java
│               │       ├── NestedLoopJoinPlan.java
│               │       ├── SortPlan.java
│               │       ├── LimitPlan.java
│               │       └── AggregatePlan.java
│               │
│               ├── storage/
│               │   ├── DiskManager.java
│               │   ├── Page.java
│               │   ├── SlottedPage.java
│               │   ├── BufferPool.java
│               │   ├── Frame.java
│               │   ├── HeapFile.java
│               │   ├── RecordId.java
│               │   ├── Tuple.java
│               │   ├── TupleSerializer.java
│               │   └── btree/
│               │       ├── BTree.java
│               │       ├── BTreeNode.java
│               │       ├── InternalNode.java
│               │       ├── LeafNode.java
│               │       └── BTreeIterator.java
│               │
│               ├── catalog/
│               │   ├── Catalog.java
│               │   ├── TableMetadata.java
│               │   ├── Schema.java
│               │   ├── Column.java
│               │   ├── DataType.java
│               │   └── IndexMetadata.java
│               │
│               └── txn/
│                   ├── TransactionManager.java
│                   ├── Transaction.java
│                   ├── LockManager.java
│                   ├── LockTable.java
│                   ├── LockMode.java
│                   ├── WalManager.java
│                   ├── LogRecord.java
│                   ├── LogType.java
│                   └── RecoveryManager.java
│
└── test/
    └── java/
        └── HelixDB/
            ├── parser/ParserTest.java
            ├── storage/BTreeTest.java
            ├── storage/BufferPoolTest.java
            ├── txn/TransactionTest.java
            └── integration/SqlIntegrationTest.java
```

---

## Layer 1 — Client Interface

The client interface is the only public-facing API of the database. It is modelled deliberately after JDBC so that the patterns feel familiar to any Java developer.

```
Connection  →  manages one session, one active transaction, auto-commit flag
Statement   →  compiles and executes one SQL string, holds a reference to the txn
ResultSet   →  lazy iterator over the tuples returned by a query
```

A `Connection` object is obtained from the `Database` singleton and represents a logical session. It holds the `TransactionId` of the currently active transaction. When `autoCommit` is true (the default), every statement is automatically wrapped in its own `BEGIN / COMMIT`. When false, the user calls `commit()` or `rollback()` explicitly.

`Statement.execute(String sql)` is the single entry point for all SQL. It passes the raw string to the parser, gets back an AST node, passes that to the planner, gets back a physical plan tree, and then opens the root plan node to begin pulling tuples. For non-query statements (INSERT, UPDATE, DELETE, CREATE TABLE) it returns an affected-row count immediately. For SELECT, it returns a `ResultSet` whose `next()` method pulls tuples lazily.

`ResultSet` wraps the root `PlanNode` and exposes typed accessors: `getString(String columnName)`, `getInt(String columnName)`, `getLong(String columnName)`, `getBoolean(String columnName)`. Internally it calls `planNode.next()` on each call to `ResultSet.next()` and decodes the raw `Tuple` bytes into typed Java values using the schema's column types.

---

## Layer 2 — SQL Parser

The parser converts a raw SQL string into a tree of Java objects (the AST). It has two stages that run in sequence: the lexer and the parser.

### The Lexer

The lexer (also called a tokenizer or scanner) reads the SQL string one character at a time and groups characters into typed tokens. Each `Token` has a `TokenType` and a string value.

Token types include:

- **Keywords**: `SELECT`, `FROM`, `WHERE`, `INSERT`, `INTO`, `VALUES`, `UPDATE`, `SET`, `DELETE`, `CREATE`, `TABLE`, `DROP`, `INDEX`, `ON`, `JOIN`, `INNER`, `LEFT`, `RIGHT`, `AND`, `OR`, `NOT`, `NULL`, `TRUE`, `FALSE`, `ORDER`, `BY`, `ASC`, `DESC`, `LIMIT`, `OFFSET`, `BEGIN`, `COMMIT`, `ROLLBACK`, `PRIMARY`, `KEY`, `UNIQUE`, `INT`, `VARCHAR`, `BOOLEAN`, `FLOAT`
- **Identifiers**: any unquoted name that is not a keyword (table names, column names)
- **String literals**: `'hello world'` — single-quoted, escaped with `\'`
- **Integer literals**: sequences of digits
- **Float literals**: digits with a decimal point
- **Operators**: `=`, `<>`, `!=`, `<`, `>`, `<=`, `>=`, `+`, `-`, `*`, `/`, `%`
- **Punctuation**: `(`, `)`, `,`, `.`, `;`
- **EOF**: signals end of input

The lexer handles whitespace by skipping it. It handles SQL comments (`-- single line` and `/* multi-line */`) by consuming and discarding them. It is implemented as a single class with a cursor position integer tracking how far through the input string we have read.

### The Parser and AST

The parser is a hand-written recursive-descent parser. Recursive-descent means each grammar rule in the SQL language maps directly to a Java method. The parser reads from the flat token list produced by the lexer and builds a tree of AST node objects.

The entry point is `Parser.parse()`, which inspects the first token and dispatches to the appropriate statement parser method:

```
parse()
  ├── parseSelect()      → SelectStatement
  ├── parseInsert()      → InsertStatement
  ├── parseUpdate()      → UpdateStatement
  ├── parseDelete()      → DeleteStatement
  ├── parseCreate()      → CreateTableStatement / CreateIndexStatement
  └── parseDrop()        → DropTableStatement / DropIndexStatement
```

`parseSelect()` is the most complex because SELECT has the most clauses. It calls helper methods in sequence: `parseColumnList()` for the projection list, `parseFromClause()` for the table and any JOINs, `parseWhereClause()` for the optional WHERE expression, `parseOrderByClause()`, and `parseLimitClause()`.

`parseWhereClause()` calls `parseExpression()`, which is itself a mini expression parser that handles operator precedence. It uses the standard recursive descent trick: `parseOr()` calls `parseAnd()` which calls `parseComparison()` which calls `parseAddSub()` which calls `parseMulDiv()` which calls `parseUnary()` which calls `parsePrimary()`. This ensures that `a + b * c > d AND e = f` parses with the correct precedence without any explicit precedence tables.

The AST node classes are plain Java objects with public fields. They do not contain any logic — they are pure data. All logic lives in the planner. For example:

`SelectStatement` holds a `List<Expression>` for the select list, a `List<JoinClause>` for any JOINs, a `String` for the primary table name, an optional `Expression` for the WHERE predicate, a `List<OrderByClause>` for ORDER BY, and optional `Integer` fields for LIMIT and OFFSET.

`BinaryExpression` holds a left `Expression`, an `Operator` enum value (`EQ`, `NEQ`, `LT`, `GT`, `LTE`, `GTE`, `AND`, `OR`, `PLUS`, `MINUS`, `MUL`, `DIV`), and a right `Expression`.

`ColumnRef` holds a table alias (nullable) and a column name.

`Literal` holds a `DataType` and an `Object` value.

---

## Layer 3 — Query Planner and Optimizer

The planner takes an AST node from the parser and produces a physical plan: a tree of operator objects that the executor will iterate over to produce result tuples.

### Semantic Analyzer

Before planning, the analyzer validates the AST against the catalog. It checks that all referenced tables exist, all referenced columns exist in those tables, column types are compatible with the operations being performed on them (e.g. you cannot apply `>` to a VARCHAR column and an INT literal without coercion), and that INSERT values match the target table's schema in count and type. If any check fails, a `SemanticException` is thrown before any plan is built.

The analyzer also resolves column references — `name` becomes `users.name` once the analyzer confirms it belongs to the `users` table in scope. This resolution is stored in each `ColumnRef` node as a fully-qualified reference so the planner and executor never have to re-look things up.

### Logical Plan

The logical plan represents what the query does without specifying how. It mirrors the relational algebra: Scan, Filter (σ), Project (π), Join (⋈), Sort, Limit, Aggregate. The logical planner translates the AST directly into a logical plan tree without any optimization decisions.

### Physical Plan

The physical planner takes the logical plan and produces a physical plan — a tree of concrete operator implementations. This is where optimization decisions are made:

**Index selection**: If a `FilterPlan` has a predicate of the form `column = constant` or `column > constant` and there is a B+ Tree index on that column, the physical planner replaces `SeqScanPlan + FilterPlan` with a single `IndexScanPlan`. The decision is based on a simple cost model: if the table has more than a threshold number of pages and the index selectivity is high, the index is preferred.

**Predicate pushdown**: Filter nodes are pushed as close to the scan as possible. A filter on `orders.total > 100` that logically sits above a join is pushed down to sit directly above the scan of `orders`, so that rows are eliminated before the join materializes them.

**Join algorithm selection**: The default join implementation is NestedLoopJoin. A HashJoin implementation can be selected for equijoins when both input tables are large, since HashJoin is O(N + M) rather than O(N × M).

### The Volcano Iterator Model

Every physical plan node implements the `PlanNode` interface:

```java
public interface PlanNode {
    void open();          // initialize state, open children
    Tuple next();         // return next result tuple, or null if exhausted
    void close();         // release resources, close children
    Schema getOutputSchema();
}
```

This is the **volcano model** (also called the iterator model). The executor calls `open()` on the root node, then calls `next()` in a loop until it returns null. Each `next()` call on the root propagates down the tree: `ProjectionPlan.next()` calls `FilterPlan.next()`, which calls `SeqScanPlan.next()`, which reads the next tuple from the heap file via the buffer pool.

The result is a pull-based pipeline where data flows upward through the tree on demand. No intermediate results are fully materialized (except in `SortPlan`, which must buffer all input before it can return the first sorted tuple).

`SeqScanPlan` maintains a cursor over a `HeapFile`. On each `next()` call it advances to the next non-deleted tuple across all pages of the file.

`IndexScanPlan` opens a `BTreeIterator` positioned at the start of the matching key range. On each `next()` call it calls `iterator.next()` to get the next matching `RecordId`, then uses the `RecordId` to fetch the actual tuple bytes from the heap file via the buffer pool.

`FilterPlan` wraps a child plan. Its `next()` calls the child's `next()` in a loop, evaluating the predicate expression on each tuple, and only returns tuples for which the predicate evaluates to true.

`ProjectionPlan` calls its child's `next()`, then constructs a new `Tuple` containing only the projected columns in the specified order.

`NestedLoopJoinPlan` has two children: the outer (left) table and the inner (right) table. It calls `outer.next()` to get one outer tuple, then calls `inner.open()` + repeated `inner.next()` to scan the entire inner table for each outer tuple, emitting combined tuples where the join predicate holds. `inner.close()` is called before fetching the next outer tuple, and `inner.open()` is called again to reset the inner scan.

---

## Layer 4 — Storage Engine

The storage engine is responsible for organizing data on disk and in memory. It has two independent sub-systems: the heap file manager (for storing actual tuples) and the B+ Tree index (for fast key lookups).

### Disk Manager

`DiskManager` is the only class in the entire system that performs file I/O. It owns a `RandomAccessFile` handle to each `*.db` file. All other components call `DiskManager.readPage(int pageId)` and `DiskManager.writePage(int pageId, byte[] data)`. Page IDs are zero-based integers. The file offset for page N is `N * PAGE_SIZE` (where `PAGE_SIZE` is 4096 bytes, matching the OS virtual memory page size).

`DiskManager.allocatePage()` appends a new zeroed page to the file and returns its ID. This is the only way new storage is created.

The `DiskManager` also manages the WAL file (`*.wal`) for the transaction manager, exposing `appendLogRecord(byte[] record)` and `flushLog()`.

### Page Layout — Slotted Pages

Every page in the database is exactly 4096 bytes. The page layout is a **slotted page** design, which allows variable-length tuples and supports deletion without compaction.

```
Offset 0:
┌──────────────────────────────────────────────────────────────┐
│  Page Header (24 bytes)                                      │
│  pageId          (4 bytes)  — this page's ID                 │
│  numSlots        (2 bytes)  — total slots including deleted  │
│  numLiveSlots    (2 bytes)  — non-deleted slots              │
│  freeSpaceOffset (2 bytes)  — offset where free space starts │
│  nextPageId      (4 bytes)  — next page in heap chain (-1)   │
│  lsn             (8 bytes)  — LSN of last WAL record modifying│
│                               this page (for WAL page flushing)│
├──────────────────────────────────────────────────────────────┤
│  Slot Array (grows toward high offsets)                      │
│  Slot 0: [offset: 2 bytes] [length: 2 bytes]                 │
│  Slot 1: [offset: 2 bytes] [length: 2 bytes]                 │
│  ...                                                         │
│  (a slot with offset=0 and length=0 is a deleted/empty slot) │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│              F R E E    S P A C E                            │
│                                                              │
├──────────────────────────────────────────────────────────────┤
│  Tuple Data (grows toward low offsets, packed from end)      │
│  ...                                                         │
│  [tuple 1 bytes]                                             │
│  [tuple 0 bytes]                                             │
└──────────────────────────────────────────────────────────────┘
Offset 4095
```

The slot array grows from the start of the free space region toward high addresses. Tuple data grows from the end of the page toward low addresses. Free space is the gap between them. Inserting a tuple requires: writing the bytes at the current free space end, decrementing `freeSpaceOffset`, and writing a new slot entry pointing to that offset and length.

Deleting a tuple sets its slot's offset and length to zero (a tombstone). The space is not immediately reclaimed. A page compaction operation (VACUUM) can be triggered to compact deleted space, but it is not required for correctness.

A tuple is identified globally by a `RecordId`, which is a `(pageId, slotId)` pair. RecordIds are stored in B+ Tree leaf nodes to link index entries to actual tuples.

### Buffer Pool Manager

Disk I/O is orders of magnitude slower than memory access. The buffer pool keeps frequently accessed pages in memory so that most operations do not require disk reads.

The buffer pool maintains an array of `Frame` objects, each holding one `Page`. A `pageTable` maps `pageId → frameIndex`. When a page is requested:

1. If `pageTable` contains the pageId, it is a **cache hit** — return the frame's page directly. Update the LRU position.
2. If not, it is a **cache miss** — a free frame must be found. If all frames are occupied, the LRU (least-recently-used) frame is evicted. If the evicted frame's `dirty` flag is true, its page must be written to disk before eviction (if the WAL protocol allows — see the WAL section for the `pageLSN >= flushedLSN` constraint). Then the requested page is read from disk into the now-free frame.

Each frame has:
- `page`: the raw `byte[]` data
- `pageId`: which page it holds
- `dirty`: whether the in-memory copy differs from the disk copy
- `pinCount`: the number of active users of this frame (a pinned frame cannot be evicted)
- `lruTimestamp`: for LRU ordering

Operators pin pages before reading them and unpin them when done. A page is marked dirty by any operation that modifies it. The buffer pool's `flushAll()` is called during checkpoint and shutdown.

The buffer pool capacity is configurable (default: 1000 frames = 4 MB of buffer cache for 4 KB pages).

### Heap Files

A `HeapFile` represents one table's data. It is a linked list of pages on disk. The first page of every heap file is the **header page**, which stores the page count and a free-space bitmap indicating which pages have room for new tuples.

Inserting a tuple: find the first page with enough free space (using the free-space bitmap), pin it from the buffer pool, call `SlottedPage.insert(tupleBytes)`, mark it dirty, unpin it. If no page has space, allocate a new page, insert there, and update the linked list.

Scanning a table: iterate through every page in the heap file chain. For each page, iterate through every non-deleted slot. The `SeqScanPlan` uses a `HeapFileCursor` that remembers `(currentPageId, currentSlotId)` so it can resume after returning a tuple.

### Catalog and Metadata

The `Catalog` is a system-level singleton that tracks all tables and indexes. It stores `TableMetadata` (table name, schema, first heap page ID, row count estimate) and `IndexMetadata` (index name, table name, column name, B+ Tree root page ID).

The catalog's own data is stored in two reserved heap files in the same `.db` file — a tables heap file and an indexes heap file. This means the catalog is itself managed by the storage engine, just like user data.

`Schema` describes the structure of a row: an ordered list of `Column` objects, each with a name, `DataType`, whether it is nullable, and whether it is the primary key.

### B+ Tree Index

The B+ Tree is the most algorithmically complex component. It provides O(log N) point lookups and O(log N + K) range scans where K is the number of matching keys.

The tree is a **B+ Tree** specifically: all data (key → RecordId pairs) lives in leaf nodes. Internal nodes hold only routing keys and child page pointers. This property is what makes range scans efficient — the leaf nodes are linked in sorted order by sibling pointers, so a range scan traverses a contiguous chain of leaf pages without touching internal nodes.

#### Node Structure

Every B+ Tree node maps to exactly one page, managed by the buffer pool. The page's bytes encode either an internal node or a leaf node, determined by a flag in the first byte.

The **order** of the tree, `d`, determines the capacity of nodes:
- Maximum keys per internal node: `2d`
- Maximum keys per leaf node: `2d`
- Minimum keys per non-root node: `d` (the half-full invariant)
- Each internal node with `k` keys has `k + 1` child pointers.

An internal node page layout:
```
[type: 1 byte = INTERNAL]
[numKeys: 2 bytes]
[keys: numKeys × keySize bytes]
[childPageIds: (numKeys + 1) × 4 bytes]
```

A leaf node page layout:
```
[type: 1 byte = LEAF]
[numKeys: 2 bytes]
[prevLeafPageId: 4 bytes]   ← for reverse range scans
[nextLeafPageId: 4 bytes]   ← for forward range scans
[entries: numKeys × (keySize + 8) bytes]
  each entry: [key bytes][pageId: 4 bytes][slotId: 4 bytes]
```

#### Search

To find the `RecordId` for a given key:

1. Start at the root page.
2. Deserialize the page into a `BTreeNode`.
3. If it is an internal node, binary search the keys array to find the correct child pointer. For key `k`, if `k < keys[i]` for the first `i`, follow `children[i]`. This is the standard B-Tree routing rule.
4. Pin the child page, unpin the current page, and repeat from step 2.
5. When a leaf node is reached, binary search its entries for the exact key. If found, return the stored `RecordId`. If not found, return null.

The height of the tree is typically 3–4 for millions of rows, so a point lookup reads 3–4 pages from disk (or cache hits if the upper nodes are hot in the buffer pool).

#### Insertion and Node Splitting

To insert `(key, recordId)`:

1. Traverse from root to the correct leaf, keeping a stack of `(pageId, indexInParent)` pairs for the path.
2. Insert the entry into the leaf in sorted order.
3. If the leaf now has `2d + 1` entries (overflow), split it: create a new leaf, move the upper half of entries to it, link it into the sibling chain, and push the first key of the new leaf up to the parent.
4. If the parent internal node also overflows after receiving the pushed-up key, split it too: create a new internal node, move the upper half of its keys and children to it, and push the **middle key** up to its parent. (Note: unlike leaf splits, the middle key is pushed up and not kept in either child.)
5. Continue recursively upward. If the root splits, a new root is created and the tree height increases by one.

This split propagation is handled by working back up the path stack after the initial leaf insertion.

#### Deletion and Rebalancing

To delete a key:

1. Traverse to the correct leaf and remove the entry.
2. If the leaf now has fewer than `d` entries (underflow), attempt to **borrow** from a sibling leaf: if the left sibling has more than `d` entries, rotate the rightmost entry of the left sibling into this leaf and update the parent's routing key. If borrowing is not possible, **merge** this leaf with a sibling: move all entries into one leaf, update the sibling pointer chain, and remove the now-empty leaf's routing key from the parent.
3. If the parent internal node underflows after key removal, apply the same borrow-or-merge logic recursively.
4. If the root ends up with zero keys after a merge, the root's only child becomes the new root and the tree height decreases by one.

#### Range Scans

To scan all keys in `[lo, hi]`:

1. Search for the leftmost position where the first key >= `lo`. This lands on a leaf node.
2. Iterate forward through that leaf's entries, collecting matching RecordIds.
3. When the end of the leaf is reached, follow `nextLeafPageId` to the next leaf and continue.
4. Stop when the current key exceeds `hi` or `nextLeafPageId` is -1.

The linked leaf layer makes this O(log N + K) where K is the number of results. Without the leaf-linking, you would have to traverse back through internal nodes for every leaf boundary crossing.

---

## Layer 5 — Transaction Manager (ACID)

ACID (Atomicity, Consistency, Isolation, Durability) is what separates a database from a filesystem. Each property is implemented by a distinct mechanism.

### Atomicity — Write-Ahead Log

Atomicity means a transaction either completes fully or has no effect at all. If the process crashes in the middle of a transaction, the database must be left as if that transaction never started.

This is implemented with a **Write-Ahead Log (WAL)**. The rule is: before any modified page is written to disk, the log record describing that modification must already be on disk. This ordering guarantee means that if a crash occurs, the log is always at least as up-to-date as the data files, which makes recovery possible.

Every modification produces a `LogRecord`:

```
LogRecord fields:
  lsn          (8 bytes) — log sequence number, monotonically increasing
  prevLsn      (8 bytes) — LSN of the previous record by the same txn (forms a chain for UNDO)
  txId         (4 bytes) — which transaction wrote this record
  type         (1 byte)  — BEGIN | UPDATE | COMMIT | ABORT | COMPENSATION | CHECKPOINT
  pageId       (4 bytes) — for UPDATE records, which page was modified
  slotId       (2 bytes) — for UPDATE records, which slot
  beforeImage  (varies)  — the bytes of the slot before modification (for UNDO)
  afterImage   (varies)  — the bytes of the slot after modification (for REDO)
```

The `lsn` of the most recent log record that modified a page is stored in the page header as `pageLSN`. This is used during recovery to know whether a page's on-disk state already reflects a given log record.

Each page also stores its `pageLSN` in its header. The buffer pool enforces the WAL protocol: it will not flush a dirty page to disk if `page.pageLSN > walManager.getFlushedLSN()`. In other words, the log record must reach disk before the page it describes.

### Consistency — Schema Enforcement

Consistency means the database is always in a valid state according to its schema constraints. This is enforced by the semantic analyzer and executor before any writes reach the storage layer:

- **Type checking**: values being inserted or used in WHERE clauses must match the column's declared `DataType`.
- **NOT NULL enforcement**: null values are rejected for columns declared NOT NULL.
- **Primary key uniqueness**: before an INSERT, the B+ Tree index on the primary key column is checked for a duplicate entry. If found, the insert is rejected with a `ConstraintViolationException`.
- **Foreign key checks** (if implemented): referenced keys must exist in the referenced table.

### Isolation — Two-Phase Locking

Isolation means concurrent transactions do not interfere with each other. The specific isolation level implemented is **serializable** — the outcome of running N concurrent transactions is equivalent to running them in some serial order.

This is achieved with **strict Two-Phase Locking (S2PL)**:

**Lock modes**: There are two lock modes. A **shared lock (S-lock)** allows reading a resource. Multiple transactions may hold S-locks on the same resource simultaneously. An **exclusive lock (X-lock)** allows writing a resource. An X-lock is incompatible with any other lock — neither another X-lock nor an S-lock.

**Compatibility matrix**:
```
           Holder: S-lock    Holder: X-lock
Request: S-lock    GRANT        WAIT
Request: X-lock    WAIT         WAIT
```

**Two phases**:
- **Growing phase**: a transaction may acquire locks but may not release any.
- **Shrinking phase**: a transaction may release locks but may not acquire new ones.

In **strict 2PL** (which HelixDB uses), the shrinking phase only begins at transaction end — all locks are held until `COMMIT` or `ROLLBACK`. This is stricter than basic 2PL but prevents cascading aborts.

**Lock granularity**: Locks are acquired at the **tuple level** (by `RecordId`). This gives fine-grained concurrency — two transactions updating different rows in the same table do not block each other.

**Deadlock detection**: When a transaction must wait for a lock, it registers its wait in a `waits-for` graph: an edge `T1 → T2` means T1 is waiting for a resource held by T2. A background thread periodically checks this graph for cycles. If a cycle is found (deadlock), one transaction in the cycle is chosen as the victim and aborted. The chosen victim is typically the one with the fewest locks held (i.e. the one that has done the least work and is cheapest to abort).

The `LockManager` maps each `RecordId` to a `LockTable` entry that records the current lock mode and a queue of waiting requests. When a lock is released, the lock manager grants it to the next compatible request in the queue and wakes up the waiting thread.

### Durability — Force Flush on Commit

Durability means that once a transaction has committed, its effects survive any subsequent crash. This is enforced by the `COMMIT` protocol:

1. Write a `COMMIT` log record to the in-memory WAL buffer.
2. Call `walManager.flush()` — this calls `FileChannel.force(true)` which issues an `fsync` to the OS, guaranteeing the WAL log up to and including the COMMIT record has hit physical disk.
3. Only after the fsync returns successfully does the `COMMIT` call return to the client.

This means that even if the machine loses power one millisecond after the client receives the COMMIT confirmation, the COMMIT log record is on disk and the transaction's effects will be fully recovered on restart.

Data pages themselves do not need to be flushed at commit time. They can be flushed lazily by a background writer thread. The WAL log is sufficient to reconstruct them.

### Crash Recovery — REDO and UNDO

When the database starts up, the `RecoveryManager` runs before accepting any connections. It replays the WAL to bring the database to a consistent state after a crash. The algorithm is a simplified version of the ARIES (Algorithm for Recovery and Isolation Exploiting Semantics) protocol:

**Phase 1 — Analysis**: Scan the WAL forward from the last checkpoint. Build a table of which transactions were active at the time of the crash (those that have a BEGIN record but no COMMIT or ABORT record). Also build a list of dirty pages — pages that were modified but may not have been flushed to disk.

**Phase 2 — REDO**: Scan the WAL forward from the earliest LSN of any dirty page. For each UPDATE log record, check if `page.pageLSN >= record.lsn`. If yes, the page already reflects this change (it was flushed after the log was written) — skip it. If no, apply the `afterImage` to the page. This brings all committed changes back into the data files.

**Phase 3 — UNDO**: For each transaction that was active at crash time (from the analysis phase), follow its `prevLsn` chain backward through the log, applying `beforeImage` to each modified page. For each undo operation, write a **Compensation Log Record (CLR)** to the WAL before applying the undo. CLRs are never themselves undone — this prevents infinite undo loops.

After UNDO completes, write ABORT records for each rolled-back transaction to close them in the log.

---

## Data Types

HelixDB supports four native data types:

| Type | Java mapping | Storage | Notes |
|---|---|---|---|
| `INT` | `int` | 4 bytes | 32-bit signed integer |
| `BIGINT` | `long` | 8 bytes | 64-bit signed integer |
| `FLOAT` | `double` | 8 bytes | IEEE 754 double precision |
| `VARCHAR(n)` | `String` | 2 + n bytes | 2-byte length prefix + UTF-8 bytes, max n chars |
| `BOOLEAN` | `boolean` | 1 byte | 0x00 = false, 0x01 = true |

Null values are tracked via a **null bitmap** at the start of each tuple — one bit per column. This allows null to be represented for any type without using sentinel values.

Tuple serialization is handled by `TupleSerializer`, which uses `java.nio.ByteBuffer` for encoding and decoding column values in the order they appear in the schema.

---

## Supported SQL Syntax

```sql
-- DDL
CREATE TABLE users (
    id     INT PRIMARY KEY,
    name   VARCHAR(100) NOT NULL,
    age    INT,
    active BOOLEAN
);

DROP TABLE users;

CREATE INDEX idx_age ON users (age);
DROP INDEX idx_age;

-- DML
INSERT INTO users (id, name, age, active) VALUES (1, 'Alice', 30, TRUE);

SELECT id, name, age
FROM users
WHERE age > 25 AND active = TRUE
ORDER BY age DESC
LIMIT 10;

SELECT u.name, o.total
FROM users u
JOIN orders o ON u.id = o.user_id
WHERE o.total > 100;

UPDATE users SET age = 31 WHERE id = 1;

DELETE FROM users WHERE active = FALSE;

-- Aggregate functions
SELECT COUNT(*), AVG(age), MIN(age), MAX(age) FROM users;

-- Transactions
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
UPDATE accounts SET balance = balance + 100 WHERE id = 2;
COMMIT;

BEGIN;
DELETE FROM users WHERE id = 99;
ROLLBACK;
```

---

## Concurrency Model

HelixDB uses Java threads to simulate concurrent clients. Each `Connection` runs in its own thread and holds its own `Transaction` object. Thread-safety is maintained at the following boundaries:

- **Buffer Pool**: `synchronized` methods on `fetchPage`, `markDirty`, and `evict`. Pin counts use `AtomicInteger`.
- **LockManager**: A `ReentrantLock` guards each `LockTable` entry. Waiting threads call `Condition.await()` and are woken by `Condition.signalAll()` when a lock is released.
- **WAL Manager**: Log writes use a `synchronized` append method. The WAL buffer is flushed with an exclusive lock. LSN generation uses `AtomicLong.getAndIncrement()`.
- **Catalog**: Read-write lock (`ReentrantReadWriteLock`) — multiple readers allowed, exclusive access for CREATE TABLE / DROP TABLE.
- **Deadlock detector**: runs as a daemon thread every 100ms, acquiring a global lock on the waits-for graph only during cycle detection.

---

## On-Disk File Format

A database named `mydb` creates the following files:

```
mydb.db    — the main data file (heap pages for all tables + B+ Tree pages)
mydb.wal   — the write-ahead log (append-only, never overwritten mid-log)
```

There is one `*.db` file for the entire database. Page IDs are globally unique across all tables and indexes. The catalog records the root page of each table's heap file and each index's B+ Tree.

The WAL file is a sequential binary file. Log records are written in append order. During a checkpoint, completed log records up to a safe LSN are truncated from the front of the WAL. The checkpoint process: flush all dirty pages, write a CHECKPOINT log record containing the LSN and the current dirty page table, then truncate the log up to that point.

---

## Zero External Dependencies — Design Philosophy

HelixDB uses only the Java standard library. There are no Maven or Gradle dependencies beyond the JDK itself. No Guava, no Apache Commons, no Netty, no logging frameworks.

This is a deliberate constraint that forces every data structure and algorithm to be written from scratch. The B+ Tree is not from a library. The SQL parser is not from ANTLR. The serialization is not from Protobuf. The lock manager is built on raw `java.util.concurrent` primitives.

The only Java standard library packages used are:

- `java.io` and `java.nio` for file I/O (`RandomAccessFile`, `FileChannel`, `ByteBuffer`)
- `java.util` for `LinkedHashMap` (used for LRU eviction), `ArrayList`, `HashMap`, `TreeMap`
- `java.util.concurrent` for `ReentrantLock`, `ReentrantReadWriteLock`, `Condition`, `AtomicLong`, `AtomicInteger`, `ConcurrentHashMap`
- `java.util.logging` for structured logging (no third-party log framework)

No reflection, no serialization frameworks, no annotation processors.

---

## Known Limitations and Intentional Simplifications

These are conscious trade-offs made to keep the implementation tractable while preserving correctness:

- **Single-file database**: all data is in one `.db` file. Production databases use separate files per tablespace and per table to control file growth and I/O scheduling.
- **No query cache**: every call to `execute()` re-parses and re-plans the query. A production system compiles prepared statements once and reuses plans.
- **Simple cost model**: the physical planner uses a basic row-count threshold for index vs sequential scan decisions. A production optimizer uses histogram statistics, multi-dimensional selectivity estimation, and dynamic programming for join ordering.
- **NestedLoopJoin only by default**: HashJoin is implemented as an optional upgrade. MergeJoin is not implemented.
- **No MVCC**: the isolation level is serializable via strict 2PL, which means readers block writers and writers block readers. MVCC (Multi-Version Concurrency Control, used by Postgres) allows readers and writers to not block each other by keeping multiple versions of each row. This is a significant complexity addition.
- **No online compaction**: deleted tuple space is not reclaimed automatically. A manual VACUUM operation can compact pages but is not triggered automatically.
- **No network layer**: connections are in-process Java objects. There is no TCP socket server, no wire protocol, no remote client support.
- **Page size is fixed at 4096 bytes**: configuring page size at database creation time (as Postgres allows) is not supported.

---

## Concepts You Will Deeply Understand After Reading This

Working through this codebase — reading it, modifying it, breaking it, and fixing it — builds an intuition for:

**Storage**: why databases use fixed-size pages, why slotted pages allow variable-length tuples without fragmentation, why the buffer pool is the single most performance-critical component, why LRU is the right eviction policy for most workloads but not all (sequential scan thrashing), why B+ Trees are preferred over B-Trees for range queries, why internal node keys are just routing guides and all the real data is in leaves.

**Querying**: why indexes speed up equality and range queries but not arbitrary function predicates, why predicate pushdown matters, why the volcano model is elegant and where it bottlenecks (deep call stacks, no vectorization), why sort is special (must materialize all input), why hash joins beat nested loop joins for large tables but lose for small ones.

**Transactions**: why WAL must be flushed before the data page it describes (not after — think about what recovery looks like if it's after), why COMMIT requires an fsync and not just a memory write, why strict 2PL prevents dirty reads, why it can deadlock and basic 2PL cannot (hint: locks are held longer), what makes ARIES crash recovery correct even when a recovery process crashes mid-recovery (idempotent REDO, CLRs for safe UNDO), why checkpoints exist and what would happen without them (WAL grows forever).

**Java concurrency**: why `ReentrantReadWriteLock` is right for the catalog but `synchronized` is sufficient for the buffer pool, why `AtomicLong` is right for LSN generation without locking, why `Condition.await()` inside a `while` loop (not `if`) is mandatory for lock waiting.

---

*HelixDB is an educational implementation. It is correct, complete, and covers every concept present in production-grade relational databases — but it is not optimised for production use.*
