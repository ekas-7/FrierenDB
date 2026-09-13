# Step 15 - Query Limits and Read-Only Mode

Classes: `QueryOptions`, `ExecutionContext`, `QueryLimitException` (package `exec`), `Database.query`, `Statement.isReadOnly`.

## Goal

Make it impossible for a single query to run unbounded in time, output or memory, and impossible for the read-only entry point to modify data.

## Concepts

- **Defence in the engine**: callers (like the agent) get limits for free; they cannot forget to apply them.
- **Cooperative cancellation**: the executor checks a deadline at known points; there are no thread interrupts.
- **Blocking operators** are where memory grows; guard them.

## Design

### `QueryOptions`

```java
public record QueryOptions(long maxRows, Duration timeout, long maxMaterializedRows)
```

- `maxRows ≥ 1` and `maxMaterializedRows ≥ 1` are validated (`IllegalArgumentException`).
- `timeout` of zero means no deadline.
- `unlimited()` = `(Long.MAX_VALUE, ZERO, 1_000_000)`. **Note**: even "unlimited" keeps the default 1,000,000-row memory guard.
- `bounded(maxRows, timeout)` = same memory guard with your row cap and deadline.
- `newContext()` creates a fresh `ExecutionContext` for each statement (so a limit hit by one query never affects the next).

### `ExecutionContext`

| Method | Called by | Effect |
|---|---|---|
| `tick()` | scans, join loops, `matching` | increments a counter; every 1024 ticks compares `System.nanoTime()` with the deadline |
| `checkDeadline()` | `Sort` before sorting | immediate check |
| `materialize(n)` | blocking operators | adds to a running total; over `maxMaterializedRows` → `QueryLimitException` |

Checking the clock every 1024 ticks keeps the overhead negligible while still stopping runaway loops within milliseconds.

### Row cap

`Executor.select` stops pulling after `maxRows` rows and sets `truncated` if the iterator still has more. The caller is told rows were cut off, never silently misled.

### Read-only mode

```java
public synchronized QueryResult query(String sql, QueryOptions options) {
    Statement statement = Parser.parse(sql);
    if (!statement.isReadOnly()) {
        throw new ReadOnlyViolationException();
    }
    return executor.execute(statement, options);
}
```

The decision is made on the **parsed** statement. There is no string inspection (no "does it start with SELECT"), so comments, case tricks and unusual whitespace cannot fool it, and stacked statements already fail in the parser.

## Build it

1. Create `QueryLimitException extends FrierenException` (with `serialVersionUID`).
2. Add `ExecutionContext` and `QueryOptions` as above; thread the context through `PlanNode.execute(context)`.
3. Insert `context.tick()` in `Scan`, in the join probe loop, and in `Executor.matching`.
4. Insert `context.materialize(1)` where rows are retained: `drain` (sort input and join build side), a new group in `Aggregate`, new entries in `Distinct`, matched rows in `matching`.
5. Add `Statement.isReadOnly()` and the `query` method.

## Pitfalls

- **A deadline that is only checked at the end is useless.** Put `tick()` inside inner loops (the join's per-candidate loop), not just per output row: a join whose condition rejects most pairs does a lot of work while emitting almost nothing, so checks tied to output would never fire.
- **Per-statement context**: reusing a context would carry the previous query's counters.
- **The cap is on rows held**, not bytes. A few very large `TEXT` cells still count as one row each; the 4088-byte record limit bounds this.
- **A timed-out query leaves the engine usable**; test that a later query works (`queriesStillWorkAfterALimitAbortedAPreviousOne`).
- `LIMIT` stops work early only for streaming pipelines; `ORDER BY` + `LIMIT` still sorts everything (documented limitation).

## Tests to write (`QuerySafetyTest`, 8)

Read-only mode rejects every modifying statement type (insert, update, delete, create, drop); read-only mode allows `SELECT` and `EXPLAIN`; stacked statements rejected by the parser; SQL inside string values is just data; row cap truncates and reports it; a **runaway cross join** is stopped by the timeout; blocking operators respect the memory cap; queries still work after a limit aborted a previous one.

## Checkpoint

Load 3000 rows into a table `big` and run `select count(*) from big a join big b on 1 = 1` with `QueryOptions.bounded(10, Duration.ofMillis(50))`: it throws `QueryLimitException` (the test asserts it stops in under 5 seconds; 9 million pairs would otherwise be examined), and a plain `SELECT COUNT(*)` right afterwards still works. For the memory cap, a `QueryOptions(100, Duration.ZERO, 10)` makes `ORDER BY`, `DISTINCT` and `GROUP BY` over 50 rows fail, while `LIMIT 10` still succeeds.

Commit: `feat: add SQL planner, executor and read-only query mode with resource limits`.
