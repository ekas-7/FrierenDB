# Step 11 - Expression Semantics

Package: `dev.frierendb.engine.exec`. Classes: `SqlValues`, `RowFunction`, `RowSchema`, `ExpressionCompiler`, `Accumulator`, `GroupScope`.

## Goal

Evaluate expressions over rows with correct SQL semantics: NULL propagation, three-valued logic, numeric promotion, overflow checks, `LIKE`, aggregates and scalar functions.

## Concepts

- **Three-valued logic**: conditions are `TRUE`, `FALSE` or `UNKNOWN` (represented by `NULL`).
- **Compile once, run per row**: resolve column names to indexes once, produce a function.
- **Aggregation scope**: after `GROUP BY`, only group keys and aggregates are legal.

## Design

### `RowFunction`

```java
@FunctionalInterface interface RowFunction { Value apply(Row row); }
```

### `RowSchema`

A list of `Binding(table, name)`. `table` is the alias a column came from (null after projection). `find(table, column)` is case-insensitive, returns empty if absent, throws `Ambiguous column reference` if two columns match; `resolve` throws `Unknown column`.

### `SqlValues` (value-level rules)

| Rule | Behaviour |
|---|---|
| Comparison | If either side is `NULL` → `NULL`. Otherwise use `Value.compareTo`; incompatible types become `SqlException` |
| `WHERE`/`ON`/`HAVING` filter | keep a row only if the result is exactly `TRUE`; a non-boolean, non-NULL result is an error |
| Arithmetic | `NULL` in → `NULL` out. `INT op INT` stays integer with `Math.addExact`-style checks; anything with a `DOUBLE` becomes `double` |
| Errors | integer overflow, `x / 0`, `x % 0` (including `0.0`), `-Long.MIN_VALUE`, `ABS(Long.MIN_VALUE)` → `SqlException` |
| `MODULO` by `-1` | returns 0 (avoids the `Long.MIN_VALUE % -1` trap) |
| `LIKE` | `%` any sequence, `_` one character, case-sensitive, **iterative two-pointer algorithm** (no regex, no exponential backtracking) |
| `canonical(v)` | a `DOUBLE` that is a whole number below 9e18 becomes an `INT` so `2` and `2.0` hash and group together |

### Truth tables implemented in `ExpressionCompiler`

`AND`: `FALSE` if either side is `FALSE` (short-circuit on a false left), else `NULL` if either is `NULL`, else `TRUE`.
`OR`: `TRUE` if either is `TRUE`, else `NULL` if either is `NULL`, else `FALSE`.
`NOT`: `NULL` stays `NULL`.
`x IN (a, b, ...)`: `NULL` if `x` is NULL; `TRUE` if any equals; else `NULL` if any item was NULL; else `FALSE`. `NOT IN` negates only definite results.
`IS [NOT] NULL`: always `TRUE` or `FALSE`.

### Functions

| Function | Notes |
|---|---|
| `LOWER`, `UPPER` | text only; NULL stays NULL |
| `LENGTH` | number of Unicode code points |
| `ABS` | int or double |
| `ROUND(x [, digits])` | `HALF_UP` through `BigDecimal`; digits in [-15, 15]; ints round only for negative digits |
| `COALESCE(a, b, ...)` | first non-NULL |

### Aggregates (`Accumulator`)

One accumulator per distinct aggregate per group:

| Aggregate | NULL handling | Result over no input |
|---|---|---|
| `COUNT(*)` | counts all rows | 0 |
| `COUNT(x)` | skips NULLs | 0 |
| `SUM(x)` | skips NULLs; INT stays INT with overflow check; mixed becomes DOUBLE | `NULL` |
| `AVG(x)` | skips NULLs; always DOUBLE | `NULL` |
| `MIN/MAX(x)` | skips NULLs; uses `Value.compareTo` | `NULL` |
| `DISTINCT` | wrapper that passes only the first occurrence of each canonical value | – |

### `GroupScope`

Describes the aggregated row layout: `[group-key values..., aggregate results...]`. When the compiler is created **with** a scope:

1. An expression equal to a `GROUP BY` expression (or a column that resolves to a group column) compiles to a read of that position.
2. An `Aggregate` registers itself in the scope (`positionOfAggregate`, deduplicated by AST equality) and compiles to a read of its result position.
3. Any other bare column → `Column x must appear in the GROUP BY clause or be used in an aggregate function`.

Without a scope, an aggregate anywhere is an error (`Aggregate function ... is not allowed here`), which covers `WHERE COUNT(*) > 1`.

## Build it

1. `SqlValues` with static methods, tested in isolation (no database needed).
2. `RowSchema` and `RowFunction`.
3. `ExpressionCompiler.compile(Expr)`: a `switch` over the sealed `Expr`; each case returns a lambda that captures already-compiled child functions and resolved indexes. Excerpt:

```java
case Expr.ColumnRef c -> {
    int index = input.resolve(c.table(), c.column());
    yield row -> row.get(index);
}
```

4. `Accumulator` implementations, then `GroupScope`.
5. `static boolean matches(RowFunction, Row, String context)` used by every filter-like operator.

## Pitfalls

- **Do not use `Math.floorMod`/`%` blindly on `Long.MIN_VALUE`** with `-1`.
- **Equal numbers must group together**: use `canonical` for hash keys (group keys, join keys, `DISTINCT`), but keep the **original** values for output.
- **NULL keys**: `NULL = NULL` is unknown, so NULL join keys never match (but `GROUP BY` and `DISTINCT` treat NULLs as one group, per SQL).
- **Compile-time vs run-time errors**: unknown columns must fail at compile time (before reading rows).
- `AND` must not short-circuit on `NULL`; only on definite `FALSE`.

## Tests to write

`SqlValuesTest` (10): `LIKE` wildcards; no blow-up on pathological patterns; integer arithmetic stays integral; mixed arithmetic promotes; NULL propagation; overflow and division by zero are errors; modulo by -1; incompatible comparison is a SQL error; arithmetic on text is an error; canonical form makes equal numbers hash alike.

`SqlQueryTest` covers truth tables, `IN`/`NOT IN` with NULLs, aggregates over no rows, and grouping validation (`whereExcludesNullComparisons`, `notAndOrFollowThreeValuedLogic`, `inAndNotInHandleNulls`, `aggregatesOverNoRowsFollowSqlRules`, `groupByValidationRejectsUngroupedColumns`).

## Checkpoint

You can evaluate `SELECT`-free expressions against hand-built rows in unit tests, and every NULL/overflow rule above has a test.
