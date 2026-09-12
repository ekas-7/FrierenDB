# Step 2 - Types and Values

Package: `dev.frierendb.engine.types`.

## Goal

A tiny type system: four data types plus SQL `NULL`, with a total ordering that sorting, grouping and (later) indexing can rely on.

## Concepts

- **SQL `NULL`** is "unknown", not zero or empty. It is not a Java `null`: we model it as a real value so it can flow through rows and operators.
- **Total ordering**: a comparison that is defined for every pair of values we allow to be compared, needed by `ORDER BY`, `MIN`, `MAX`.
- **Sealed interface**: a closed set of implementations; `switch` over it must be exhaustive.

## Design

### `DataType`

```java
public enum DataType { INT, DOUBLE, TEXT, BOOLEAN; ... }
```

- `INT` is a Java `long` (64-bit). `DOUBLE` is `double`. `TEXT` is `String`. `BOOLEAN` is `boolean`.
- SQL names map to types: `INT/INTEGER/BIGINT → INT`, `DOUBLE/FLOAT/REAL → DOUBLE`, `TEXT/VARCHAR/STRING → TEXT`, `BOOLEAN/BOOL → BOOLEAN`. The mapping is case-insensitive and returns `Optional.empty()` for unknown names.
- `isNumeric()` is true for `INT` and `DOUBLE`.

### `Value`

A sealed interface with five record implementations:

| Record | Holds | `type()` |
|---|---|---|
| `NullValue` | nothing | throws (NULL has no type) |
| `IntValue(long)` | integer | `INT` |
| `DoubleValue(double)` | floating point | `DOUBLE` |
| `TextValue(String)` | text | `TEXT` |
| `BoolValue(boolean)` | boolean | `BOOLEAN` |

Factories: `Value.of(long)`, `of(double)`, `of(String)` (rejects Java `null`), `of(boolean)`, and the constant `Value.NULL`.

`toString()` returns the plain value (`NULL`, `42`, `3.5`, `hello`, `true`), which the CLI and tool output rely on.

### Ordering rules (`compareTo`)

1. If either side is `NULL`: `NULL` sorts **before** everything and equals `NULL`.
2. `INT` vs `INT`, `DOUBLE` vs `DOUBLE`, and mixed `INT`/`DOUBLE` compare numerically (`long` is promoted to `double`).
3. `TEXT` compares lexicographically (`String.compareTo`).
4. `BOOLEAN`: `false < true`.
5. Any other mix (for example `TEXT` vs `INT`) throws `FrierenException("Cannot compare X with Y")`.

> The SQL layer converts that exception into a `SqlException` so a user sees a SQL error, not an internal one (step 11).

## Build it

1. Create `DataType` with the `fromSqlName` method (use a `switch` on the upper-cased name, `Locale.ROOT`).
2. Create `Value` as `public sealed interface Value extends Comparable<Value>` with `isNull()` and `type()`.
3. Add the records inside the interface (nested records are implicitly `public static` there).
4. Implement `compareTo` as a `default` method. Core idea:

```java
if (isNull() || other.isNull()) {
    return Boolean.compare(!isNull(), !other.isNull());
}
return switch (this) {
    case IntValue a when other instanceof IntValue b -> Long.compare(a.value(), b.value());
    case IntValue a when other instanceof DoubleValue b -> Double.compare(a.value(), b.value());
    case DoubleValue a when other instanceof IntValue b -> Double.compare(a.value(), b.value());
    case DoubleValue a when other instanceof DoubleValue b -> Double.compare(a.value(), b.value());
    case TextValue a when other instanceof TextValue b -> a.value().compareTo(b.value());
    case BoolValue a when other instanceof BoolValue b -> Boolean.compare(a.value(), b.value());
    default -> throw new FrierenException("Cannot compare " + type() + " with " + other.type());
};
```

   `Boolean.compare(!isNull(), !other.isNull())` yields `0` when both are NULL, negative when only `this` is NULL, positive when only `other` is.

5. Add `FrierenException extends RuntimeException` (with `serialVersionUID`) in the engine root package as the base of all engine errors.

## Pitfalls

- `Long.compare(a, b)` for the `INT`-vs-`DOUBLE` case would not compile; promote to `double`. Be aware very large `long`s lose precision in `double`; this is an accepted limitation.
- `DoubleValue` equality uses record equality, which for `double` uses `Double.compare` semantics (so `NaN` equals `NaN`, and `0.0` differs from `-0.0`). That is fine for hashing and grouping but remember it.
- Do **not** let `NullValue.type()` return a made-up type; failing loudly finds bugs where NULL is mishandled.

## Tests to write (`ValueTest`)

- `NULL` sorts before everything; `NULL` equals `NULL` in ordering.
- `INT` and `DOUBLE` compare numerically (`1 < 1.5`, `2 == 2.0`).
- Text lexicographic order; `false` before `true`.
- Cross-type comparison (`TEXT` vs `INT`) throws.
- `Value.of((String) null)` throws.
- SQL type names map correctly, and unknown names give empty.

## Checkpoint

All `ValueTest` cases pass; `mvn clean test` is green.
