# Step 22 - Write-Ahead Log, Transactional Buffer Pool and Recovery

Classes: `storage.Wal`, `storage.BufferPool` (transaction part), `storage.Recovery`, plus `PageFile.truncate` / `isOpen` and `HeapFile.reload`.

Prerequisite: steps 4, 5 and 7. Read [HLD 08](../hld/08-transactions-and-recovery.md) first for the model; the exact algorithms are in [LLD 09](../lld/09-wal-and-recovery-internals.md).

## Goal

Make changes to pages atomic and durable: a transaction's changes are either all visible after a crash or none are, and a commit that returned never disappears.

## Concepts

- **Write-ahead rule**: a changed page may reach its data file only after a log record that can undo it is on disk.
- **Steal**: the pool may evict (write) an uncommitted page. **No-force**: commit does not write data pages.
- **Redo / undo**: replay after-images of committed work; restore before-images of unfinished work.
- **Idempotent recovery**: running it twice equals once, so a crash during recovery is harmless.
- **Torn write**: a log frame half written at the moment of a crash. A CRC and a length let the reader find where the valid log ends.

## Part A - `PageFile` and `HeapFile` additions

- `PageFile.truncate(int pageCount)`: drop every page from the given count onward (a negative count or one above the current size is a `StorageException`; the same size is a no-op). Used to drop pages a rolled-back transaction allocated.
- `PageFile.isOpen()`: the pool must not sync a file that has been closed (dropped tables).
- `HeapFile.reload()`: clear and rebuild the free-space list from the pages (the constructor calls it). Needed because a rollback changes pages behind the heap's back.

## Part B - `Wal`

File `wal.log`: 8-byte header `0x4657414C "FWAL"` + version `1`, then frames.

Frame: `payload length (4) | CRC-32 of payload (4) | payload`.
Payload: `type (1) | txId (8)` for COMMIT (2) and ABORT (3). UPDATE (1) continues `| file name length (2) | name (UTF-8, 1..255 bytes) | page id (4) | has-before (1) | before (4096)? | after (4096)`.

Implement: `open` (create header; read the valid prefix; trim any torn tail; remember `max txId + 1`), `append` (write at the end), `force` (`channel.force(false)`), `contents`, static `read(path)` (stop at the first short, oversized, CRC-failing or undecodable frame; wrong magic or version is an *error*, not a tail), `size`, `reset` (truncate to the header and force), `nextTxId`. Validate names and page sizes when encoding.

Pitfall: treating a bad **header** as "empty log" would let a foreign file be overwritten. Only a file shorter than the header counts as empty.

## Part C - the transactional `BufferPool`

Fields: `wal`, `transactionId` (0 = none), `modifications` counter, `transactionFiles` (name → `PageFile`, so rollback can find files), `writtenSinceSync`. Per frame: `txBefore`, `txAllocated`.

1. `attachWal`, `begin` (takes `wal.nextTxId()`; error without a log or when one is already active), `inTransaction`, `logSize`, `modifications`.
2. `fetch`: after pinning, if a transaction is active and the frame has no `txBefore`/`txAllocated`, store `txBefore = data.clone()`.
3. `markDirty` increments `modifications`.
4. `allocate`: if a transaction is active, **append and force `UPDATE(before = null, zero page)` for the page id the file is about to get, then grow the file**; mark the frame `txAllocated`.
5. `writeFrame` (used by eviction, `flush`, `checkpoint`): if a transaction is active and the frame changed or was allocated, append `UPDATE(before, after)` and `force()` **before** writing the page.
6. `commit`: for every cached frame that differs from `txBefore` (or was allocated) append an UPDATE; append `COMMIT`; `force()`; clear per-frame transaction state.
7. `rollback`: five steps in [LLD 09](../lld/09-wal-and-recovery-internals.md#rollback-step-by-step): restore cached frames from `txBefore`; undo logged UPDATEs newest first (write `before` to the file and cache; remember truncation for `before == null`); drop cached frames beyond truncation points and call `PageFile.truncate`; `sync` touched files; append `ABORT` and force.
8. `checkpoint`: refuse in a transaction; write every dirty frame, `sync` files written since the last sync, `wal.reset()`.

Pitfalls found while building this:

- **Orphan pages**: growing the file *before* logging the allocation lets a crash leave a page that no record explains. Log and force first.
- **Order of undo**: apply log records newest-first. A page stolen, re-fetched and changed again appears twice; only the oldest before-image is the true original, and it must be written last.
- **Write `ABORT` after the undo is synced**, never before. Recovery skips ABORTed transactions on the strength of that ordering.
- **Pinned frames during truncation** must be an error, not silently dropped.

## Part D - `Recovery.recover(Path dir)`

Run it before opening any page file. Algorithm: [LLD 09](../lld/09-wal-and-recovery-internals.md#recoveryrecoverdir). In short: read the valid prefix; classify transactions as committed, aborted or unfinished; redo committed UPDATEs in log order; undo unfinished UPDATEs newest first (`before == null` → truncate); `sync`; empty the log; return a `Report`. Open files by **name relative to `dir`** and refuse a name with path components, so a damaged log cannot make recovery write elsewhere.

## Tests to write

1. `WalTest`: new log is header only; round-trip records with and without before-image; reopen continues and numbers transactions after the largest; a frame cut short is ignored and trimmed; a flipped byte stops the read at that record; trailing garbage ignored; `reset`; missing file or headerless is empty, foreign file refused; bad names and sizes rejected.
2. `BufferPoolTransactionTest`: `begin` needs a log and cannot nest; commit logs only changed pages with both images; many changes to one page log once with the original as before-image; rollback restores cached pages; **evicted** pages are logged first and undone; a stolen-then-changed-again page returns to the original; committed-but-unflushed changes survive a later rollback; rollback drops allocated pages; allocations are logged as new; checkpoint writes and empties the log; a plain pool without a log behaves as before.
3. `RecoveryTest`: described in the LLD. Simulate a crash by **copying the files of a live pool without closing it**. The most valuable test cuts the log at every record boundary and several interior offsets and asserts all-or-nothing. Be careful to pair a short log with the data-file state that could really exist (before the file grew, for cuts earlier than the allocation record), otherwise the test checks an impossible state.

## Checkpoint

All three test classes pass. Break the write-ahead logging (comment out the log append in `writeFrame`) and confirm a test fails; do the same for rollback's log undo and for each half of recovery; then restore. Commits: `PageFile`/`HeapFile` helpers; `Wal`; transactional `BufferPool`; `Recovery`.
