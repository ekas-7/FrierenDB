# 06 - Limitations and Roadmap

Everything under "Not built" is a design sketch, not a feature. Nothing below is claimed to work.

## Known limitations (built, with caveats)

| Area | Limitation |
|---|---|
| Durability | No write-ahead log. Data is on disk after each statement returns, but a crash *during* a statement may corrupt a table. |
| Capacity | Record size ≤ 4088 bytes (page size 4096 minus a 4-byte header and a 4-byte slot). `TEXT` values count toward that. |
| Performance | Every table access is a full scan. Joins build an in-memory hash table of the entire right side. |
| Concurrency | One statement at a time. No isolation between threads beyond method-level locks. |
| SQL | No subqueries, CTEs, `UNION`, `CASE`, `OFFSET`, window functions, comma joins, `ALTER`, indexes, `PRIMARY KEY`/`UNIQUE`/`FOREIGN KEY`/`DEFAULT`. `VARCHAR(n)` is parsed but the length is ignored. |
| Identifiers | Table names limited to `[A-Za-z_][A-Za-z0-9_]{0,63}`; they are stored lower-case. Column lookups are case-insensitive. |
| Types | `INT` is 64-bit; no dates, decimals, blobs. |
| Agent | Only OpenAI-style endpoints; never run against a live model in this repo; 12 evaluation questions on one small dataset. |
| Packaging | No fat jar; run from class directories. |
| Scans | `HeapFile.scan` visits only the pages that existed when it started. |

## Roadmap (not built)

### R1. Evaluate against a real model
Run `--eval` with a model, record model name, date, score and failures in `docs/`. Fix the prompt or tools based on observed failures. This is the highest-value next step because it replaces assumptions with measurements.

### R2. B+ tree index
Sketch:
1. A new `index` package with `BPlusTree` over `Value` keys storing `RecordId`s, built on the existing `PageFile` + `BufferPool` (each node is a page).
2. `CREATE INDEX` statement; the catalog stores index definitions (bump the catalog `VERSION`).
3. Maintain indexes in `Table.insert/update/delete`.
4. In `Planner`, replace `Scan + Filter` with `IndexScan` when the predicate is `col = const` or a range on an indexed column; show it in `EXPLAIN`.
Tests to write first: ordered iteration, splits, duplicate keys, delete, persistence, and an equivalence test (indexed and unindexed plans return the same rows).

### R3. Transactions and write-ahead log
Sketch:
1. A log file of records (begin, update with before/after image, commit, abort), each with an LSN; pages store the LSN of their last change.
2. Write-ahead rule: flush the log up to a page's LSN before writing the page (the `BufferPool` eviction path needs a hook).
3. Recovery: analysis, redo, undo on open.
4. Concurrency: start with a global lock, then table-level and row-level locks.
This is the largest item; it changes `BufferPool`, `HeapFile`, `Executor` and `Database`.

### R4. Overflow pages
Chain pages for records > 4088 bytes (large `TEXT`).

### R5. More SQL
In order of value to analytics: `CASE`, `OFFSET`, subqueries in `WHERE ... IN (SELECT ...)`, `UNION`, CTEs, `PRIMARY KEY`/`UNIQUE`. Each needs parser, AST, planner and tests.

### R6. Optimizer
Statistics per table, selectivity estimates, join ordering, build-side choice.

### R7. Other providers
Native Anthropic or Gemini adapters implementing `ChatModel`.

### R8. Service mode
A REST layer (for example Spring Boot) exposing `ask` with authentication, rate limiting and structured request logging. Not needed for the embedded use case.

### R9. Observability
Structured logs of agent traces; counters for tokens, latency and failures.

## How to choose the next step

1. R1 first: it is cheap and tells you what actually matters.
2. Then pick by the pain you observe: slow queries → R2; data loss worries → R3; unsupported question shapes → R5.
