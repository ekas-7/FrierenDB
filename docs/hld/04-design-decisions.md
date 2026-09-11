# 04 - Design Decisions

Each entry: the decision, the alternatives, why, and what it costs. These are the choices a reviewer is most likely to ask about.

## D1. Write the engine from scratch instead of using H2/SQLite

- **Alternatives**: embed H2 or SQLite and build only the agent.
- **Why**: the project's safety claim ("the model can only read, and cannot exhaust the machine") is strongest when read-only checking, row caps, deadlines and memory caps live inside the executor. Owning the engine also makes the build guide possible.
- **Cost**: far less SQL, no optimizer, no indexes, no crash safety. Do not use it for real data.

## D2. Maven multi-module: `engine` and `agent`

- **Why**: the dependency direction (agent → engine) is enforced by the build, not by discipline. The engine stays free of Jackson and HTTP.
- **Cost**: a test-jar is needed to share the `TestDatabases` fixture; reactor builds must run from `frierendb/`.

## D3. Java 21 features used deliberately

| Feature | Where | Benefit |
|---|---|---|
| Records | `Row`, `Column`, `QueryResult`, `AgentConfig`, AST nodes | Immutable data with value equality (AST equality is used by the planner to match `GROUP BY` expressions) |
| Sealed interfaces | `Value`, `Expr`, `Statement`, `Message`, `ModelResponse` | The compiler proves every `switch` handles every case |
| Pattern-matching `switch` | `RowCodec`, `ExpressionCompiler`, `Executor` | No visitor boilerplate, no default branch that hides a new case |
| Text blocks | `SystemPrompt`, tests | Readable multi-line text |

## D4. Strict compilation: `-Xlint:all -Werror` and a JDK enforcer

- **Why**: warnings are future bugs; `requireJavaVersion [21,22)` fails fast with a clear message rather than a confusing compile error on a newer JDK.
- **Cost**: small ceremony, e.g. every exception class needs a `serialVersionUID`.

## D5. Storage: slotted pages + heap file + LRU buffer pool

- **Why**: this is the textbook design; it supports variable-length records, deletion with space reuse, and datasets bigger than memory (a test runs a table far larger than the pool).
- **Alternatives**: one file per table appended with length-prefixed rows (simpler, but no in-place update or space reuse).
- **Cost**: a record may not exceed 4088 bytes (no overflow pages). No write-ahead log, so a crash mid-write can leave a table inconsistent.

## D6. Durability by flush-after-write, not by logging

- **Decision**: after every modifying statement, flush dirty pages and `force` the files. The catalog is replaced atomically (temp file + atomic move).
- **Why**: honest and simple; shows where a WAL would go.
- **Cost**: a crash *during* a statement can corrupt the table; a test (`unflushedDataIsLostOnReopenBecauseThereIsNoWriteAheadLogYet`) documents that unflushed data is lost.

## D7. Pull-based iterator operators

- **Decision**: each plan node returns `Iterator<Row>` and pulls from its child.
- **Why**: `LIMIT` stops work early; memory stays proportional to the blocking operators only; nodes are tiny classes.
- **Cost**: iterators are single-use; blocking operators (`Sort`, `Aggregate`, the build side of a join) materialize rows and are guarded by the memory cap.

## D8. Resolve names at plan time, evaluate through compiled lambdas

- **Decision**: `ExpressionCompiler` turns an `Expr` into a `RowFunction` after resolving each column to an index once.
- **Why**: name lookup does not repeat per row; errors like "Unknown column" appear before any row is read.
- **Cost**: the planner is more involved than a direct tree-walking interpreter.

## D9. SQL `NULL` is a first-class value; three-valued logic everywhere

- **Decision**: `Value.NULL` exists; `AND`/`OR`/`NOT`/`IN`/comparisons follow SQL; `WHERE` keeps a row only if the predicate is exactly `TRUE`.
- **Why**: analytics questions depend on NULL semantics ("students without a GPA"). Getting it wrong silently gives wrong answers.
- **Related**: `NULL` sorts first ascending and last descending; `COUNT(col)` ignores NULLs; `AVG`/`SUM` over no non-NULL values is `NULL`; global `COUNT(*)` over no rows is `0`.

## D10. Arithmetic errors are errors

- **Decision**: integer overflow, division by zero, and `Long.MIN_VALUE` negation throw `SqlException`.
- **Why**: silently wrapping or returning infinity would give wrong analytics.
- **Note**: floating-point `+ - *` follow IEEE; division by 0.0 is still an error.

## D11. Hash join only for equi-joins; everything else is nested loop

- **Decision**: the planner extracts `column = column` conjuncts from `ON`; if any pair spans both sides, it builds a hash table on the right input. The full `ON` is still evaluated on each candidate.
- **Why**: covers the common case with little code; correctness does not depend on the extraction (the condition is always rechecked).
- **Cost**: no join reordering, no index joins, the right side is always the build side.

## D12. The agent talks to a `ChatModel` interface

- **Why**: tests inject a scripted model, so agent behaviour (retries, step limit, safety) is deterministic and needs no network. A local `HttpServer` tests the real HTTP client.
- **Cost**: only the OpenAI-style protocol is implemented; Anthropic or Gemini need a new adapter (see [`../guides/extending.md`](../guides/extending.md)).

## D13. Safety lives in the tool layer, not in the prompt

- **Decision**: the prompt asks for good behaviour, but nothing depends on it. Read-only is a parser-level check; caps are enforced by the engine; the loop is bounded.
- **Why**: prompts can be bypassed by injection. Tests simulate a model that *tries* to write and show the data survives.

## D14. One `run_query` tool taking SQL, not a bag of "business" tools

- **Why**: lets the model compose analytics freely while the engine bounds what any composition can do.
- **Cost**: quality depends on the model writing correct SQL; mitigations are schema in the prompt, `describe_table` samples, and errors fed back for self-correction.

## D15. Evaluation by execution accuracy

- **Decision**: compare the *values* returned by the agent's last successful query with those of a reference query, not the SQL text.
- **Why**: many different SQL strings are correct. Text comparison would punish valid answers.
- **Cost**: it checks the query, not the prose of the final answer; the 12-question set is a smoke test, not a benchmark.

## D16. Documentation instead of code comments

- Source files hold almost no comments; the reasoning is in this folder. When behaviour changes, the doc changes in the same commit.
