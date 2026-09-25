# Step 12 - Planner

Class: `dev.frierendb.engine.exec.Planner` (package-private) and the `PlanNode` interface.

## Goal

Turn a `Statement.Select` into a tree of executable operators, resolving names and rejecting invalid queries before any row is read.

## Concepts

- **Logical pipeline**: SQL has a fixed conceptual order: `FROM/JOIN → WHERE → GROUP BY → HAVING → SELECT → ORDER BY → DISTINCT → LIMIT`.
- **Operator tree**: each operator consumes its child's rows. `EXPLAIN` prints this tree.
- **Hidden sort columns**: `ORDER BY` can refer to an expression that is not in the select list; compute it as an extra column, sort, then strip it.

## Design

### `PlanNode`

```java
interface PlanNode {
    RowSchema schema();
    Iterator<Row> execute(ExecutionContext context);
    void describe(int depth, List<String> out);   // one line + children, indented
    default List<String> explain() { ... }
}
```

> **Later change (step 21):** the planner builds all scans first, compiles the `WHERE`, may replace the `FROM` table's `Scan` by an `IndexScan`, and then builds the joins. The `Filter` below is still the full `WHERE`. Steps 1-2 below describe the original order.

### Pipeline built by `Planner.plan(Select)`

1. **Scan** the `FROM` table with its alias; for each `JOIN`, scan the right table and wrap both in a `Join`. Table aliases must be unique (`Table name 'x' is used more than once; give each use a distinct alias`).
2. **Filter** for `WHERE` (compiled against the joined schema, no aggregates allowed).
3. Decide if the query is **aggregated**: it has `GROUP BY` or any aggregate in the select list, `HAVING` or `ORDER BY`. `HAVING` without grouping or aggregates is an error. `SELECT *` combined with aggregation is an error.
4. Expand the select list into `Output(expr, name)`: `*` and `t.*` expand to columns; a projection's output name is its alias, else the column name for a plain column, else the formatted expression text (e.g. `COUNT(*)`).
5. Build a `GroupScope` if aggregated; compile projections and `HAVING` through a compiler that uses it.
6. Resolve each `ORDER BY` item to a column index in the projected row (`resolveSortKey`), in this order:
   1. an integer literal `n` in `[1, outputs]` → position `n`
   2. an unqualified name equal to an output alias/name → that column
   3. an expression equal (by AST equality) to a select expression → that column
   4. otherwise, if `DISTINCT` → error ("ORDER BY expressions must appear in the select list"); else add a **hidden** projected column
7. Assemble: `Aggregate` (if any) → `Filter` for `HAVING` → `Project` (outputs + hidden) → `Sort` → `Strip` (if hidden columns) → `Distinct` → `Limit`.

### Join planning

For `a JOIN b ON cond`:

- Compile `cond` against the concatenated schema (left columns then right columns).
- Split `cond` into top-level `AND` conjuncts. Every conjunct of the form `col = col` where one column resolves in the left schema and the other in the right becomes a **key pair**.
- If there is at least one pair → hash join on those keys; otherwise nested loop. The complete `cond` is **always** re-evaluated per candidate pair, so extra conditions stay correct.

## Build it

1. `PlanNode` and `RowSchema` (step 11).
2. `Planner` constructor takes a `Catalog`.
3. `scan(TableRef, aliases)`: lookup the table (unknown → `CatalogException`), enforce alias uniqueness (case-insensitive), return `PlanNodes.Scan`.
4. Write `expandSelectList`, `resolveSortKey`, `usesAggregates`/`containsAggregate` (an exhaustive `switch` over `Expr`), `conjuncts`, `join`, `aggregate`.
5. Name bindings of the `Aggregate` output are the formatted group expressions followed by the formatted aggregates (e.g. `s.name`, `COUNT(*)`).
6. Hidden sort columns are named `?order0`, `?order1`, …; the `Strip` node keeps the first `outputs.size()` columns.

## Pitfalls

- **Compile `ORDER BY` before building the aggregate node** so any aggregate used only in `ORDER BY` is registered in the `GroupScope` and computed.
- **`ORDER BY` alias vs column name clash**: prefer the output name (step 6.2), which matches common SQL behaviour.
- **Same alias twice** (self join without aliases) must error rather than silently shadow columns.
- **`SELECT DISTINCT` + `ORDER BY` a non-selected column** is ambiguous (which row's value?); reject it.
- **`LEFT JOIN` + `WHERE` on the right table** is evaluated after the join (so `WHERE e.sid IS NULL` finds unmatched rows). Do not push `WHERE` conditions below an outer join.

## Tests to write

Mostly `SqlQueryTest` (31 cases) through the public `Database` API: select star; ordering by alias, position and unselected column (`orderByAcceptsAliasPositionAndUnselectedColumn`); multiple keys and stability; `DISTINCT` + unselected `ORDER BY` rejected; grouping validation; `HAVING`; joins (inner, left, three-way, non-equi falls back to nested loops, equi-join uses hash join even with extra conditions, NULL keys never match, int/double keys equal); ambiguous and duplicate names rejected; qualified star; inferred column names; `EXPLAIN` text.

## Checkpoint

`EXPLAIN SELECT ...` prints a plausible tree; see [`../lld/06-explain-examples.md`](../lld/06-explain-examples.md) for real output to compare against.
