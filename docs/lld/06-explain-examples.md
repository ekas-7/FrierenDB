# LLD 06 - `EXPLAIN` Examples

Real output from the CLI on the demo dataset (`--demo`). Plans are printed top-down: the first line is the **last** operator to run; children are indented and feed their parent.

Reading tips:

- `Scan t AS x (full table scan)`: every row of the table is read.
- `IndexScan t [AS x] using idx (cond)`: only the key range of index `idx` is read (see example 6). A `Filter` with the full `WHERE` always stays above it.
- `Filter ...`: keeps rows where the condition is exactly `TRUE`.
- `HashJoin` is chosen when the `ON` clause contains at least one `column = column` pair across both sides; otherwise `NestedLoopJoin`.
- `Sort [...] (NULLs first when ascending)`: NULLs come first for `ASC` and last for `DESC`.
- Hidden sort columns do not appear in the plan (the internal `Strip` operator prints nothing).

## 1. Join, filter, group, having, order, limit

```sql
EXPLAIN SELECT s.name, COUNT(*) AS n
FROM students s JOIN enrollments e ON e.sid = s.sid
WHERE s.major = 'CS'
GROUP BY s.name HAVING COUNT(*) > 1
ORDER BY n DESC LIMIT 3
```

```
Limit 3
  Sort [n DESC] (NULLs first when ascending)
    Project [s.name, COUNT(*)]
      Filter (COUNT(*) > 1)
        Aggregate group by [s.name] compute [COUNT(*)]
          Filter (s.major = 'CS')
            HashJoin INNER ON (e.sid = s.sid)
              Scan students AS s (full table scan)
              Scan enrollments AS e (full table scan)
```

Execution order (bottom up): scan both tables → hash join (build on `enrollments`) → `WHERE` filter → group and count → `HAVING` filter → project → sort → limit.

## 2. Left join with an extra condition

```sql
EXPLAIN SELECT s.name FROM students s
LEFT JOIN enrollments e ON e.sid = s.sid AND e.grade > 3
WHERE e.sid IS NULL
```

```
Project [s.name]
  Filter e.sid IS NULL
    HashJoin LEFT ON ((e.sid = s.sid) AND (e.grade > 3))
      Scan students AS s (full table scan)
      Scan enrollments AS e (full table scan)
```

The hash key is `e.sid = s.sid`; `e.grade > 3` is rechecked on each candidate pair. The `WHERE` filter runs **after** the outer join, which is why it finds students with no qualifying enrollment.

## 3. `DISTINCT` + `ORDER BY`

```sql
EXPLAIN SELECT DISTINCT major FROM students ORDER BY major
```

```
Distinct
  Sort [major] (NULLs first when ascending)
    Project [major]
      Scan students (full table scan)
```

Sorting happens before duplicate removal; `Distinct` keeps the first occurrence, so the sort order is preserved.

## 4. Sort by a column that is not selected

```sql
EXPLAIN SELECT name FROM students ORDER BY gpa DESC
```

```
Sort [gpa DESC] (NULLs first when ascending)
  Project [name]
    Scan students (full table scan)
```

Internally `Project` also computes a hidden `gpa` column; `Strip` removes it after sorting. Running the query (not `EXPLAIN`) returns `Nia Cole`, `Ada Chen`, `Pia Stone` for `LIMIT 3`; the 3.9 tie keeps table order because the sort is stable.

## 5. Non-equi join

```sql
EXPLAIN SELECT a.name FROM students a JOIN students b ON a.gpa < b.gpa
```

```
Project [a.name]
  NestedLoopJoin INNER ON (a.gpa < b.gpa)
    Scan students AS a (full table scan)
    Scan students AS b (full table scan)
```

No equality pair exists, so every left row is compared with every right row: O(n·m). The query deadline and memory cap exist for exactly this case.

## 6. Index scans

After `CREATE INDEX idx_major ON students (major)`:

```sql
EXPLAIN SELECT name FROM students WHERE major = 'CS'
```

```
Project [name]
  Filter (major = 'CS')
    IndexScan students using idx_major (major = 'CS')
```

The `Filter` is the full `WHERE`; the index only limited which rows are read. With `CREATE INDEX idx_sid ON students (sid)`:

```sql
EXPLAIN SELECT name FROM students WHERE sid = 4 AND major = 'CS'
```

```
Project [name]
  Filter ((sid = 4) AND (major = 'CS'))
    IndexScan students using idx_sid (sid = 4)
```

Only the indexable part (`sid = 4`) selects the range; `major = 'CS'` is checked by the filter. Ranges, and `BETWEEN` (rewritten by the parser to `>=` and `<=`):

```sql
EXPLAIN SELECT name FROM students WHERE sid BETWEEN 2 AND 5
```

```
Project [name]
  Filter ((sid >= 2) AND (sid <= 5))
    IndexScan students using idx_sid (sid >= 2 AND sid <= 5)
```

In a join only the table after `FROM` is narrowed, and only if *its* column is indexed:

```
Project [s.name, e.grade]
  Filter ((s.sid >= 3) AND (s.sid < 6))
    HashJoin INNER ON (e.sid = s.sid)
      IndexScan students AS s using idx_sid (sid >= 3 AND sid < 6)
      Scan enrollments AS e (full table scan)
```

(The CLI table view cuts wide cells with `...`; the `IndexScan` line above is its full text.) With an index only on `enrollments.sid`, the same query shows `Scan students AS s (full table scan)`: the joined table's index is never used.

These do **not** use an index:

| Query | Plan line | Why |
|---|---|---|
| `WHERE sid = 2 OR sid = 3` | `Scan students (full table scan)` | `OR` is not an indexable conjunct |
| `WHERE sid IN (2, 3)` | `Scan students (full table scan)` | `IN` is not handled |
| `WHERE LOWER(major) = 'cs'` | `Scan students (full table scan)` | the column is inside an expression |

## Related behaviours seen in the same session

| Input | Output |
|---|---|
| `SELECT 1 / 0 FROM courses LIMIT 1` | `Error: Division by zero` |
| `SELECT * FROM students; DROP TABLE students` | `Error: Unexpected input after the end of the statement; only one statement is allowed at position 24 (found 'DROP')` |
| `SELEKT 1` | `Error: Expected SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, EXPLAIN, BEGIN, COMMIT or ROLLBACK at position 0 (found 'SELEKT')` |
| `SELECT name FROM students WHERE gpa IN (3.9, NULL)` | `Ada Chen`, `Pia Stone` (a match is found; non-matches become unknown and are dropped) |
| `SELECT COUNT(*), SUM(gpa), AVG(gpa) FROM students WHERE sid > 100` | `0 | NULL | NULL` (a global aggregate over zero rows still yields one row) |
| `SELECT sid, COUNT(*) FROM enrollments` | `Error: Column sid must appear in the GROUP BY clause or be used in an aggregate function` |
| `SELECT ... FROM students a JOIN students a ON ...` | `Error: Table name 'a' is used more than once; give each use a distinct alias` |
