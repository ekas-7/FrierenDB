# LLD 04 - SQL Grammar

Notation: `UPPER` = keyword (case-insensitive), `[x]` optional, `{x}` zero or more, `a | b` alternatives, `'x'` literal symbol. This is the grammar **as implemented** in `Parser`; it is smaller than standard SQL.

## Statements

```
statement      = select | explain | insert | update | delete | create_table | drop_table
               | create_index | drop_index | begin | commit | rollback
input          = statement [ ';' ] EOF            -- Parser.parse: exactly one statement
script         = { [ ';' ] statement } separated by ';'    -- Parser.parseScript

explain        = EXPLAIN select
select         = SELECT [ DISTINCT ] select_item { ',' select_item }
                 FROM table_ref { join }
                 [ WHERE expr ]
                 [ GROUP BY expr { ',' expr } ]
                 [ HAVING expr ]
                 [ ORDER BY order_item { ',' order_item } ]
                 [ LIMIT integer ]
select_item    = '*' | name '.' '*' | expr [ [ AS ] alias ]
table_ref      = name [ [ AS ] alias ]
join           = [ INNER | LEFT [ OUTER ] ] JOIN table_ref ON expr      -- bare JOIN = INNER
order_item     = expr [ ASC | DESC ]

insert         = INSERT INTO name [ '(' name { ',' name } ')' ]
                 VALUES row { ',' row }
row            = '(' expr { ',' expr } ')'
update         = UPDATE name SET assignment { ',' assignment } [ WHERE expr ]
assignment     = name '=' expr
delete         = DELETE FROM name [ WHERE expr ]

create_table   = CREATE TABLE [ IF NOT EXISTS ] name '(' column_def { ',' column_def } ')'
column_def     = name type [ '(' number { ',' number } ')' ] [ NOT NULL | NULL ]
drop_table     = DROP TABLE [ IF EXISTS ] name
create_index   = CREATE INDEX [ IF NOT EXISTS ] name ON name '(' name ')'     -- exactly one column
drop_index     = DROP INDEX [ IF EXISTS ] name
begin          = BEGIN [ TRANSACTION ]
commit         = COMMIT [ TRANSACTION ]
rollback       = ROLLBACK [ TRANSACTION ]
type           = INT | INTEGER | BIGINT | DOUBLE | FLOAT | REAL | TEXT | VARCHAR | STRING | BOOLEAN | BOOL
```

Notes:

- The size in `type(...)` is parsed and ignored.
- `index`, `begin`, `commit`, `rollback` and `transaction` are **not** reserved, so they can still be table or column names.
- Only `SELECT` and `EXPLAIN` are read-only (`Statement.isReadOnly()`). `CREATE INDEX`, `DROP INDEX`, `BEGIN`, `COMMIT` and `ROLLBACK` are not, so `Database.query` (the agent's door) rejects them.
- `EXPLAIN` takes a `SELECT` only (`EXPLAIN DELETE ...` is a syntax error).
- Transaction statements are executed by `Database`, not by `Executor`; `CREATE`/`DROP` of tables and indexes are refused inside a transaction (see [`../hld/08-transactions-and-recovery.md`](../hld/08-transactions-and-recovery.md)).
- A comma after the first table (`FROM a, b`) is a specific error: *Comma joins are not supported; use JOIN ... ON*.
- `LIMIT` accepts a plain integer literal only (no sign, decimal point or exponent).

## Expressions (lowest to highest precedence)

```
expr           = or_expr
or_expr        = and_expr { OR and_expr }
and_expr       = not_expr { AND not_expr }
not_expr       = NOT not_expr | comparison
comparison     = additive [ comparator additive
                          | IS [ NOT ] NULL
                          | [ NOT ] IN '(' expr { ',' expr } ')'
                          | [ NOT ] LIKE additive
                          | [ NOT ] BETWEEN additive AND additive ]
comparator     = '=' | '!=' | '<>' | '<' | '<=' | '>' | '>='
additive       = multiplicative { ( '+' | '-' ) multiplicative }
multiplicative = unary { ( '*' | '/' | '%' ) unary }
unary          = '-' number | '-' unary | [ '+' ] primary
primary        = number | string | TRUE | FALSE | NULL
               | function_call | [ name '.' ] name | '(' expr ')'
function_call  = aggregate | scalar
aggregate      = COUNT '(' '*' ')' | ( COUNT | SUM | AVG | MIN | MAX ) '(' [ DISTINCT ] expr ')'
scalar         = ( LOWER | UPPER | LENGTH | ABS | ROUND | COALESCE ) '(' expr { ',' expr } ')'
```

Argument counts: `LOWER/UPPER/LENGTH/ABS` 1; `ROUND` 1–2; `COALESCE` 1 or more.

### Associativity and desugaring

- Binary operators in `additive`/`multiplicative`/`and`/`or` are **left associative**.
- Comparisons are **not chainable**: `a = b = c` fails with *Unexpected input after the end of the statement*.
- `x BETWEEN a AND b` is stored as `(x >= a) AND (x <= b)`.
- `x NOT BETWEEN a AND b` and `x NOT LIKE p` are stored as `NOT (...)`.
- `x NOT IN (...)` is an `InList` with `negated = true`.
- `-5` is a single negative literal; `-x` is a `NEGATE` node.

## Lexical rules

| Item | Rule |
|---|---|
| Keywords/words | `[A-Za-z_][A-Za-z0-9_]*` (Unicode letters allowed); keywords case-insensitive |
| Quoted identifier | `"..."`, `""` inside means a quote; may be a reserved word |
| String | `'...'`, `''` inside means a quote |
| Number | digits, optional fraction, optional exponent (`1`, `1.5`, `.5`, `2e-3`); integer literals must fit in a 64-bit `long` |
| Comment | `-- to end of line` |
| Symbols | `( ) , . * + - / % = < > ;` and `<= >= != <>` |

## Reserved words

`select distinct from where group by having order limit join inner left outer on as and or not is null in like between asc desc insert into values update set delete create table drop if exists explain true false`

A reserved word as a bare name gives: *'x' is a reserved word; quote it with double quotes to use it as a name*. Function names (`count`, `sum`, ...) are **not** reserved, so a column may be called `count`; a name followed by `(` is parsed as a function call.

## Not supported (rejected or absent)

Subqueries (`(SELECT ...)` fails as a reserved word in expression position), CTEs (`WITH`), `UNION`/`INTERSECT`/`EXCEPT`, `CASE`, `OFFSET`, window functions, `ALTER TABLE`, `CREATE UNIQUE INDEX`, multi-column indexes, `SAVEPOINT`, `PRIMARY KEY`/`UNIQUE`/`DEFAULT`/`FOREIGN KEY`, `CAST`, `||` string concatenation, `RIGHT`/`FULL`/`CROSS` joins, comma joins, `INSERT ... SELECT`, `ORDER BY ... NULLS FIRST/LAST`.

## Semantics worth remembering

- `ORDER BY` accepts: a position (`ORDER BY 2`), an output alias/name, a select expression, or any expression over the input (unless `DISTINCT`).
- `GROUP BY` takes expressions; select items must be group expressions or aggregates.
- `SELECT *` cannot be combined with grouping or aggregates.
- `NULL` sorts first ascending, last descending.
