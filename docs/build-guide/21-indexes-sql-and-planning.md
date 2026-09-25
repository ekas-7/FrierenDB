# Step 21 - Indexes in the Catalog, SQL and the Planner

Classes: `catalog.IndexInfo`, `catalog.TableIndex`, `catalog.Table`, `catalog.Catalog`, `sql.Statement.CreateIndex/DropIndex`, `sql.Parser`, `exec.Executor`, `exec.IndexSelection`, `exec.PlanNodes.IndexScan`, `exec.Planner`.

Prerequisite: step 20 (B+ tree) and steps 8, 10, 12, 14.

## Goal

`CREATE INDEX` / `DROP INDEX`, automatic index maintenance on writes, and `IndexScan` in plans when a `WHERE` clause allows it, **without ever changing a query's result**.

## Part A - Catalog and table

1. **`IndexInfo`**: `record IndexInfo(String name, String table, String column)`.
2. **`TableIndex`** (package-private): one definition + `BPlusTree` + `PageFile`. Methods: `check(row)` (via `tree.checkKey`), `add(row, rid)` and `remove(row, rid)` (skip a `NULL` key), `replace(before, oldId, after, newId)` (no-op when key and id are unchanged), `flush`, `close`.
3. **`Table`**: keep `Map<String, TableIndex>`. Rewrite `insert` / `delete` / `update` as in [LLD 08](../lld/08-index-internals.md#how-tables-keep-indexes-in-step). Add `indexes()`, `checkIndexable(row)`, `indexScan(name, lo, loInc, hi, hiInc)` (iterate the tree, fetch each row), `indexSize(name)`, `buildIndex`, `openIndex`, `attach`, `detach`, `hasIndex`.
4. **`Catalog` file format v2**: after each table's columns write the index count and `(UTF index name, UTF column)` pairs. When loading, `version >= 2` reads them; version 1 means zero indexes. Reject versions above 2. Index files are `<table>.<index>.idx`. Open fails with `CatalogException` if a listed index file is missing.
5. **`createIndex(name, table, column)`**: normalise and validate the name (same rules as tables), reject duplicates *across all tables*, resolve the column, build the **whole file first** (scan the heap, insert each non-NULL key), delete any stale file with that name before starting, attach, save the catalog. On any exception delete the half-built file and leave the catalog alone.
6. **`dropIndex`**, **`dropTable`** (delete index files too, **save the catalog before deleting files**), **`createTable`** (delete a stale file of that name).

Pitfall: save the catalog *before* deleting files. A crash between the two then leaves an orphan file (harmless), instead of a catalog entry whose file is gone (the database would not open).

## Part B - SQL

1. `Statement`: `CreateIndex(name, table, column, ifNotExists)` and `DropIndex(name, ifExists)`. Both keep the default `isReadOnly() == false`.
2. `Parser`: after `CREATE`, `TABLE` or `INDEX`; `CREATE INDEX [IF NOT EXISTS] name ON table ( column )`; `DROP INDEX [IF EXISTS] name`. `index` is **not** a reserved word, so existing tables or columns named `index` keep working.
3. `Executor`: add the two cases; `IF NOT EXISTS` / `IF EXISTS` return 0 affected rows when there is nothing to do.
4. **Validate before writing.** For `INSERT`, prepare every row, then call `table.checkIndexable` on all of them, then write. Same for `UPDATE`: compute all replacement rows, check all, then apply. A bad key (oversized TEXT) now stops the whole statement before the first write.

## Part C - the planner

1. In `Planner.select`, build every `Scan` first (`scan()` now returns `PlanNodes.Scan` with `table()` and `alias()` accessors).
2. Compile the `WHERE` against the combined schema. This reports unknown or ambiguous columns before any index decision.
3. For the `FROM` table only, call `IndexSelection.choose(table, alias, where)`. If present, replace the `Scan` with `IndexScan(table, alias, choice)`, where `choice` carries the index, the bounds and the text shown in `EXPLAIN`.
4. Build the joins on top.
5. Wrap in `Filter(where)` with the **full** predicate regardless.

`IndexSelection` rules are in [LLD 08](../lld/08-index-internals.md#planner-indexselection) and [HLD 07](../hld/07-indexes-and-index-aware-planning.md). Summary: split on `AND`; usable conjuncts are `indexedColumn op constant` with `op` in `= < <= > >=`; constants are folded by evaluating against an empty row schema; skip anything `NULL`, wrongly typed, throwing, or an `INT` beyond ±2^53 against a `DOUBLE`; merge bounds per column; equality beats range; more bounds beat fewer.

`IndexScan.describe`: `IndexScan t [AS a] using idx (cond)`. `Executor.matching(table, where, compiler, context)` (used by `UPDATE` and `DELETE`) calls the same `IndexSelection.choose` (the alias is the table name) and then re-checks the predicate on every row it reads, collecting all matches before anything is modified.

## Pitfalls

- **Never skip the Filter.** It makes a wrong or over-wide index range harmless. It also makes narrowing mistakes the *only* dangerous bug, so test narrowing against an oracle.
- A constant that throws (for example `1/0`) must make the planner fall back to a scan so that the scan raises the same error a user without an index would get.
- Do not narrow the right side of a `LEFT JOIN`: the `WHERE` may legitimately keep rows with `NULL`-extended right columns.
- An `UPDATE` that changes the indexed column can move a row's key and, if the heap relocates the row, its record id too. Both cases must update the index.

## Tests to write

- `CatalogIndexTest`: backfill skips NULLs; maintenance on insert/update/delete; persistence; drop; name rules; oversize key rejected before the row is stored; failed build leaves nothing; a hand-written **version 1** catalog still loads; a missing index file is reported; a stale file does not leak into a re-created table.
- `SqlIndexDdlTest`: statements, `IF [NOT] EXISTS`, unknown table/column, data statements on an indexed table, multi-row insert with one bad key writes nothing, reopen, `DROP TABLE` removes indexes, indexes are not read-only.
- `SqlIndexPlanTest`: plan text for `=`, flipped `5 = a`, ranges, TEXT, aliases; equality beats range; fallbacks (`OR`, functions, `NULL`, type mismatch, big `INT` vs `DOUBLE`); only the `FROM` table of a join; **random predicates compared against the same query with the index defeated by `a + 0`**; `ORDER BY` still sorts; `UPDATE`/`DELETE` keep the index consistent over a random workload.

## Checkpoint

On the demo database: `CREATE INDEX idx_major ON students (major)`, then `EXPLAIN SELECT name FROM students WHERE major = 'CS'` prints

```
Project [name]
  Filter (major = 'CS')
    IndexScan students using idx_major (major = 'CS')
```

and the query returns the same rows as before the index existed. Commit in small steps: B+ tree, catalog, SQL, planner.
