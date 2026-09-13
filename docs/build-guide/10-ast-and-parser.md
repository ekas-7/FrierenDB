# Step 10 - AST and Parser

Package: `dev.frierendb.engine.sql`. Classes: `Expr`, `Statement`, `Parser`, `ExprFormatter`, `ReadOnlyViolationException`.

## Goal

Turn tokens into an immutable syntax tree (the AST) with exact error positions, and classify statements as read-only or not.

## Concepts

- **AST**: a tree describing what the user wrote, before names are resolved.
- **Recursive descent**: one method per grammar rule; precedence comes from which method calls which.
- **Precedence climbing by layering**: lower-precedence rules call higher-precedence rules.

## Design

### Expressions (`sealed interface Expr`)

| Node | Meaning |
|---|---|
| `Literal(Value)` | number, string, `TRUE`, `FALSE`, `NULL` |
| `ColumnRef(table, column)` | `table` is `null` when unqualified |
| `Unary(op, operand)` | `NEGATE`, `NOT` |
| `Binary(op, left, right)` | arithmetic, comparisons, `AND`, `OR`, `LIKE` |
| `IsNull(operand, negated)` | `IS [NOT] NULL` |
| `InList(operand, items, negated)` | `[NOT] IN (...)` |
| `Aggregate(function, argument, distinct)` | `argument == null` means `COUNT(*)` |
| `Call(function, arguments)` | scalar functions |

`BETWEEN` has no node: `x BETWEEN a AND b` becomes `(x >= a) AND (x <= b)`; `NOT BETWEEN` wraps it in `NOT`. `x NOT LIKE p` becomes `NOT (x LIKE p)`.

### Statements (`sealed interface Statement`)

`Select`, `Insert`, `Update`, `Delete`, `CreateTable`, `DropTable`, `Explain`. Supporting records: `SelectItem` (`Star(table)` or `Projection(expr, alias)`), `TableRef(name, alias)`, `Join(type, table, on)`, `OrderItem(expr, ascending)`, `Assignment`, `ColumnDef`.

Every list in a record is defensively copied (`List.copyOf`) in the compact constructor.

`default boolean isReadOnly() { return false; }`, overridden to `true` by `Select` and `Explain`. **This one method is the security boundary** used by `Database.query`.

### Parser entry points

- `Parser.parse(String)`: exactly one statement, optionally followed by one `;`, then end of input. Anything else: `"Unexpected input after the end of the statement; only one statement is allowed"`.
- `Parser.parseScript(String)`: statements separated by `;` (for seed scripts and `Database.executeScript`).

### Precedence (lowest to highest)

```
OR
AND
NOT
comparison:  = != <> < <= > >=   IS [NOT] NULL   [NOT] IN   [NOT] LIKE   [NOT] BETWEEN
+ -
* / %
unary - +
primary: literal, column, function call, ( expression )
```

Comparison is **not chainable**: `a = b = c` is an error. `NOT` applies to the whole comparison below it (`NOT a = b` is `NOT (a = b)`).

### Names and keywords

- Keywords are matched case-insensitively against `WORD` tokens.
- A reserved word cannot be used as a bare identifier (`'select' is a reserved word; quote it with double quotes`); quoted identifiers may be reserved words.
- Optional `AS` for aliases; an alias without `AS` is allowed if the next token is a non-reserved word or quoted identifier.
- Unary minus directly before a number token becomes a **negative literal**, so `-9223372036854775808` (the minimum `long`) parses.
- `LIMIT` takes a non-negative integer literal only.
- Comma joins (`FROM a, b`) are rejected with a message pointing to `JOIN ... ON`.
- `CREATE TABLE`: types by name; a parenthesised size like `VARCHAR(20)` is parsed and **ignored**; `NOT NULL` or `NULL` optional (default nullable).
- Expression nesting is limited to 64 levels (`SqlException("Expression is nested too deeply")`) so malicious input cannot overflow the Java stack.

### Errors

`error(token, message)` builds: `message + " at position N (found 'x')"` (or `found end of input`).

## Build it

1. Write `Expr` and `Statement` first (records and enums only, no logic besides `isReadOnly`).
2. In `Parser`, keep `tokens`, `index`, `depth`. Helpers: `peek(offset)`, `next()`, `acceptKeyword`, `expectKeyword`, `acceptSymbol`, `expectSymbol`, `identifier()`.
3. `statement()` dispatches on the first keyword.
4. `select()`: `DISTINCT?` items `FROM` tableRef joins `WHERE?` `GROUP BY?` `HAVING?` `ORDER BY?` `LIMIT?`.
5. Expression methods in order: `expression` (OR) → `and` → `not` → `comparison` → `additive` → `multiplicative` → `unary` → `primary`. Each loops for its operators and calls the next level for operands:

```java
private Expr additive() {
    Expr left = multiplicative();
    while (true) {
        if (acceptSymbol("+")) left = new Expr.Binary(BinaryOp.ADD, left, multiplicative());
        else if (acceptSymbol("-")) left = new Expr.Binary(BinaryOp.SUBTRACT, left, multiplicative());
        else return left;
    }
}
```

6. `wordExpression`: `TRUE/FALSE/NULL` literals; a word followed by `(` is a function call; otherwise a column reference with an optional `table.` prefix.
7. `functionCall`: aggregates first (`COUNT(*)`, `COUNT(DISTINCT x)`), then scalar functions with an argument-count check from `ScalarFunction.acceptsArgumentCount`; unknown names → `Unknown function X`.
8. `ExprFormatter.format(Expr)`: renders an expression back to text for column names and `EXPLAIN` (binary operations in parentheses).
9. `ReadOnlyViolationException extends SqlException` with a fixed message ("Only SELECT and EXPLAIN statements are allowed in read-only mode").

## Pitfalls

- **Left associativity**: use loops, not recursion, for `+ - * /` so `a - b - c` means `(a - b) - c`.
- **`NOT IN` / `NOT LIKE` / `NOT BETWEEN`**: look ahead two tokens before consuming `NOT`.
- **Aliases vs keywords**: `SELECT name FROM students WHERE ...`: `WHERE` is reserved so it is never taken as an alias.
- **Explicit end check**: without `expectEnd`, `SELECT 1; DROP TABLE x` would parse the first statement and ignore the rest. This is the stacked-statement defence.
- **Negative literals**: handle `-` before a number in `unary()`; otherwise `Long.MIN_VALUE` cannot be written.
- **Depth counter must be decremented in `finally`** or error recovery would skew it.

## Tests to write (`ParserTest`, 27 cases)

Basic select with aliases and `*`; qualified star; case-insensitive keywords; `AND` binds tighter than `OR`; arithmetic precedence and parentheses; `NOT` scope; `IN`, `BETWEEN`, `LIKE`, `IS NULL`; aggregates with `GROUP BY`/`HAVING`/`ORDER BY`/`LIMIT`; `COUNT(DISTINCT x)`; joins; scalar functions; negative numbers including the long minimum; string escapes and comments; quoted identifiers that are reserved words; multi-row insert; update/delete/create/drop/explain; **read-only classification**; one trailing semicolon allowed; multiple statements rejected; `parseScript` splits; syntax errors carry positions; unterminated strings and odd characters; unknown functions and wrong arity; comma joins and reserved names rejected; malformed `LIMIT`; pathological nesting; unknown column types.

## Checkpoint

`Parser.parse("SELECT a FROM t WHERE x = 1 OR y = 2 AND z = 3")` produces an `OR` whose right child is an `AND`. `Parser.parse("SELECT 1; DROP TABLE t")` throws.

Commit: `feat: add SQL lexer, sealed AST and recursive-descent parser`.
