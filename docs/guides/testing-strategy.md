# Testing Strategy

## Principles

1. **Tests are the specification.** Write the expected behaviours first; implementation follows.
2. **Deterministic by construction.** No network, no real clock-dependent assertions beyond generous bounds, no LLM. The agent is tested with a scripted model and a local HTTP server.
3. **Test the contract at the right level.** Low-level classes get focused unit tests; SQL semantics are tested through `Database`, which is how users reach them.
4. **Test the dangerous paths** (corruption, limits, hostile input) as carefully as the happy paths.
5. **Never disable a test or a lint to get green.** Fix the cause or remove the feature.

## Layers and counts

At the time of writing, `mvn clean test` runs **321 tests**: 266 in the engine, 55 in the agent.

| Layer | Test class | Tests | Style |
|---|---|---|---|
| Build | `BuildEnvironmentTest` | 1 | Asserts JDK 21 |
| Types | `ValueTest` | 8 | Pure unit |
| Schema | `SchemaTest`, `RowCodecTest` | 5 + 11 | Pure unit, byte-level |
| Storage | `PageFileTest`, `BufferPoolTest`, `SlottedPageTest`, `HeapFileTest` | 7 + 9 + 11 + 10 | Unit on temp files (`@TempDir`) |
| Write-ahead log | `WalTest`, `BufferPoolTransactionTest`, `RecoveryTest` | 9 + 12 + 11 | Unit; crashes simulated by copying live files; log cut at many offsets |
| Index | `BPlusTreeTest` | 12 | Unit plus random operations against a `TreeMap` oracle |
| Catalog | `CatalogTest`, `CatalogIndexTest` | 11 + 10 | Temp directory, reopen scenarios, a hand-built v1 catalog |
| Parser | `ParserTest` | 32 | Parse text, assert AST and errors |
| Values/semantics | `SqlValuesTest` | 10 | Pure unit |
| Queries | `SqlQueryTest` | 31 | End to end through `Database` on a fixed dataset |
| Writes | `SqlWriteTest` | 16 | End to end, includes reopen and larger-than-cache data |
| Indexes in SQL | `SqlIndexDdlTest`, `SqlIndexPlanTest` | 8 + 9 | DDL; plan text; random predicates against an unindexed oracle; DML workload |
| Transactions | `SqlTransactionTest` | 17 | Semantics through `Database` |
| Crash recovery | `CrashRecoveryTest` | 8 | Copy of a live database directory, log cuts, random workloads against a map oracle |
| Safety | `QuerySafetyTest` | 8 | Limits and read-only enforcement |
| Agent model | `OpenAiCompatibleModelTest` | 12 | Real local `HttpServer` |
| Agent tools | `DatabaseToolsTest` | 11 | Tools against a real database |
| Agent loop | `AnalystAgentTest` | 16 | `ScriptedChatModel` |
| CLI | `ReplTest` | 7 | Streams in memory |
| Demo/eval | `DemoDataTest`, `EvalRunnerTest` | 1 + 8 | Harness validated with scripted models |

## Fixtures

- `TestDatabases` (engine test-jar): a small university database with **binary-exact GPAs** (3.75, 3.25, 3.5, ...), so floating-point results can be compared as strings without rounding issues. `rows(db, sql)` runs a query and returns rows rendered as strings (`NULL` included).
- `ScriptedChatModel` (agent tests): scripted `ModelResponse`s or exceptions; records every request so tests can assert what the model was shown.
- JUnit `@TempDir` for every on-disk test; nothing writes to the working directory.

## Patterns used

- **Round trip**: encode then decode (codec), write then reopen (storage, catalog, writes).
- **Equivalence**: hash join and nested loop produce the same rows; equal numbers of different types hash alike.
- **Negative space**: assert what must *not* happen (data unchanged after a blocked write; no key in an error message; no pinned pages after a scan).
- **Boundary values**: the largest storable record, `Long.MIN_VALUE`, empty text, 20+ columns for the null bitmap, 5000 rows through a tiny buffer pool.
- **Simulated adversary**: a scripted model that tries `DROP TABLE`, and data that contains instructions.
- **Time-bounded**: limit tests assert "stopped within a few seconds", not exact timings.
- **Oracle**: compare the fast path with a slow trivially-correct one over random input (B+ tree vs `TreeMap`; index plan vs the same query with the index defeated by `col + 0`; recovered database vs a plain map).
- **Crash by copying**: copy the directory of an open database without closing it, then open the copy. Pair a short log only with data files that could really exist at that point.
- **Mutation check**: temporarily break the mechanism (skip the undo, skip recovery, drop the write-ahead logging) and confirm that a test fails; then restore it. A test that cannot fail proves nothing. One regression test (`pagesAllocatedByARolledBackTransactionLeaveNoStaleFreeSpaceBehind`) was added precisely because an earlier test survived removing `catalog.reload()`.

## Running tests

```bash
export JAVA_HOME=/path/to/jdk-21
cd frierendb
mvn clean test                                  # everything
mvn -pl engine test                             # engine only
mvn -pl agent -am test                          # agent plus the engine it needs
mvn -pl engine -Dtest=ParserTest test           # one class
```

`-am` ("also make") builds the engine in the same reactor, which the agent needs for its test-jar. Running `mvn -pl agent test` alone fails unless the engine artifacts are installed in your local repository; avoid `install` and use the reactor instead.

## What the tests do **not** prove

- Behaviour of a real disk under power loss. Crash tests copy the files of an open database, which models lost data pages and a forced log. They do not model a disk that reorders or loses forced writes, or a torn write inside one 4 KiB page.
- That the log is *efficient*. No benchmark exists for the log or for indexes.
- Concurrency (single-threaded tests only).
- Performance (no benchmarks are claimed or recorded).
- Accuracy of any real LLM (the harness is validated, not a model).
- Compatibility with every OpenAI-compatible server (only the protocol shape is tested).

## Adding a test: checklist

1. Name it as a sentence about behaviour (`deletedSpaceIsReusedInsteadOfGrowingTheFile`).
2. Arrange with the smallest data that shows the behaviour.
3. Assert exact values (not just "no exception").
4. If it covers a bug, make sure it **fails without the fix**.
5. Keep it independent: it must pass alone and in any order.
