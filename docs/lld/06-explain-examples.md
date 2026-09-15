# LLD 06 - `EXPLAIN` Examples

Real output from the CLI on the demo dataset (`--demo`). Plans are printed top-down: the first line is the **last** operator to run; children are indented and feed their parent.

Reading tips:

- `Scan t AS x (full table scan)`: every row of the table is read; there are no indexes.
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

## Related behaviours seen in the same session

| Input | Output |
|---|---|
| `SELECT 1 / 0 FROM courses LIMIT 1` | `Error: Division by zero` |
| `SELECT * FROM students; DROP TABLE students` | `Error: Unexpected input after the end of the statement; only one statement is allowed at position 24 (found 'DROP')` |
| `SELEKT 1` | `Error: Expected SELECT, INSERT, UPDATE, DELETE, CREATE, DROP or EXPLAIN at position 0 (found 'SELEKT')` |
| `SELECT name FROM students WHERE gpa IN (3.9, NULL)` | `Ada Chen`, `Pia Stone` (a match is found; non-matches become unknown and are dropped) |
| `SELECT COUNT(*), SUM(gpa), AVG(gpa) FROM students WHERE sid > 100` | `0 | NULL | NULL` (a global aggregate over zero rows still yields one row) |
| `SELECT sid, COUNT(*) FROM enrollments` | `Error: Column sid must appear in the GROUP BY clause or be used in an aggregate function` |
| `SELECT ... FROM students a JOIN students a ON ...` | `Error: Table name 'a' is used more than once; give each use a distinct alias` |
