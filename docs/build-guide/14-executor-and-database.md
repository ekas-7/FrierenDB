# Step 14 - Executor and the `Database` Facade

Classes: `dev.frierendb.engine.exec.Executor`, `QueryResult`, `QueryOptions` (see step 15), and `dev.frierendb.engine.Database`.

## Goal

Run every statement type against the catalog and expose one safe, thread-safe entry point.

## Concepts

- **Snapshot-then-modify**: collect the rows an `UPDATE`/`DELETE` will touch *before* changing anything, so the statement cannot see (or re-process) its own writes (the "Halloween problem").
- **Validate everything, then write**: a multi-row `INSERT` is atomic with respect to validation errors. (Since step 23 it is atomic with respect to *any* failure, because every write statement runs in a transaction.)
- **Facade**: hide parser, planner and catalog behind a few methods.

## Design

### `QueryResult`

```java
public record QueryResult(List<String> columns, List<Row> rows, long affectedRows, boolean truncated)
```

`QueryResult.rows(columns, rows, truncated)` for queries; `QueryResult.affected(n)` for writes and DDL (no columns).

### `Executor.execute(Statement, QueryOptions)`

A `switch` over the sealed `Statement`:

| Statement | Behaviour |
|---|---|
| `Select` | Plan → pull rows until `maxRows` → `truncated = iterator.hasNext()` |
| `Explain` | Plan, return `explain()` lines as rows of a one-column `plan` result; **does not execute** the plan |
| `Insert` | Resolve target columns (all, or the listed ones; duplicates rejected). For each row: evaluate value expressions with an empty row (constants only), fill unlisted columns with `NULL`, `schema.conform`. Insert only after **all** rows are valid |
| `Update` | Compile assignments against the table schema (duplicate targets rejected); collect matches; compute every replacement from the **original** row and `conform` them; then apply with `table.update` |
| `Delete` | Collect matches; delete each by `RecordId` |
| `CreateTable` | Build a `Schema` from `ColumnDef`s; `IF NOT EXISTS` returns 0 when it exists |
| `DropTable` | `IF EXISTS` returns 0 when absent |

In the first version, after any **non-read-only** statement the executor called `catalog.flush()` (write dirty pages and fsync); that was the durability point. **Step 23 replaces this**: the executor no longer flushes, and `Database` makes each write statement a transaction whose commit forces the log. If you build this step on its own, keep the flush and remove it in step 23.

### `Database`

```java
public static Database open(Path dir)                       // 256-page buffer pool
public static Database open(Path dir, int bufferPages)
public synchronized QueryResult execute(String sql)          // any statement, unlimited options
public synchronized QueryResult query(String sql, QueryOptions options)   // SELECT/EXPLAIN only
public synchronized int executeScript(String script)        // statements separated by ';'
public synchronized List<String> tableNames()
public synchronized Schema tableSchema(String table)
public synchronized void close()                             // flush + close tables
```

Step 23 adds `open(dir, bufferPages, checkpointBytes)`, `inTransaction()`, `recoveryReport()`, `logSize()` and `tableIndexes(table)`, and changes `close()` to roll back an open transaction, checkpoint, and close the log.

`query` parses, checks `statement.isReadOnly()`, throws `ReadOnlyViolationException` otherwise, then executes with the caller's limits.

`executeScript` parses the whole script first (syntax errors stop before anything runs), then executes statements in order; the first runtime error stops the script (earlier statements stay applied unless the script itself wraps them in `BEGIN ... COMMIT`; before step 23 there were no transactions).

## Build it

1. `Executor` constructor builds a `Planner` from the `Catalog`.
2. Implement `select` and `explain` first; they reuse the planner.
3. Implement `insert`. Use the same `ExpressionCompiler` over an **empty** `RowSchema` for value expressions, so `INSERT ... VALUES (1 + 2, UPPER('a'))` works but a column reference fails with `Unknown column`.
4. Implement `matching(table, where, compiler, context)`:

```java
for (Iterator<Table.RowEntry> it = table.scan(); it.hasNext(); ) {
    Table.RowEntry entry = it.next();
    context.tick();
    if (predicate == null || ExpressionCompiler.matches(predicate, entry.row(), "WHERE condition")) {
        context.materialize(1);
        matches.add(entry);
    }
}
```

5. `update`: first `conform` all new rows (so one bad value changes nothing), then call `table.update(id, row)` for each match. The returned `RecordId` is ignored because nothing else holds ids.
6. `Database`: wrap `Catalog.open(dir, new BufferPool(pages))`; every public method `synchronized`.

## Pitfalls

- **Updates read the original row**: `UPDATE t SET a = b, b = a` swaps correctly only if both assignments are evaluated against the same original row.
- **Do not mutate while scanning**: a growing row that relocates to the end of the file would be visited again; snapshotting avoids it.
- **`INSERT` into an unknown column** should fail via `Schema.requireIndexOf` with the column name.
- **`close()` order**: flush every table, then close; leaving pins would make `evictAll` throw.
- `executeScript` is not atomic by itself; do not describe it as a transaction (a script can contain `BEGIN`/`COMMIT`, which does make those statements one).

## Tests to write

`SqlWriteTest` (16): insert with a column list leaves others NULL; widening and constant expressions; **multi-row insert is all-or-nothing when a value is invalid**; invalid inserts rejected; update modifies and counts rows; assignments read the original row; update does not revisit rows it wrote; **failed update changes nothing**; growing rows relocate without loss (300 rows grow from 5 to 900 characters); delete; deleted space reused; create and drop; quoted table names cannot escape the directory; data and schema survive reopen; **works when data is much larger than the buffer pool** (5000 rows through a tiny pool); script runs every statement and stops at the first error.

## Checkpoint

From a `jshell`-like scratch test: open a temp directory, `executeScript` the demo SQL, `query("SELECT COUNT(*) FROM students", QueryOptions.unlimited())` returns 20; close and reopen; still 20.

Commit (with 11–13 and 15): `feat: add SQL planner, executor and read-only query mode with resource limits`.
