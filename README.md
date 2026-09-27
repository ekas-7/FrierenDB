# FrierenDB

![Frieren watching the sunset](docs/assets/frieren.jpg)

An embedded relational database engine written from scratch in Java 21, with a natural-language analyst on top: ask a question in English, and an agent inspects the schema, writes read-only SQL, runs it through bounded tools, and explains the result.

> **Status: working prototype.** Storage, catalog, B+ tree indexes, a write-ahead log with transactions and crash recovery, the SQL engine and the agent are implemented and tested (321 tests). The agent has been tested against scripted models and a local HTTP server only; it has not been run against a live LLM, so no accuracy figure is claimed. No performance benchmarks have been run, so none are claimed. See [Status](#status) and the limits listed there.

## Status

| Component | State |
|---|---|
| Java 21 Maven build with JDK enforcement and `-Xlint:all -Werror` | Done |
| Value types (`INT`, `DOUBLE`, `TEXT`, `BOOLEAN`, SQL `NULL`), schema, binary row codec | Done, tested |
| Page file, LRU buffer pool, slotted pages, heap file | Done, tested |
| Persistent catalog (format v2, loads v1) | Done, tested |
| SQL lexer, parser, planner, executor (joins, grouping, aggregates, ordering, writes, `EXPLAIN`) | Done, tested |
| Read-only query mode with row, time and memory limits | Done, tested |
| Analyst agent: schema/query/explain tools, bounded loop, OpenAI-compatible provider | Done, tested with scripted models and a local HTTP server |
| Interactive CLI, demo dataset, execution-accuracy evaluation harness | Done, tested |
| Accuracy of a real LLM on the evaluation set | Not measured |
| B+ tree secondary indexes (`CREATE INDEX`), automatic maintenance, rule-based index-aware planning (`IndexScan` in `EXPLAIN`) | Done, tested against oracles |
| Write-ahead log, atomic statements, `BEGIN`/`COMMIT`/`ROLLBACK`, crash recovery on open | Done, tested with simulated crashes |
| Performance numbers for indexes or the log | Not measured |

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

## Try it

The CLI works without any model: you can type SQL yourself.

```bash
cd frierendb
mvn -q package -DskipTests
CP=engine/target/classes:agent/target/classes:$(find ~/.m2 -name 'jackson-*-2.18.2.jar' | tr '\n' ':')
java -cp "$CP" dev.frierendb.agent.cli.Main --db /tmp/frieren --demo
```

```
frieren> SELECT major, COUNT(*) AS n FROM students GROUP BY major ORDER BY n DESC
frieren> \tables
frieren> ask Which course has the most enrollments?
```

To enable `ask`, point the CLI at any OpenAI-compatible chat-completions endpoint:

```bash
export FRIEREN_LLM_MODEL=<model name>
export FRIEREN_LLM_API_KEY=<key>                    # omit for local servers
export FRIEREN_LLM_BASE_URL=http://localhost:11434/v1   # default: https://api.openai.com/v1
```

`--eval` runs the 12 bundled questions against the configured model and reports execution accuracy (the agent's final query must return the same values as a reference query).

## How the agent is kept safe

The model is never trusted. Its only capabilities are four tools (`list_tables`, `describe_table`, `run_query`, `explain_query`), and every query goes through `Database.query`, which rejects anything but `SELECT`/`EXPLAIN` by inspecting the parsed statement, accepts one statement only, and enforces row, time and memory caps. Tool output is labelled as untrusted data, loop length and run time are bounded, and API keys are masked in logs and errors. Tests simulate a compromised model attempting writes and prompt injection through stored data. Prompt wording is not part of the security boundary.

## Repository layout

```
frierendb/            Maven multi-module build
  engine/             Storage, catalog, SQL parsing/planning/execution
    src/main/java/dev/frierendb/engine/
      types/ schema/ storage/ index/ catalog/ sql/ exec/   Database.java
  agent/              Analyst agent, tools, model client, CLI, evaluation
    src/main/java/dev/frierendb/agent/
      model/ tools/ core/ cli/ demo/ eval/
docs/                 Design notes, progress log and README assets
```

## SQL supported

`SELECT` (`DISTINCT`, `WHERE`, `INNER`/`LEFT JOIN`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`), `COUNT/SUM/AVG/MIN/MAX`, `LOWER/UPPER/LENGTH/ABS/ROUND/COALESCE`, `INSERT`, `UPDATE`, `DELETE`, `CREATE/DROP TABLE`, `CREATE/DROP INDEX`, `BEGIN`/`COMMIT`/`ROLLBACK`, `EXPLAIN`. Not supported: subqueries, CTEs, `UNION`, `CASE`, `OFFSET`, window functions, comma joins, constraints beyond `NOT NULL`.

## Indexes and transactions

```sql
CREATE INDEX idx_major ON students (major);
EXPLAIN SELECT name FROM students WHERE major = 'CS';   -- IndexScan students using idx_major (major = 'CS')

BEGIN;
UPDATE accounts SET balance = balance - 50 WHERE id = 1;
UPDATE accounts SET balance = balance + 50 WHERE id = 2;
COMMIT;                                                 -- or ROLLBACK
```

- Every `INSERT`/`UPDATE`/`DELETE` is atomic; an open database is recovered from `wal.log` after a crash, which returns committed work and undoes unfinished transactions.
- The planner uses an index for `=`, `<`, `<=`, `>`, `>=` (and `BETWEEN`) against a constant on the `FROM` table, and always re-applies the whole `WHERE`, so an index cannot change a result.
- **Limits, stated plainly:** indexes are single-column and non-unique; `NULL` and `TEXT` keys over 255 bytes are not indexable; deletes never rebalance the tree; no statistics or cost model; indexes do not provide `ORDER BY`. One session and one transaction at a time, no locking or isolation (a read-only query can see an open transaction's uncommitted changes); `CREATE`/`DROP` are not transactional and are refused inside a transaction; the log stores whole pages, so it is large; crash tests copy a live directory and do not model a disk that reorders or loses forced writes. The agent cannot run `BEGIN`/`COMMIT`/`ROLLBACK` or any write: `Database.query` accepts `SELECT`/`EXPLAIN` only.

## Documentation

Start at [`docs/README.md`](docs/README.md). The docs are written so the whole system can be rebuilt by hand:

- [`docs/hld/`](docs/hld/): problem, architecture, runtime flows, design decisions, safety model, roadmap, indexes, transactions and recovery
- [`docs/build-guide/`](docs/build-guide/): 24 step-by-step files, from the Maven skeleton to the CLI and evaluation harness, then the B+ tree, index planning, write-ahead log and transactions
- [`docs/lld/`](docs/lld/): class reference, on-disk formats, SQL grammar, error model, `EXPLAIN` examples, agent protocol, index and WAL internals
- [`docs/guides/`](docs/guides/): testing strategy, extension recipes, troubleshooting, glossary
- [`docs/implementation-progress.md`](docs/implementation-progress.md): milestones, validation, known issues, next steps
