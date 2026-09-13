# Step 13 - Operators

Class: `dev.frierendb.engine.exec.PlanNodes` (a holder of nested operator classes).

## Goal

Implement the pull-based operators the planner assembles.

## Concepts

- **Iterator (Volcano) model**: `execute()` returns an `Iterator<Row>`; each `next()` pulls from the child.
- **Streaming vs blocking**: streaming operators hold one row at a time; blocking ones (sort, aggregate, hash-join build, distinct) must hold many rows.
- **Cost guards**: scans tick a deadline counter; blocking operators report materialized rows.

## The operators

| Operator | Streaming? | What it does |
|---|---|---|
| `Scan(table, alias)` | yes | Iterates `Table.scan()`; calls `context.tick()` per row; schema columns are tagged with the alias |
| `Filter(child, predicate, text, label)` | yes | Keeps rows where the predicate is exactly `TRUE` (`label` appears in error messages) |
| `Join(left, right, type, condition, leftKeys, rightKeys, text)` | build side blocks | See below |
| `Aggregate(child, groupKeys, specs, schema, text)` | blocks | Hash-groups input; emits one row per group |
| `Project(child, expressions, schema, text)` | yes | Computes output columns (plus hidden sort columns) |
| `Sort(child, columns, ascending, text)` | blocks | Collects, checks the deadline, sorts with a multi-key comparator |
| `Strip(child, keep)` | yes | Drops hidden columns; produces no `EXPLAIN` line |
| `Distinct(child)` | grows a set | Passes the first occurrence of each row (canonical values) |
| `Limit(child, n)` | yes | Stops after `n` rows without reading more |

### Join details

1. **Build**: drain the right child into a list (counting each row with `context.materialize(1)`). For a hash join, also build `Map<List<Value>, List<Row>>` keyed by canonical key values, **skipping rows whose key contains NULL**.
2. **Probe**: for each left row, candidates are the matching bucket (hash join) or all right rows (nested loop); a NULL left key has no candidates.
3. For each candidate: `context.tick()`, build the combined row, evaluate the full `ON` condition; if `TRUE`, queue the row.
4. `LEFT` join: if a left row produced no match, emit it padded with NULLs for every right column.
5. The output iterator keeps a small queue (`pending`) so results stream.

### Aggregate details

- Key = list of group expressions evaluated on the row; hash by the **canonical** values but remember the **original** values for output.
- New group → `context.materialize(1)`.
- For each aggregate spec: `accumulator.add(argument == null ? true : argument.apply(row))` (`COUNT(*)` passes a constant).
- **No groups and no `GROUP BY`** (a global aggregate over zero rows): emit one row of accumulator results (`COUNT` = 0, others `NULL`).
- Output order follows first appearance of each group (`LinkedHashMap`).

### Sort details

- Comparison uses `Value.compareTo` on the chosen columns; descending reverses with `Integer.compare(0, c)` (avoids negating `Integer.MIN_VALUE`).
- Java's `List.sort` is stable, so ties keep input order.
- `NULL` first when ascending, therefore last when descending; the `EXPLAIN` line says `(NULLs first when ascending)`.

## Build it

1. Write small helpers: `drain(iterator, context)`, `filtered(iterator, predicate)`, `mapped(iterator, f)` (generic `<A, B>`), `key(row, indexes)`.
2. Implement operators in the order of the table above; the planner can already test them as you go.
3. Each operator implements `describe(depth, out)`; `Scan` prints `Scan <table> [AS <alias>] (full table scan)`.

## Pitfalls

- **Iterators are single-use**; call `execute` once per plan.
- **`hasNext()` must be idempotent**: precompute the next element (as `filtered` does) rather than consuming in `hasNext`.
- **Memory**: forgetting `materialize` on a blocking path lets a query bypass the memory cap.
- **Hash join correctness**: always re-check the full `ON` condition; the hash only narrows candidates.
- **Generic helper**: a `mapped` limited to `Row → Row` cannot adapt `RowEntry → Row`; make it generic.

## Tests to write

Exercised through SQL in `SqlQueryTest` and `QuerySafetyTest` (see step 12), plus the plan shape tests (`equiJoinUsesHashJoinEvenWithExtraConditions`, `nonEquiJoinFallsBackToNestedLoops`, `explainDescribesThePlan`). Add unit tests per operator if you prefer lower-level coverage.

## Checkpoint

`SELECT s.name, COUNT(*) FROM students s JOIN enrollments e ON e.sid = s.sid GROUP BY s.name ORDER BY 2 DESC LIMIT 3` returns the top three by enrollments, and its plan shows `HashJoin`.
