# LLD 01 - Engine Class Reference

Root: `frierendb/engine/src/main/java/dev/frierendb/engine/`. "Visibility" is public unless noted. Line counts are approximate sizes to help you plan effort.

## Root

| Class | Role | Key members |
|---|---|---|
| `Database` (77 lines) | The facade; `AutoCloseable`; every method `synchronized` | `open(Path[, int])`, `execute(String)`, `query(String, QueryOptions)`, `executeScript(String)`, `tableNames()`, `tableSchema(String)`, `close()` |
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
| `PageFile` | Positional 4096-byte page I/O for one file | `open`, `allocate`, `read`, `write`, `sync`, `pageCount`, `close`; `PAGE_SIZE` |
| `BufferPool` | LRU cache with pin counts | inner `Frame` (`data()`, `pageId()`, `markDirty()`, `close()`), `fetch`, `allocate`, `flush(file)`, `evictAll(file)`, `hits()`, `misses()`, `cachedPages()` |
| `SlottedPage` | Variable-length records inside one page | `format`, `insert`, `get`, `delete`, `update`, `freeBytes`, `isLive`, `slotCount`; `MAX_RECORD_SIZE = 4088` |
| `HeapFile` | Collection of records across pages | `insert`, `get`, `delete`, `update`, `scan`, `flush`, `pageCount`; record `Entry(RecordId, byte[])` |
| `RecordId` (record) | `(pageId, slot)` | `toString()` = `page:slot` |
| `StorageException` | I/O and storage errors | |

## `catalog`

| Class | Role | Notes |
|---|---|---|
| `Catalog` | Table registry persisted in `catalog.bin` | `open(dir, pool)`, `createTable`, `dropTable`, `table`, `exists`, `tableNames`, `flush`, `close`; validates names |
| `Table` | Typed rows over a heap file | package-private constructor; `insert`, `get`, `delete`, `update`, `scan`; record `RowEntry(RecordId, Row)`; `name()`, `schema()` |
| `CatalogException` | Registry errors | |

## `sql`

| Class | Role | Notes |
|---|---|---|
| `Lexer` (package-private, 141 lines) | Text → `List<Token>` | `Kind`, `Token(kind, text, position)` |
| `Parser` (637 lines) | Tokens → `Statement` | `parse(String)` (one statement), `parseScript(String)`; recursive descent; depth limit 64 |
| `Expr` (sealed) | Expression AST | `Literal`, `ColumnRef`, `Unary`, `Binary`, `IsNull`, `InList`, `Aggregate`, `Call`; enums `UnaryOp`, `BinaryOp`, `AggregateFunction`, `ScalarFunction` |
| `Statement` (sealed) | Statement AST | `Select`, `Insert`, `Update`, `Delete`, `CreateTable`, `DropTable`, `Explain`; `isReadOnly()` |
| `ExprFormatter` | Expr → text | for column names and `EXPLAIN` |
| `SqlException`, `ReadOnlyViolationException` | SQL errors | |

## `exec`

| Class | Visibility | Role |
|---|---|---|
| `Executor` | public | Executes each `Statement`; flushes after non-read-only statements |
| `QueryOptions` | public | `(maxRows, timeout, maxMaterializedRows)`; `unlimited()`, `bounded(...)` |
| `QueryResult` | public | `(columns, rows, affectedRows, truncated)` |
| `QueryLimitException` | public | Budget exceeded |
| `Planner` | package | `Select` → `PlanNode` tree |
| `PlanNode` | package | `schema()`, `execute(context)`, `describe(depth, out)`, `explain()` |
| `PlanNodes` (512 lines) | package | `Scan`, `Filter`, `Join`, `Aggregate`, `Project`, `Sort`, `Strip`, `Distinct`, `Limit` |
| `ExpressionCompiler` | package | `Expr` → `RowFunction`; handles aggregation scope |
| `GroupScope` | package | Layout of aggregated rows; dedups aggregates |
| `Accumulator` | package | `Count`, `Sum`, `Avg`, `Extreme`, `Distinct` |
| `SqlValues` | package | NULL-aware comparison and arithmetic, `LIKE`, canonical form |
| `RowSchema` | package | Column bindings (`table`, `name`) with ambiguity detection |
| `RowFunction` | package | `Value apply(Row)` |
| `ExecutionContext` | package | Per-statement deadline and memory counters |

## Dependency rules (enforced by convention, visible in imports)

- `types` imports nothing from the engine except `FrierenException`.
- `schema` → `types`. `storage` → nothing (bytes only). `catalog` → `schema`, `storage`.
- `sql` → `types`. `exec` → everything below. `Database` → `catalog`, `sql`, `exec`.
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
| Flush after write | `Executor.execute` |
| Table-name validation | `Catalog.normalize` |
