# Step 23 - Transactions in `Database`, SQL Control Statements and Crash Tests

Classes: `Database`, `TransactionException`, `sql.Statement.Begin/Commit/Rollback`, `sql.Parser`, `catalog.Catalog.reload`, `catalog.Table.reload`, `exec.Executor`.

Prerequisite: step 22.

## Goal

Expose the log to users: every write statement is atomic, `BEGIN`/`COMMIT`/`ROLLBACK` group statements, and opening a database after a crash recovers it. The agent must still be unable to start, commit or roll back anything.

## Part A - SQL

1. `Statement`: records `Begin`, `Commit`, `Rollback`; they keep `isReadOnly() == false`, so `Database.query` rejects them with `ReadOnlyViolationException` (this is what keeps the agent unable to control transactions).
2. `Parser`: `BEGIN [TRANSACTION]`, `COMMIT [TRANSACTION]`, `ROLLBACK [TRANSACTION]`. Add them to the "expected one of ..." error message. Do **not** make `begin`, `commit`, `rollback` or `transaction` reserved words (a column may be called `transaction`).
3. `Executor`: the three cases throw `SqlException("BEGIN, COMMIT and ROLLBACK must be run through Database.execute")`. `Database` must intercept them first. Remove the `catalog.flush()` that used to follow each write: durability now comes from the log.
4. `TransactionException extends FrierenException` (constructors `(String)` and `(String, Throwable)`; add `serialVersionUID`).

## Part B - reload after rollback

`Table.reload()` calls `heap.reload()`; `Catalog.reload()` calls it on every table. Index trees need nothing (they have no cache beyond page 0).

## Part C - the new `Database`

State: `BufferPool pool`, `Wal wal`, `Catalog catalog`, `Executor executor`, `checkpointBytes`, the `Recovery.Report`, `explicitTransaction`, `closed`.

**Open** `open(dir, bufferPages, checkpointBytes)` (the shorter overloads default to 256 pages and 4 MiB):

1. `createDirectories(dir)`.
2. `Recovery.recover(dir)` (before any page file is opened).
3. `new BufferPool(pages)`, `Wal.open(dir/wal.log)`, `pool.attachWal(wal)`.
4. `Catalog.open(dir, pool)`; if it throws, close the log and rethrow.

**Dispatch** (all `synchronized`; check `closed` first):

| Statement | Action |
|---|---|
| `Begin` | error if already in a transaction; `pool.begin()` |
| `Commit` / `Rollback` | error if none; commit, or `abort()` = `pool.rollback()` + `catalog.reload()` |
| `Select` / `Explain` | executor directly |
| `CreateTable/DropTable/CreateIndex/DropIndex` | inside a transaction: `TransactionException`. Otherwise `pool.checkpoint()`, execute, `catalog.flush()` |
| `Insert/Update/Delete` outside a transaction | `begin`, execute, `commit`; on `RuntimeException`, `abort()` (suppress secondary failures with `addSuppressed`) and rethrow; then `checkpointIfLogIsLarge()` |
| same, inside a transaction | execute; if it throws **and** `pool.modifications()` changed, `abort()`, clear the flag and throw `TransactionException` with the cause; if nothing changed, rethrow and keep the transaction |

Why DDL checkpoints first: creating or dropping files happens outside the log, and log records name files. If the log still held records for a table that is then dropped and re-created under the same name, a later recovery would replay the old table's pages into the new file. A checkpoint empties the log first, so no record can refer to a file that is about to change.

`close()`: idempotent; abort an open transaction; `pool.checkpoint()`; `catalog.close()`; `wal.close()` in a `finally`.

Also expose `inTransaction()`, `recoveryReport()`, `logSize()`, `tableIndexes(table)`.

`query(...)` is unchanged except that it first checks the database is open; it still accepts only `SELECT`/`EXPLAIN`. It can see uncommitted changes of an open transaction (single session): say so in the docs.

## Pitfalls

- **Forgetting `catalog.reload()` after a rollback** leaves the heap free-space cache pointing at pages the rollback truncated; the next insert fails with `Page 0 does not exist`. The regression test `pagesAllocatedByARolledBackTransactionLeaveNoStaleFreeSpaceBehind` exists for this and must use a table that *starts empty* (otherwise a stale entry is never reached because first-fit finds an older page first).
- A multi-row `INSERT` with a bad row must not leave the earlier rows behind. With the transaction wrapper this holds even when the failure comes from the storage layer (an oversized record) rather than validation.
- `Database.executeScript` still stops at the first error, but a script may now contain `BEGIN ... COMMIT`.
- Do not checkpoint inside a transaction (`BufferPool` refuses).

## Tests to write

`SqlTransactionTest` (17 tests): commit visible and durable across reopen; rollback of inserts, updates and deletes including the index; rollback survives reopen; freed space not lost; misuse errors; DDL refusal inside a transaction; failure that changed nothing keeps the transaction; failure after changes rolls everything back; partially failing statements undone entirely (multi-row insert, oversized update); read-only `query` rejects control statements; close with an open transaction discards it; scripts with transactions; checkpoint threshold; closing empties the log; use after close.

`CrashRecoveryTest` (8 tests): helper `crash()` copies the directory of the **open** database. Cases: committed statements survive lost data pages; an open transaction is undone even when its pages were stolen (small pool); committed transaction with stolen pages; a recovered database needs no recovery the second time; recovering twice is stable; log checkpointing keeps the log bounded and recovery still matches; **log cut at many offsets gives all-or-nothing** (use an update-only transaction on non-indexed columns so no file grows, and assert that no allocation record exists); **random workloads with crashes** compared to a plain `TreeMap`-based oracle, with an index-versus-scan consistency check after every recovery.

## Checkpoint

From the CLI:

```
frieren> BEGIN
frieren> DELETE FROM students WHERE sid = 1
frieren> SELECT COUNT(*) FROM students      -- 19 (demo has 20 students)
frieren> ROLLBACK
frieren> SELECT COUNT(*) FROM students      -- 20
frieren> BEGIN
frieren> CREATE TABLE z (a INT)
Error: CREATE and DROP cannot run inside a transaction; COMMIT or ROLLBACK first
```

Then break recovery on purpose (skip `Recovery.recover` in `open`): all of `CrashRecoveryTest` must fail. Restore it. Commits: control statements in the parser; reload helpers; `Database`; each test class.
