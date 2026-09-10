# FrierenDB

![Frieren watching the sunset](docs/assets/frieren.jpg)

An embedded relational database engine written from scratch in Java 21, with a natural-language analyst on top: ask a question in English, and an agent inspects the schema, writes read-only SQL, runs it through bounded tools, and explains the result.

> **Status: working prototype.** Storage, catalog, SQL engine and agent are implemented and tested (217 tests). The agent has been tested against scripted models and a local HTTP server only; it has not been run against a live LLM, so no accuracy figure is claimed. Indexes, transactions and a write-ahead log are not built. See [Status](#status).

## Status

| Component | State |
|---|---|
| Java 21 Maven build with JDK enforcement and `-Xlint:all -Werror` | Done |
| Value types (`INT`, `DOUBLE`, `TEXT`, `BOOLEAN`, SQL `NULL`), schema, binary row codec | Done, tested |
| Page file, LRU buffer pool, slotted pages, heap file | Done, tested |
| Persistent catalog | Done, tested |
| SQL lexer, parser, planner, executor (joins, grouping, aggregates, ordering, writes, `EXPLAIN`) | Done, tested |
| Read-only query mode with row, time and memory limits | Done, tested |
| Analyst agent: schema/query/explain tools, bounded loop, OpenAI-compatible provider | Done, tested with scripted models and a local HTTP server |
| Interactive CLI, demo dataset, execution-accuracy evaluation harness | Done, tested |
| Accuracy of a real LLM on the evaluation set | Not measured |
| B+ tree index and index-aware planning | Planned |
| Transactions and write-ahead log | Planned |

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
      types/ schema/ storage/ catalog/ sql/ exec/   Database.java
  agent/              Analyst agent, tools, model client, CLI, evaluation
    src/main/java/dev/frierendb/agent/
      model/ tools/ core/ cli/ demo/ eval/
docs/                 Design notes, progress log and README assets
```

## SQL supported

`SELECT` (`DISTINCT`, `WHERE`, `INNER`/`LEFT JOIN`, `GROUP BY`, `HAVING`, `ORDER BY`, `LIMIT`), `COUNT/SUM/AVG/MIN/MAX`, `LOWER/UPPER/LENGTH/ABS/ROUND/COALESCE`, `INSERT`, `UPDATE`, `DELETE`, `CREATE/DROP TABLE`, `EXPLAIN`. Not supported: subqueries, CTEs, `UNION`, `CASE`, `OFFSET`, window functions, comma joins, indexes, constraints beyond `NOT NULL`.

## Documentation

- [`docs/implementation-progress.md`](docs/implementation-progress.md): milestones, validation, known issues, next steps
