# Implementation Progress

## Direction (decided)

Rewrite the database core as an independent Java 21 engine in `frierendb/`, then build a natural-language-to-SQL agent on top of it.

Assumptions made without asking, so they can be challenged:

- Maven multi-module build, `release 21`, JUnit 5, `-Xlint:all -Werror`, no runtime dependencies in the engine.
- Hand-written recursive-descent SQL parser (no JavaCC step).
- LLM access through a provider interface, a deterministic fake for tests, then a real HTTP provider. Provider and budget are still undecided.
- Spring Boot is deferred until there is an API worth serving.

## Current stage

Stage 2/3: engine foundation. Storage layer finished; SQL front end is next.

## Completed milestones

| # | Milestone | Commit | Validation |
|---|---|---|---|
| 1 | Java 21 Maven skeleton with JDK enforcement | `5e03dae` | `mvn test` passes on JDK 21; the enforcer rejects JDK 26 |
| 2 | Value types, schema, binary row codec | `5b75ad8` | 25 tests |
| 3 | Storage layer: page file, buffer pool, slotted pages, heap file | see `git log` | 59 tests total, all passing |

## Known issues and limitations

- No write-ahead log yet. Data is durable only after an explicit flush, and a test documents this.
- Records are limited to 4088 bytes (no overflow pages).
- `HeapFile.scan` visits only pages that existed when the scan started.
- The buffer pool is thread-safe at the method level only. Transaction-level isolation is not built.
- No model provider or API key has been chosen.
- Maven defaults to JDK 26 on this machine, so builds must set `JAVA_HOME` to JDK 21 (the enforcer fails fast otherwise).

## Next milestones

1. Catalog (persistent table metadata) and a `Table` abstraction over heap files.
2. SQL lexer and parser with a sealed AST.
3. Expression evaluation with SQL NULL semantics, and the executor operators.
4. B+ tree index and index-aware planning.
5. Transactions and a write-ahead log.
6. The natural-language agent: schema tool, read-only query tool, EXPLAIN tool, bounded loop, fake and real model providers, evaluation set.

## Build and test

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd frierendb && mvn test
```
