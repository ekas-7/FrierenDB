# Implementation Progress

## Direction (decided)

Rewrite the database core as an independent Java 21 engine in `frierendb/`, then build a natural-language-to-SQL agent on top of it, in this repository.

Assumptions made without asking, so they can be challenged:

- Maven multi-module build, `release 21`, JUnit 5, `-Xlint:all -Werror`; the engine has no runtime dependencies, the agent depends on Jackson only.
- Hand-written recursive-descent SQL parser (no JavaCC step).
- LLM access through a `ChatModel` interface: a deterministic scripted model for tests and an OpenAI-compatible HTTP client (covers OpenAI and local servers such as Ollama). Anthropic or other native APIs would need a separate adapter.
- Spring Boot is deferred until there is an API worth serving.

## Current stage

Engine and agent exist end to end, with B+ tree indexes and a write-ahead log with transactions and crash recovery. Remaining: live-model validation and the items under "Known issues and limitations".

## Completed milestones

| # | Milestone | Validation |
|---|---|---|
| 1 | Java 21 Maven skeleton with JDK enforcement | `mvn test` passes on JDK 21; the enforcer rejects JDK 26 |
| 2 | Value types, schema, binary row codec | Unit tests |
| 3 | Storage: page file, buffer pool, slotted pages, heap file | Unit tests |
| 4 | Persistent catalog (atomic `catalog.bin`, validated table names) | Unit tests |
| 5 | SQL lexer, parser, sealed AST | 27 parser tests |
| 6 | Planner and executor, writes, `EXPLAIN`, read-only query mode with limits | 65 tests (queries, writes, safety, value semantics) |
| 7 | Agent core: tools, bounded loop, OpenAI-compatible client | 39 tests, incl. a simulated compromised model and a local `HttpServer` |
| 8 | CLI, demo dataset, evaluation harness | 16 tests; CLI smoke-tested by piping commands into `Main` |
| 9 | Documentation: HLD, build guide, LLD reference, guides | The Maven and CLI commands quoted in the docs were run; every relative link checked; byte-level examples and `EXPLAIN` output taken from the real code |
| 10 | B+ tree, `CREATE`/`DROP INDEX`, index maintenance, rule-based `IndexScan` planning | `BPlusTreeTest` (12, incl. random operations vs a `TreeMap`), `CatalogIndexTest` (10), `SqlIndexDdlTest` (8), `SqlIndexPlanTest` (9, incl. random predicates vs an unindexed oracle), parser tests; plan text checked through the CLI |
| 11 | Write-ahead log, transactional buffer pool, recovery | `WalTest` (9), `BufferPoolTransactionTest` (12), `RecoveryTest` (11, incl. the log cut at every record boundary), `PageFile`/`HeapFile` additions |
| 12 | `BEGIN`/`COMMIT`/`ROLLBACK`, atomic statements, recovery on open | `SqlTransactionTest` (17), `CrashRecoveryTest` (8: directory copies of a live database, log cuts, random workloads vs an oracle), parser tests; behaviour checked through the CLI |

Full reactor: 266 engine tests and 55 agent tests, all passing (`mvn clean test`, JDK 21). Several tests were checked by mutation: breaking the write-ahead logging, rollback's log undo, recovery's undo or redo, recovery at open, the post-rollback catalog reload, or the child-selection rule makes the named tests fail.

## Known issues and limitations

- Indexes: single-column and non-unique; `NULL` is not indexed; `TEXT` keys are limited to 255 UTF-8 bytes; deletion never merges or rebalances nodes. The planner is rule-based with no statistics, narrows only the `FROM` table, ignores `OR`/`IN`/`LIKE`/`<>` and expressions over a column, and does not use index order for `ORDER BY`. No benchmark has been run, so no speed-up is claimed.
- Transactions: one session and one open transaction at a time; no locks or isolation (a read-only `query` sees an open transaction's uncommitted changes). `CREATE`/`DROP` are not transactional and are refused inside a transaction (they checkpoint first). A statement that fails after changing data inside a transaction rolls the whole transaction back.
- Write-ahead log: whole-page before and after images, so the log is large (checkpointed after about 4 MiB by default, on `CREATE`/`DROP` and on close). Crash tests copy the directory of a live database; they do not model a disk that reorders or loses forced writes, or a torn write inside one page. Joins build an in-memory hash table of the right side; non-equi joins use a nested loop.
- Records are limited to 4088 bytes (no overflow pages).
- `VARCHAR(n)` length is parsed but not enforced. No `PRIMARY KEY` or `UNIQUE` constraints.
- SQL gaps: subqueries, CTEs, `UNION`, `CASE`, `OFFSET`, window functions, comma joins.
- The agent has never been run against a live LLM. The evaluation harness is validated only with scripted models (a model that replays the reference SQL scores 12/12; wrong or non-querying models score 0). Real accuracy is unknown.
- Only OpenAI-compatible chat-completions endpoints are supported.
- The evaluation set is 12 questions on one small hand-written dataset; it is a smoke test, not a benchmark.
- Maven defaults to JDK 26 on this machine, so builds must set `JAVA_HOME` to JDK 21 (the enforcer fails fast otherwise).

## Next milestones

1. Run `--eval` against a real model, record the result with model name and date, and fix what it shows.
2. Measure index and commit performance honestly (workload, hardware, JDK, repetitions) before quoting any number.
3. Optional: index merge/rebalance on delete, multi-column and unique indexes, index-ordered `ORDER BY`, statistics, record-level (logical) logging, locking.
4. Optional REST API and structured logging.

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd frierendb && mvn clean test
```
