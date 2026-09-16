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
| Data lost after a crash | No write-ahead log | Known limitation; keep backups; see roadmap R3 |
| `Record of N bytes exceeds the page limit of 4088` | A row is too large (long `TEXT`) | Store less text; overflow pages are not implemented |

## Agent behaviour

| Symptom | Likely cause | What to do |
|---|---|---|
| Wrong numbers | The model wrote semantically wrong SQL | Inspect the trace (SQL is shown); add a question to the eval set |
| "I cannot answer" | Question refers to data that is not in the tables | Check `\tables` and `describe_table` |
| Many failed queries | Model uses unsupported SQL (subquery, CASE) | The error text tells it what is wrong; the `run_query` description lists supported SQL |
| Slow runs | Large tables with joins | Add `LIMIT`/filters; remember every scan is a full scan |
| Output cut off | `maxToolOutputChars` or row cap hit | Ask for aggregates; check `truncated` notices in the trace |

## Debugging tips

- `EXPLAIN <select>` shows the join algorithm and where filters apply.
- To see exactly what the model saw, assert on `ScriptedChatModel.requests` in a test.
- To reproduce a model run without a model, copy the SQL from the trace into the REPL.
- Run a single test class: `mvn -pl engine -Dtest=SqlQueryTest test`.
