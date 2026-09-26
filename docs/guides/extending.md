# Extending FrierenDB: Recipes

Each recipe lists the files to touch, in order, and the tests to add. The sealed types help: adding a case to a sealed interface makes every incomplete `switch` fail to compile, which is your to-do list.

## Add a scalar function (e.g. `TRIM`)

1. `sql/Expr.java`: add `TRIM(1, 1)` to `ScalarFunction` (name, min args, max args). The parser finds functions by enum name, so no parser change is needed.
2. `exec/ExpressionCompiler.java`: add a `case TRIM -> ...` in `call(...)` (the `switch` is exhaustive, so the compiler tells you).
3. Handle NULL (return NULL) and type errors (`SqlException`).
4. Tests: `SqlQueryTest` (arithmeticAndFunctions style), NULL propagation, wrong type, wrong arity (`ParserTest`).
5. Docs: `lld/04-sql-grammar.md` function list; `agent/tools/DatabaseTools.RunQuery` description string so the model knows it exists.

## Add an aggregate (e.g. `STDDEV`)

1. `Expr.AggregateFunction`: add the constant.
2. `exec/Accumulator.create`: add the case and write an accumulator (ignore NULLs; define the empty result).
3. `Planner`/`GroupScope` need no change (they treat aggregates generically).
4. Tests: grouped and global, with NULLs, with `DISTINCT`, over no rows.

## Add a SQL statement (e.g. `TRUNCATE TABLE`)

1. `Statement`: add a record. Decide `isReadOnly()`; **leave the default `false`** for anything that changes data or schema (this protects the agent).
2. `Parser.statement()`: dispatch on the keyword; add the keyword to `RESERVED` if appropriate.
3. `Executor.execute`: add a `case` (exhaustive switch), and a matching case in `Database.run`: a statement that writes pages belongs with `Insert`/`Update`/`Delete` (automatic transaction, rolled back on failure); one that creates or deletes files belongs with the DDL cases (checkpoint first, refused inside a transaction). There is no per-statement flush any more; the log provides durability.
4. Tests: parse, execute, persistence across reopen, failure leaves no trace (`SqlTransactionTest` style), a crash copy (`CrashRecoveryTest` style), and **`QuerySafetyTest.readOnlyModeRejectsEveryModifyingStatement`** extended with the new statement.

## Add a column type (e.g. `DATE`)

This touches many files; do it in this order and let the compiler guide you:

1. `types/DataType`: constant and SQL name mapping.
2. `types/Value`: a record, `isNull`, `type`, `toString`, and a case in `compareTo`.
3. `schema/RowCodec`: encode and decode cases.
4. `Catalog`: type names are stored by enum name, so persistence needs no change, but add a reopen test.
5. `Parser`: literal syntax (for example `DATE '2024-01-31'`) and column type names.
6. `exec/SqlValues`, `ExpressionCompiler`: comparison, arithmetic rules (what does `date + 1` mean? probably an error).
7. Tests at every layer, including `RowCodecTest` round trips and cross-type comparison errors.

## Add a tool for the agent

Ask first: *does this widen what a compromised model can do?* If it can write, call the network, or read files, it needs a new safety review (`hld/05-safety-model.md`).

1. Implement `Tool` (spec + `execute`) in `agent/tools`. Validate every argument with `ToolArguments`.
2. Register it in `DatabaseTools.create` (or a new factory). Reach the database only through `Database.query`.
3. Prefix data-bearing output with `DATA_HEADER`; cap sizes.
4. Update `DatabaseToolsTest.exposesExactlyTheFourReadOnlyTools` (it fails on purpose when the tool set changes).
5. Mention it in `SystemPrompt` rules only if needed; keep the description in the tool spec accurate.
6. Update `hld/05-safety-model.md` and `lld/02-agent-class-reference.md`.

## Add an LLM provider (e.g. Anthropic)

1. New class in `agent/model` implementing `ChatModel`.
2. Map `Message` → provider format and provider response → `Answer` / `ToolRequest`. Keep tool call ids.
3. Classify errors: authentication = not retryable; rate limit/5xx/network = retryable; malformed tool arguments = retryable.
4. **Never** include the key in messages, `toString` or logs.
5. Test with a local `HttpServer` like `OpenAiCompatibleModelTest`.
6. Wire it in `Main.modelFromEnv` (for example a `FRIEREN_LLM_PROVIDER` variable).

## Add an evaluation question

1. Append `{question, goldSql}` to `eval/university-questions.json`.
2. Make the question unambiguous: avoid ties in "top N", state how to treat NULLs.
3. `EvalRunnerTest.everyReferenceQueryRunsAndReturnsRows` verifies the gold SQL runs; add a "known facts" assertion for new data.

## Add or change demo data

Edit `agent/src/main/resources/demo/university.sql`. Re-check every evaluation question's gold answer and the counts quoted in docs (`build-guide/19-cli-demo-and-eval.md`).

## Extend indexes or transactions

The B+ tree, index planning, log and recovery exist. Read `hld/07`, `hld/08`, `lld/08` and `lld/09` first. Rules that keep them correct:

- **Never remove the `Filter` above an `IndexScan`.** Any new way of choosing an index (`IN`, `OR`, prefixes) must still leave the full `WHERE` in place, and must have an oracle test that compares against the same query with the index defeated.
- **A new page-writing structure** (another index type, an overflow page file) must go through `BufferPool.fetch/allocate/markDirty` only. Anything that writes a `PageFile` directly bypasses the log and cannot be rolled back or recovered.
- **A new in-memory cache over pages** (like `HeapFile`'s free-space list) needs a `reload()` called from `Catalog.reload()`, or a rollback will leave it pointing at pages that no longer exist. Add a regression test that starts from an *empty* structure.
- **Anything that creates or deletes files** is not covered by the log: keep it out of transactions, as DDL does.
- **Concurrency** (more than one open transaction) invalidates the recovery argument in `lld/09`; read `hld/06` (R3 follow-ups) before starting.

## Definition of done for any change

- New behaviour has tests; `mvn clean test` is green on JDK 21.
- No warnings (the build uses `-Xlint:all -Werror`).
- Docs updated in the same commit.
- `docs/implementation-progress.md` reflects the change and any new limitation.
