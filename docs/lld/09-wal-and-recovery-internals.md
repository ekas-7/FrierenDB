# LLD 09 - Write-Ahead Log, Transactions and Recovery Internals

Classes: `storage.Wal`, `storage.BufferPool` (transaction part), `storage.Recovery`, `storage.PageFile` (`truncate`, `isOpen`), `storage.HeapFile.reload`, `catalog.Catalog.reload`, and `Database` (statement dispatch). Byte layout of `wal.log` is in [`03-on-disk-formats.md`](03-on-disk-formats.md).

## Model

- **One transaction at a time.** `BufferPool` holds a single `transactionId` (0 means none). `Database` is `synchronized` and refuses a second `BEGIN`.
- **Physical logging.** An UPDATE record holds the whole *after* image of a page and, unless the page was allocated by the transaction, the whole *before* image.
- **No BEGIN record.** A transaction exists in the log from its first UPDATE. Ids come from `Wal.nextTxId()`, which continues after the highest id found when the log was opened (so ids never collide with records still in the log).
- **Record types**: `UPDATE(txId, file, pageId, before?, after)`, `COMMIT(txId)`, `ABORT(txId)`.
- **Steal / no-force**: uncommitted pages may be written to data files; committed pages need not be.

## `Wal`

| Method | Behaviour |
|---|---|
| `open(path)` | Creates the file with an 8-byte header if missing or shorter than 8 bytes. Reads the valid prefix, **trims a torn tail**, sets `nextTxId` |
| `append(record)` | Writes one frame at the end; **not yet durable** |
| `force()` | `FileChannel.force(false)`; everything appended so far is durable when it returns |
| `contents()` / static `read(path)` | The valid prefix as `Contents(records, validBytes, tornTail)` |
| `size()` | Bytes in the log including the header (8 when empty) |
| `reset()` | Truncates to the header and forces; used by checkpoint and recovery |
| `nextTxId()` | Next transaction id |

Reading stops at the first frame that is incomplete, has a length outside `[1, MAX_PAYLOAD]`, fails its CRC-32, or does not decode. Everything before it is trusted; everything after is discarded. A bad **header** (wrong magic or version) is an error, not a torn tail, so a foreign file is never silently overwritten.

## `BufferPool` transaction rules

State per frame: `txBefore` (copy of the page bytes at the transaction's first fetch of it) and `txAllocated`.

| Operation | Rule |
|---|---|
| `begin()` | Needs an attached WAL and no active transaction; takes `wal.nextTxId()` |
| `fetch(file, id)` in a transaction | First fetch of a page stores `txBefore = copy of bytes`; the file is remembered by name (`transactionFiles`) |
| `allocate(file)` in a transaction | **Before the file grows**: append `UPDATE(before = null, after = zero page)` for the new page id and `force()`. Then allocate and mark `txAllocated` |
| evict / write of a frame touched by the transaction (`writeFrame`) | **Write-ahead rule**: if the frame changed (or was allocated) in this transaction, append `UPDATE(before, after)` and `force()` *then* write the page. A stolen page leaves the cache; if it is fetched again its new before-image is the page as stolen, which is why rollback and recovery apply records **newest first** |
| `commit()` | Log every cached page that differs from its before-image (or was allocated), append `COMMIT`, `force()`. Data pages are **not** written. Pages stolen earlier were already logged |
| `rollback()` | See below |
| `checkpoint()` | Refused inside a transaction. Writes every dirty frame, `sync()`s every file written since the last sync, `wal.reset()` |
| `modifications()` | Counter of page modifications; `Database` compares readings to ask "did this statement change anything?" |

### Why log before the file grows

If `allocate` grew the file first and the process died before any log record existed, recovery would never learn about the extra page, and an index or heap could be left with an orphan page that nothing references. Logging `before = null` first lets recovery truncate the file back to the page id.

### `rollback()` step by step

1. For each cached frame: if it was allocated by this transaction, remember to truncate its file to that page id; else if its bytes differ from `txBefore`, copy `txBefore` back and mark it dirty.
2. Read the log; take this transaction's UPDATE records. **Newest first**, for each: if `before == null`, remember to truncate to the page id; else write `before` to the data file (and into the cached frame if there is one). Newest first means the oldest before-image is written last, which is the page's true original.
3. For each file with truncation: drop its cached frames at or beyond the cut (fails if one is still pinned), then `PageFile.truncate`.
4. `sync()` every touched file.
5. Append `ABORT`, `force()`, end the transaction.

The `ABORT` record is written **after** the undo has been made durable. If a crash happens before it, recovery sees an unfinished transaction and repeats the undo, which is idempotent.

## `Recovery.recover(dir)`

Runs in `Database.open` before the buffer pool or any page file exists.

1. Read the log's valid prefix. If it is empty (and possibly torn), empty the log and return.
2. Classify transaction ids: **committed** (has COMMIT), **aborted** (has ABORT), **unfinished** (neither).
3. **Redo**: for every UPDATE of a committed transaction, in log order, write the after-image (growing the file with zero pages if the page id is beyond its end).
4. **Undo**: for every UPDATE of an unfinished transaction, **newest first**: `before == null` → truncate the file to that page id (if it is longer); else write the before-image.
5. `sync()` every touched file, close them, `Wal.reset()`.
6. Return `Report(committedTransactions, unfinishedTransactions, pagesRedone, pagesUndone, tornLogTail)`.

Safety checks (in code): a file name in the log must have no path components (`StorageException` otherwise); a file that no longer exists is skipped; a log with a foreign header is refused. Each of these has a test.

Aborted transactions are skipped entirely because their undo finished before ABORT was logged. Idempotence: redo writes fixed images, undo writes fixed images, so running recovery twice gives the same bytes, and a crash inside recovery is repaired by the next recovery (the log is emptied last).

The argument that redo-then-undo is correct depends on transactions never overlapping: an unfinished transaction is always the **last** one in the log, so its undo never reverts a committed neighbour's later change.

## `Database` statement dispatch

| Statement | Path |
|---|---|
| `SELECT`, `EXPLAIN` | executor directly (works inside or outside a transaction) |
| `BEGIN` | `pool.begin()`, flag `explicitTransaction`; error if one is open |
| `COMMIT` | error if none is open; `pool.commit()`; then `checkpointIfLogIsLarge()` |
| `ROLLBACK` | error if none; `abort()` = `pool.rollback()` then `catalog.reload()` |
| `INSERT`/`UPDATE`/`DELETE`, no explicit transaction | `begin`, execute, `commit`; on `RuntimeException` roll back (if still in a transaction) and rethrow; then checkpoint if the log is large |
| same, inside an explicit transaction | execute; on failure, if `pool.modifications()` changed → `abort()`, end the transaction, throw `TransactionException` (cause attached); if not changed → rethrow and keep the transaction |
| `CREATE`/`DROP` (table or index) | refused in a transaction. Otherwise `pool.checkpoint()` (so no log record can name a file that is about to be dropped or re-created), execute, `catalog.flush()` |

`close()` is idempotent: roll back any open transaction, `pool.checkpoint()`, close the catalog, close the log (the log is closed even if earlier steps throw). A closed database throws `FrierenException("The database is closed")`.

`catalog.reload()` calls `Table.reload()` → `HeapFile.reload()` for every table, rebuilding the in-memory free-space list from the (restored) pages. Without it, a rolled-back insert that had allocated pages would leave cache entries for pages that no longer exist; a test (`pagesAllocatedByARolledBackTransactionLeaveNoStaleFreeSpaceBehind`) fails with `Page 0 does not exist` if the reload is removed.

## Checkpoint policy

`Database.open(dir, bufferPages, checkpointBytes)`; default 4 MiB. After a commit, if `logSize() > checkpointBytes`, `pool.checkpoint()`. Also: before every `CREATE`/`DROP` and in `close()`. With a big pool nothing is written to data files between checkpoints, so the log is the only copy of recent work: that is the purpose of the log, and also why it must be forced at commit.

## Test map

| Test class | What it proves |
|---|---|
| `WalTest` | Round trip of all record types, CRC and torn-tail trimming, bad headers, `nextTxId`, `reset` |
| `PageFileTest`/`HeapFileTest` additions | `truncate`, `isOpen`, `reload` |
| `BufferPoolTransactionTest` | Commit and rollback with and without eviction (steal), allocation rollback, write-ahead ordering, checkpoint rules |
| `RecoveryTest` | Redo, undo of stolen pages, ABORT skipped, torn tails, missing files skipped, foreign log refused, a log naming `../x` refused, idempotence, crash during recovery, **every record boundary and interior offsets of a log cut short** leave the transaction all or nothing |
| `SqlTransactionTest` | `BEGIN`/`COMMIT`/`ROLLBACK`, misuse errors, DDL refusal, atomic statements, "fail after change rolls back all", reads cannot control transactions, close discards, checkpoint threshold, stale-cache regression |
| `CrashRecoveryTest` | Copying the directory of a live database: committed work survives, open transactions are undone (small pool, so pages were stolen), log cut at many offsets gives all or nothing, recovery twice is stable, random transactions with crashes match a plain-map oracle, index equals table after recovery |

Mutation checks done while developing (each made the named tests fail, then was reverted): removing the write-ahead logging, skipping the log undo in rollback, skipping recovery's undo or redo, skipping recovery on open, removing `catalog.reload()` after rollback.

## What is not modelled

The crash simulation copies files that the OS has already been given. It does not simulate a disk that reorders or drops writes that were `force`d, power loss in the middle of a 4 KiB page write, or media corruption inside a log frame that still passes its CRC.
