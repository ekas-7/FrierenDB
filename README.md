# FrierenDB

An embedded relational database engine written from scratch in Java 21, being built toward a natural-language query agent: ask a question in English, and an agent inspects the schema, writes read-only SQL, runs it through controlled tools, and explains the result.

> **Status: early development.** The storage foundation is done. There is no SQL parser, query executor, index, transaction support, or natural-language agent yet, so the project cannot run queries today. See [Status](#status).

## Status

| Component | State |
|---|---|
| Java 21 Maven build with JDK enforcement and `-Xlint:all -Werror` | Done |
| Value types (`INT`, `DOUBLE`, `TEXT`, `BOOLEAN`, SQL `NULL`), schema, binary row codec | Done, tested |
| Page file, LRU buffer pool, slotted pages, heap file | Done, tested |
| Catalog and table abstraction | Planned |
| SQL lexer, parser, expression evaluation, executor | Planned |
| B+ tree index and index-aware planning | Planned |
| Transactions and write-ahead log | Planned |
| Natural-language agent (schema, read-only SQL and `EXPLAIN` tools, bounded loop, evaluation set) | Planned |

Known limitations of what exists: no write-ahead log (data is durable only after an explicit flush), records are capped at 4088 bytes, and the buffer pool is only thread-safe per method call. Details are in [`docs/implementation-progress.md`](docs/implementation-progress.md).

## Requirements

- JDK 21 (the build fails fast on any other major version)
- Maven 3.9+

## Build and test

```bash
export JAVA_HOME=/path/to/jdk-21
cd frierendb
mvn test
```

On macOS with Homebrew, for example: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.

## Repository layout

```
frierendb/            Maven multi-module build
  engine/             Storage, schema and (soon) SQL execution
    src/main/java/dev/frierendb/engine/
      types/          DataType, Value (sealed, NULL-aware ordering)
      schema/         Column, Schema, Row, RowCodec
      storage/        PageFile, BufferPool, SlottedPage, HeapFile, RecordId
docs/                 Design notes and progress log
```

## Documentation

- [`docs/implementation-progress.md`](docs/implementation-progress.md): milestones, validation, known issues, next steps
