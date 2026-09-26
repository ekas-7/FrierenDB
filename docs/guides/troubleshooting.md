# Troubleshooting

## Build

| Symptom | Cause | Fix |
|---|---|---|
| `Rule 0: ... RequireJavaVersion failed ... Build with JDK 21` | Maven is running on another JDK (a newer default is common) | `export JAVA_HOME=<jdk-21 home>` in the same shell; check `mvn -version` |
| `release version 21 not supported` | JDK older than 21 | Install JDK 21 |
| `[WARNING]... serializable class has no definition of serialVersionUID` then failure | `-Xlint:all -Werror` | Add `private static final long serialVersionUID = 1L;` to the exception class |
| `Could not resolve dependencies ... frierendb-engine` when building only `agent` | Reactor not used | Run from `frierendb/` with `-am`: `mvn -pl agent -am test` |
| `Cannot access ... surefire provider` with `mvn -o` | Offline and the JUnit provider was never downloaded | Run once online |
| Tests pass in IDE but not in Maven (or vice versa) | Stale class files or different JDK | `mvn clean test`; make the IDE use JDK 21 |
| `mvn exec:java` fails with `An exception occurred while executing the Java class` | `-am` makes Maven run the exec plugin on the parent POM too | Run the CLI from class directories as shown in the root README |

## Running the CLI

| Symptom | Cause | Fix |
|---|---|---|
| `NoClassDefFoundError: com/fasterxml/jackson/...` | Jackson jars missing from `-cp` | Include `jackson-core`, `jackson-databind`, `jackson-annotations` 2.18.2 from `~/.m2` |
| `The AI analyst is not configured` | `FRIEREN_LLM_MODEL` not set | Export it (and the key or base URL) |
| `ask` returns `The model server rejected the credentials (HTTP 401)` | Wrong or missing key | Check the variable; the key is never echoed back |
| `Could not reach the model server` | Wrong base URL, server down | For Ollama use `http://localhost:11434/v1` |
| Model replies with text but never queries | The model does not support native tool calling | Use a tool-capable model |
| `Run stopped: STEP_LIMIT` | The model kept calling tools | Look at the trace; improve the question or raise `maxSteps` in code |
| `Error: Table name 'x' is used more than once` | Self-join without distinct aliases | Alias each use |
| `Error: Column x must appear in the GROUP BY clause...` | Selecting a non-grouped column | Add it to `GROUP BY` or aggregate it |
| `Error: 'x' is a reserved word` | Reserved word used as a name | Quote it: `"x"` |

## Storage and data

| Symptom | Cause | Fix |
|---|---|---|
| `Buffer pool exhausted: all N pages are pinned` | A frame was not closed (pin leak) or the pool is tiny for a nested operation | Use try-with-resources around every `fetch`; use a larger pool |
| `File ... is not a whole number of pages` | Truncated or foreign file in the database directory | Restore from backup; do not hand-edit `.tbl` files |
| `Data file missing for table x` | `<table>.tbl` deleted but `catalog.bin` still lists it | Restore the file or remove the table definition by recreating the database |
| `... is not a FrierenDB catalog` / `Cannot read catalog` | Corrupt or foreign `catalog.bin` | Restore from backup |
| Committed data missing after a crash | Should not happen: report it with the contents of `wal.log` and the data directory | Keep a copy of the directory *before* reopening; recovery rewrites files and empties the log |
| `... is not a FrierenDB write-ahead log` or `Unsupported write-ahead log version` | `wal.log` is foreign or corrupt (a *torn tail* is trimmed silently; a bad header is refused) | Restore from backup; do not delete the log of a crashed database (it holds committed work) unless you accept losing it |
| `Write-ahead log names a file outside the database directory` | Corrupt log | Restore from backup |
| `Cannot undo a change to x: the file is not open` | A table was dropped while its transaction was open (this should be impossible through `Database`) | Use `Database` rather than driving `BufferPool` directly |
| `Index file missing for index x` at open | An `.idx` file was deleted | Restore it, or recreate the database; there is no `REINDEX` |
| `Index key is N bytes; TEXT index keys are limited to 255 bytes` | A `TEXT` value over 255 bytes in an indexed column | Shorten the value, or index another column |
| `Record of N bytes exceeds the page limit of 4088` | A row is too large (long `TEXT`) | Store less text; overflow pages are not implemented. Inside a transaction this surfaces as `TransactionException` and the whole transaction was rolled back |
| `A transaction is already in progress` / `COMMIT without BEGIN` | Transaction misuse | Check `inTransaction()`; there is no nesting |
| `CREATE and DROP cannot run inside a transaction` | DDL while a transaction is open | `COMMIT` or `ROLLBACK` first |
| `The statement failed after changing data, so the whole transaction was rolled back` | A write statement failed part-way inside a transaction | Nothing from the transaction remains; start again with `BEGIN` |
| Query is slow on a big table although an index exists | The `WHERE` has no usable comparison on that table's column (`OR`, `IN`, `LIKE`, a function of the column, a joined table's column) | Run `EXPLAIN`; look for `IndexScan`; rewrite the condition as `column op constant` |

## Agent behaviour

| Symptom | Likely cause | What to do |
|---|---|---|
| Wrong numbers | The model wrote semantically wrong SQL | Inspect the trace (SQL is shown); add a question to the eval set |
| "I cannot answer" | Question refers to data that is not in the tables | Check `\tables` and `describe_table` |
| Many failed queries | Model uses unsupported SQL (subquery, CASE) | The error text tells it what is wrong; the `run_query` description lists supported SQL |
| Slow runs | Large tables with joins | Add `LIMIT`/filters; a table is scanned in full unless an index fits the `WHERE` (check `EXPLAIN` for `IndexScan`) |
| Output cut off | `maxToolOutputChars` or row cap hit | Ask for aggregates; check `truncated` notices in the trace |

## Debugging tips

- `EXPLAIN <select>` shows the join algorithm and where filters apply.
- To see exactly what the model saw, assert on `ScriptedChatModel.requests` in a test.
- To reproduce a model run without a model, copy the SQL from the trace into the REPL.
- Run a single test class: `mvn -pl engine -Dtest=SqlQueryTest test`.
