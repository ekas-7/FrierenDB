# 06 - Limitations and Roadmap

Everything under "Roadmap (not built)" is a design sketch, not a feature. Nothing there is claimed to work. Items R2 and R3 from the first version of this document are now built; they are described in [07](07-indexes-and-index-aware-planning.md) and [08](08-transactions-and-recovery.md), and their limits are listed below.

## Known limitations (built, with caveats)

| Area | Limitation |
|---|---|
| Durability | Committed work survives a crash (write-ahead log, recovery on open). The log holds whole pages, so it is large; it is emptied by a checkpoint (default threshold about 4 MiB, on `CREATE`/`DROP`, on close). Crash tests copy a live directory; they do not model reordered or lost forced writes, or a torn write inside one page. |
| Transactions | One session, one open transaction. No locks, no isolation: a read-only `query` sees an open transaction's uncommitted changes. `CREATE`/`DROP` are refused inside a transaction and cannot be rolled back. A statement that fails after changing data inside a transaction rolls the whole transaction back. |
| Capacity | Record size ≤ 4088 bytes (page size 4096 minus a 4-byte header and a 4-byte slot). `TEXT` values count toward that. |
| Performance | A table is scanned in full unless the `WHERE` has a usable comparison on an indexed column of the `FROM` table. Joins build an in-memory hash table of the entire right side. No benchmark has been run, so no speed figure is claimed. |
| Indexes | One column, non-unique, `NULL` not indexed, `TEXT` keys at most 255 UTF-8 bytes. Delete never merges or rebalances nodes. Rule-based: no statistics, so an available index is always used. Not used for `OR`, `IN`, `LIKE`, `<>`, expressions over the column, joined tables, or `ORDER BY`. |
| Concurrency | One statement at a time. No isolation between threads beyond method-level locks. |
| SQL | No subqueries, CTEs, `UNION`, `CASE`, `OFFSET`, window functions, comma joins, `ALTER`, `PRIMARY KEY`/`UNIQUE`/`FOREIGN KEY`/`DEFAULT`, unique or multi-column indexes. `VARCHAR(n)` is parsed but the length is ignored. |
| Identifiers | Table names limited to `[A-Za-z_][A-Za-z0-9_]{0,63}`; they are stored lower-case. Column lookups are case-insensitive. |
| Types | `INT` is 64-bit; no dates, decimals, blobs. |
| Agent | Only OpenAI-style endpoints; never run against a live model in this repo; 12 evaluation questions on one small dataset. |
| Packaging | No fat jar; run from class directories. |
| Scans | `HeapFile.scan` visits only the pages that existed when it started. |

## Roadmap (not built)

### R1. Evaluate against a real model
Run `--eval` with a model, record model name, date, score and failures in `docs/`. Fix the prompt or tools based on observed failures. This is the highest-value next step because it replaces assumptions with measurements.

### R2. B+ tree index: built
See [07](07-indexes-and-index-aware-planning.md), [`../lld/08-index-internals.md`](../lld/08-index-internals.md) and [build step 21](../build-guide/21-indexes-sql-and-planning.md). Possible follow-ups, none started: merge or redistribute on delete, unique and multi-column indexes, index-ordered `ORDER BY` (skip the sort), index nested-loop joins, statistics and a cost model, an index-only scan.

### R3. Transactions and write-ahead log: built (single session)
See [08](08-transactions-and-recovery.md), [`../lld/09-wal-and-recovery-internals.md`](../lld/09-wal-and-recovery-internals.md) and [build step 22](../build-guide/22-write-ahead-log-and-recovery.md). Possible follow-ups, none started:
1. Record-level (logical) logging with an LSN on every page, replacing whole-page images and shrinking the log.
2. Concurrency: a global lock first, then table or row locks, and isolation levels. This also needs overlapping transactions in the log and a fuller recovery (analysis, redo, undo with compensation records), because the current argument relies on only the last transaction being unfinished.
3. Transactional schema changes (log file creation and deletion).
4. Group commit and a log-force benchmark.

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
2. Then pick by the pain you observe: slow queries on larger data → measure first, then the R2 follow-ups; concurrency needs → R3 follow-up 2; unsupported question shapes → R5.
