# LLD 01 - Engine Class Reference

Root: `frierendb/engine/src/main/java/dev/frierendb/engine/`. "Visibility" is public unless noted. Line counts are approximate sizes to help you plan effort.

## Root

| Class | Role | Key members |
|---|---|---|
| `Database` | The facade; `AutoCloseable`; every method `synchronized`. Runs recovery on open and owns the transaction state (see [LLD 09](09-wal-and-recovery-internals.md)) | `open(Path[, int bufferPages[, long checkpointBytes]])`, `execute(String)`, `query(String, QueryOptions)` (read-only), `executeScript(String)`, `tableNames()`, `tableSchema(String)`, `tableIndexes(String)`, `inTransaction()`, `recoveryReport()`, `logSize()`, `close()` (idempotent) |
| `TransactionException` | Transaction misuse, DDL inside a transaction, or a transaction rolled back after a failed statement | `(String)`, `(String, Throwable)` |
| `FrierenException` | Base of all expected errors | `(String)`, `(String, Throwable)` |

## `types`

| Class | Role | Notes |
|---|---|---|
| `DataType` (enum) | `INT, DOUBLE, TEXT, BOOLEAN` | `fromSqlName(String)`, `isNumeric()` |
| `Value` (sealed interface) | A typed value or `NULL` | records `NullValue`, `IntValue`, `DoubleValue`, `TextValue`, `BoolValue`; `Value.NULL`; `of(...)` factories; `compareTo` total order with NULL first |

## `schema`

| Class | Role | Notes |
|---|---|---|
| `Column` (record) | name, type, nullable | blank name rejected |
| `Schema` | Ordered columns, name index | `indexOf`, `requireIndexOf`, `conform(List<Value>)`; duplicate names rejected case-insensitively |
| `Row` (record) | Immutable list of `Value` | `of(...)`, `get(i)`, `size()` |
| `RowCodec` | Row ⇄ bytes (null bitmap + values) | `encode(Schema, Row)`, `decode(Schema, byte[])` |
| `SchemaException` | Schema/row/codec errors | |

## `storage`

| Class | Role | Notes |
|---|---|---|
| `PageFile` | Positional 4096-byte page I/O for one file | `open`, `allocate`, `read`, `write`, `truncate(pageCount)`, `sync`, `isOpen`, `pageCount`, `close`; `PAGE_SIZE` |
| `BufferPool` | LRU cache with pin counts; with a `Wal` attached, one transaction at a time with steal / no-force | inner `Frame` (`data()`, `pageId()`, `markDirty()`, `close()`), `fetch`, `allocate`, `flush(file)`, `evictAll(file)`, `hits()`, `misses()`, `cachedPages()`; transactions: `attachWal`, `begin`, `commit`, `rollback`, `checkpoint`, `inTransaction`, `modifications`, `logSize` |
| `Wal` | Append-only log of page images in `wal.log` | `open`, `append`, `force`, `contents`, static `read`, `size`, `reset`, `nextTxId`, `close`; records `LogRecord(type, txId, file, pageId, before, after)` and `Contents(records, validBytes, tornTail)`; enum `Type { UPDATE, COMMIT, ABORT }` |
| `Recovery` | Redo committed and undo unfinished transactions before files are opened | static `recover(Path dir)` → `Report(committedTransactions, unfinishedTransactions, pagesRedone, pagesUndone, tornLogTail)` with `didWork()` |
| `SlottedPage` | Variable-length records inside one page | `format`, `insert`, `get`, `delete`, `update`, `freeBytes`, `isLive`, `slotCount`; `MAX_RECORD_SIZE = 4088` |
| `HeapFile` | Collection of records across pages | `insert`, `get`, `delete`, `update`, `scan`, `flush`, `reload()` (rebuild the free-space cache after a rollback), `pageCount`; record `Entry(RecordId, byte[])` |
| `RecordId` (record) | `(pageId, slot)` | `toString()` = `page:slot` |
| `StorageException` | I/O and storage errors | |

## `index`

| Class | Role | Notes |
|---|---|---|
| `BPlusTree` | Persistent non-unique B+ tree from one column's values to `RecordId`s | `open(file, pool, keyType)`, `insert`, `delete`, `find`, `range(lo, loInclusive, hi, hiInclusive)`, `size`, `height`, `checkKey`, `checkInvariants`; details in [LLD 08](08-index-internals.md) |
| `KeyCodec` (package-private) | Key ⇄ bytes; the 255-byte TEXT limit | `check`, `size`, `write`, `read` |

## `catalog`

| Class | Role | Notes |
|---|---|---|
| `Catalog` | Table and index registry persisted in `catalog.bin` (v2) | `open(dir, pool)`, `createTable`, `dropTable`, `createIndex`, `dropIndex`, `indexExists`, `table`, `exists`, `tableNames`, `reload`, `flush`, `close`; validates names |
| `Table` | Typed rows over a heap file plus its indexes | package-private constructor; `insert`, `get`, `delete`, `update`, `scan`, `indexScan`, `checkIndexable`, `indexes()`, `indexSize`; record `RowEntry(RecordId, Row)`; `name()`, `schema()` |
| `IndexInfo` (record) | `(name, table, column)` | |
| `TableIndex` (package-private) | One index: definition, tree and file | `check`, `add`, `remove`, `replace`, `flush`, `close` |
| `CatalogException` | Registry errors | |

## `sql`

| Class | Role | Notes |
|---|---|---|
| `Lexer` (package-private, 141 lines) | Text → `List<Token>` | `Kind`, `Token(kind, text, position)` |
| `Parser` (637 lines) | Tokens → `Statement` | `parse(String)` (one statement), `parseScript(String)`; recursive descent; depth limit 64 |
| `Expr` (sealed) | Expression AST | `Literal`, `ColumnRef`, `Unary`, `Binary`, `IsNull`, `InList`, `Aggregate`, `Call`; enums `UnaryOp`, `BinaryOp`, `AggregateFunction`, `ScalarFunction` |
| `Statement` (sealed) | Statement AST | `Select`, `Insert`, `Update`, `Delete`, `CreateTable`, `DropTable`, `CreateIndex`, `DropIndex`, `Begin`, `Commit`, `Rollback`, `Explain`; `isReadOnly()` (true only for `Select` and `Explain`) |
| `ExprFormatter` | Expr → text | for column names and `EXPLAIN` |
| `SqlException`, `ReadOnlyViolationException` | SQL errors | |

## `exec`

| Class | Visibility | Role |
|---|---|---|
| `Executor` | public | Executes each `Statement` (not `Begin`/`Commit`/`Rollback`, which `Database` handles); validates all rows' index keys before writing; no flushing (durability is the log's job) |
| `QueryOptions` | public | `(maxRows, timeout, maxMaterializedRows)`; `unlimited()`, `bounded(...)` |
| `QueryResult` | public | `(columns, rows, affectedRows, truncated)` |
| `QueryLimitException` | public | Budget exceeded |
| `Planner` | package | `Select` → `PlanNode` tree |
| `PlanNode` | package | `schema()`, `execute(context)`, `describe(depth, out)`, `explain()` |
| `PlanNodes` | package | `Scan`, `IndexScan`, `Filter`, `Join`, `Aggregate`, `Project`, `Sort`, `Strip`, `Distinct`, `Limit` |
| `IndexSelection` | package | Rule-based choice of an index and key range for a `WHERE`; `choose(table, alias, where)` |
| `ExpressionCompiler` | package | `Expr` → `RowFunction`; handles aggregation scope |
| `GroupScope` | package | Layout of aggregated rows; dedups aggregates |
| `Accumulator` | package | `Count`, `Sum`, `Avg`, `Extreme`, `Distinct` |
| `SqlValues` | package | NULL-aware comparison and arithmetic, `LIKE`, canonical form |
| `RowSchema` | package | Column bindings (`table`, `name`) with ambiguity detection |
| `RowFunction` | package | `Value apply(Row)` |
| `ExecutionContext` | package | Per-statement deadline and memory counters |

## Dependency rules (enforced by convention, visible in imports)

- `types` imports nothing from the engine except `FrierenException`.
- `schema` → `types`. `storage` → nothing (bytes only). `index` → `storage`, `types`. `catalog` → `schema`, `storage`, `index`.
- `sql` → `types`. `exec` → everything below. `Database` → `storage` (`Wal`, `Recovery`, `BufferPool`), `catalog`, `sql`, `exec`.
- Package-private classes in `exec` are an implementation detail; the only public entry points are `Executor`, `QueryOptions`, `QueryResult`, `QueryLimitException`.

## Where to look for a behaviour

| Behaviour | Class and method |
|---|---|
| Statement is read-only | `Statement.Select#isReadOnly`, `Statement.Explain#isReadOnly` |
| Reject stacked statements | `Parser.parse` → `expectEnd()` |
| Row cap and `truncated` | `Executor.select` |
| Deadline | `ExecutionContext.tick` / `checkDeadline` |
| Memory cap | `ExecutionContext.materialize` |
| Hash join key extraction | `Planner.join`, `Planner.conjuncts` |
| Three-valued `AND`/`OR` | `ExpressionCompiler.binary` |
| `ORDER BY` resolution | `Planner.resolveSortKey` |
| Atomic statements, explicit transactions | `Database.write`, `Database.begin/commit/rollback` |
| Durable commit | `BufferPool.commit` (appends COMMIT and forces the log) |
| Crash recovery | `Recovery.recover`, called from `Database.open` |
| Index choice | `IndexSelection.choose` (called from `Planner` and `Executor.matching`) |
| Index maintenance | `Table.insert/update/delete` → `TableIndex` |
| Table-name validation | `Catalog.normalize` |
