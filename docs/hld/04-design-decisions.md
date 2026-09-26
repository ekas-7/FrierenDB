# 04 - Design Decisions

Each entry: the decision, the alternatives, why, and what it costs. These are the choices a reviewer is most likely to ask about.

## D1. Write the engine from scratch instead of using H2/SQLite

- **Alternatives**: embed H2 or SQLite and build only the agent.
- **Why**: the project's safety claim ("the model can only read, and cannot exhaust the machine") is strongest when read-only checking, row caps, deadlines and memory caps live inside the executor. Owning the engine also makes the build guide possible.
- **Cost**: far less SQL and no cost-based optimizer. It now has indexes and a write-ahead log, but one session, no locking and no benchmarks mean it still should not hold data you cannot afford to lose.

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
- **Cost**: a record may not exceed 4088 bytes (no overflow pages). Crash safety is added on top by the write-ahead log (D6).

## D6. Durability by a write-ahead log (physical, whole-page, steal / no-force)

- **History**: the first version flushed and synced every file after each statement and had no log, so a crash *during* a statement could corrupt a table. That was replaced.
- **Decision**: log before and after images of whole pages. Steal (uncommitted pages may be written) and no-force (commit writes only the log). One transaction at a time. Recovery redoes committed work and undoes unfinished work before any file is opened. Details: [`08-transactions-and-recovery.md`](08-transactions-and-recovery.md).
- **Alternatives**: logical logging of rows (small log, but needs undo logic per operation and per index); shadow paging (no log, but copy-on-write pages and a page table); keeping flush-after-write (simple, not crash safe).
- **Why**: page images make undo and redo mechanical, and they cover heap pages and B+ tree pages with the same code. A buffer pool hook is the only place that has to know about the log.
- **Cost**: a large log (up to two page images per changed page), a log force per commit, no concurrency. The single-transaction rule is what makes the simple recovery argument valid: an unfinished transaction is always the last one in the log.
- **What would change it**: record-level logging with LSNs on pages, and locking, for concurrent transactions.

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

## D17. Secondary indexes as persistent B+ trees on one column, chosen by rule

- **Decision**: `CREATE INDEX name ON table (column)` builds a non-unique B+ tree file keyed by `(value, record id)`. The planner uses an index only for comparisons of an indexed column with a constant on the `FROM` table, and always keeps the full `WHERE` as a filter above the scan. Details: [`07-indexes-and-index-aware-planning.md`](07-indexes-and-index-aware-planning.md).
- **Alternatives**: unique-only indexes (simpler tree, less useful); a cost model with statistics (better choices, much more code and no data to calibrate on); hash indexes (no ranges).
- **Why**: putting the record id in the sort order removes every special case for duplicates; the re-applied filter means a planner mistake can slow a query down but not change its answer; the rule-based choice needs no statistics.
- **Cost**: no merge on delete (sparse trees), a 255-byte TEXT key limit, no index-ordered `ORDER BY`, no index on joined tables, and a rule that always picks an available index even when a scan would be cheaper (for example a low-selectivity predicate).

## D18. Schema changes are not transactional

- **Decision**: `CREATE`/`DROP` of tables and indexes are refused inside a transaction and run alone after a checkpoint.
- **Why**: they create and delete files, which the page log cannot undo; making them transactional would need file-level logging.
- **Cost**: a script that mixes DDL and a transaction must commit before each DDL statement.
