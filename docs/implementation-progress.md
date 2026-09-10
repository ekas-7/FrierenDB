# Implementation Progress

## Direction (decided)

Rewrite the database core as an independent Java 21 engine in `frierendb/`, then build a natural-language-to-SQL agent on top of it, in this repository.

Assumptions made without asking, so they can be challenged:

- Maven multi-module build, `release 21`, JUnit 5, `-Xlint:all -Werror`; the engine has no runtime dependencies, the agent depends on Jackson only.
- Hand-written recursive-descent SQL parser (no JavaCC step).
- LLM access through a `ChatModel` interface: a deterministic scripted model for tests and an OpenAI-compatible HTTP client (covers OpenAI and local servers such as Ollama). Anthropic or other native APIs would need a separate adapter.
- Spring Boot is deferred until there is an API worth serving.

## Current stage

Engine and agent exist end to end. Remaining: live-model validation, indexes, transactions/WAL, long-form documentation.

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

Full reactor: 162 engine tests and 55 agent tests, all passing (`mvn clean test`, JDK 21).

## Known issues and limitations

- No write-ahead log. Data is durable only after flush; a crash can lose or corrupt recent writes.
- No indexes; every scan is a full heap scan. Equi-joins use a hash join, others a nested loop.
- No transactions or concurrency control beyond method-level thread safety in the buffer pool.
- Records are limited to 4088 bytes (no overflow pages).
- `VARCHAR(n)` length is parsed but not enforced. No `PRIMARY KEY` or `UNIQUE` constraints.
- SQL gaps: subqueries, CTEs, `UNION`, `CASE`, `OFFSET`, window functions, comma joins.
- The agent has never been run against a live LLM. The evaluation harness is validated only with scripted models (a model that replays the reference SQL scores 12/12; wrong or non-querying models score 0). Real accuracy is unknown.
- Only OpenAI-compatible chat-completions endpoints are supported.
- The evaluation set is 12 questions on one small hand-written dataset; it is a smoke test, not a benchmark.
- Maven defaults to JDK 26 on this machine, so builds must set `JAVA_HOME` to JDK 21 (the enforcer fails fast otherwise).

## Next milestones

1. Run `--eval` against a real model, record the result with model name and date, and fix what it shows.
2. Long-form docs (`docs/01…`) teaching how each layer is built.
3. B+ tree index and index-aware planning.
4. Transactions and a write-ahead log.
5. Optional REST API and structured logging.

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd frierendb && mvn clean test
```
